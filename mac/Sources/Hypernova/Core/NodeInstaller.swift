import CryptoKit
import Foundation

/// Downloads the official Node.js LTS for this Mac into <support>/node/<version>/, verified
/// against nodejs.org's SHASUMS256.txt. Nothing outside Hypernova's folder is touched.
enum NodeInstaller {
    struct Release: Equatable {
        var version: String     // "v24.11.1"
        var ltsName: String     // "Krypton"
        var file: String        // "node-v24.11.1-darwin-arm64.tar.gz"
        var url: URL
    }

    enum Phase: Equatable { case resolving, downloading(Double, Int64, Int64), verifying, extracting, done(String) }

    static var arch: String {
        #if arch(arm64)
        return "arm64"
        #else
        return "x64"
        #endif
    }

    /// The newest LTS release that has a macOS tarball for this architecture.
    static func latestLTS() async throws -> Release {
        let (data, resp) = try await URLSession.shared.data(from: Endpoints.nodeIndex)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw err("nodejs.org didn't answer. Check your internet connection and try again.") }
        guard let list = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw err("nodejs.org sent an unexpected release list.") }
        let fileKey = "osx-\(arch)-tar"
        for r in list {
            guard let v = r["version"] as? String, let lts = r["lts"] as? String, let files = r["files"] as? [String], files.contains(fileKey) else { continue }
            guard let nv = NodeVersion(v), nv >= NodeVersion.minimum else { continue }
            let file = "node-\(v)-darwin-\(arch).tar.gz"
            return Release(version: v, ltsName: lts, file: file, url: Endpoints.nodeDist(v).appendingPathComponent(file))
        }
        throw err("Couldn't find a Node.js LTS release for this Mac.")
    }

    static func expectedSHA256(for release: Release) async throws -> String {
        let (data, resp) = try await URLSession.shared.data(from: Endpoints.nodeDist(release.version).appendingPathComponent("SHASUMS256.txt"))
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw err("Couldn't get Node.js checksums from nodejs.org.") }
        for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
            let parts = line.split(separator: " ", omittingEmptySubsequences: true)
            if parts.count == 2, parts[1] == release.file { return String(parts[0]).lowercased() }
        }
        throw err("nodejs.org's checksum list has no entry for \(release.file).")
    }

    static func sha256(of file: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: file)
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try h.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Downloads, verifies and unpacks. Returns the path to the new `bin/node`.
    /// `into` defaults to <support>/node; the self-test passes a scratch folder.
    static func install(into root: URL = Paths.nodeDir, progress: @escaping @Sendable (Phase) -> Void) async throws -> String {
        progress(.resolving)
        let release = try await latestLTS()
        let expected = try await expectedSHA256(for: release)

        let fm = FileManager.default
        try fm.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let tarball = try await Downloader().download(release.url) { done, total in
            progress(.downloading(total > 0 ? Double(done) / Double(total) : 0, done, total))
        }
        defer { try? fm.removeItem(at: tarball) }

        progress(.verifying)
        let actual = try await Task.detached { try sha256(of: tarball) }.value
        guard actual == expected else {
            throw err("The download didn't match nodejs.org's checksum, so Hypernova threw it away. Try again; if it keeps happening, your network may be altering downloads.")
        }

        progress(.extracting)
        let staging = root.appendingPathComponent(".staging-\(UUID().uuidString)")
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        let r = await Shell.runAsync("/usr/bin/tar", ["-xzf", tarball.path, "-C", staging.path], timeout: 300)
        guard r.ok else { throw err("Couldn't unpack Node.js: \(r.combined)") }
        let extracted = staging.appendingPathComponent(release.file.replacingOccurrences(of: ".tar.gz", with: ""))
        let final = root.appendingPathComponent(release.version)
        if fm.fileExists(atPath: final.path) { try fm.removeItem(at: final) }
        try fm.moveItem(at: extracted, to: final)

        let node = final.appendingPathComponent("bin/node").path
        let check = await Shell.runAsync(node, ["--version"], timeout: 15)
        guard check.ok, check.stdout == release.version else { throw err("Node.js was unpacked but doesn't start: \(check.combined)") }
        progress(.done(release.version))
        return node
    }

    static func err(_ message: String) -> NSError { NSError(domain: "Hypernova.Node", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}

/// A download with byte progress, saved to a temporary file we own.
final class Downloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private var continuation: CheckedContinuation<URL, Error>?
    private var onProgress: ((Int64, Int64) -> Void)?
    private var lastReport = Date.distantPast

    func download(_ url: URL, progress: @escaping (Int64, Int64) -> Void) async throws -> URL {
        onProgress = progress
        let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        return try await withCheckedThrowingContinuation { c in
            continuation = c
            session.downloadTask(with: url).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let now = Date()
        if now.timeIntervalSince(lastReport) > 0.1 || totalBytesWritten == totalBytesExpectedToWrite {
            lastReport = now
            onProgress?(totalBytesWritten, totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            continuation?.resume(throwing: NodeInstaller.err("nodejs.org answered \(status) for the download.")); continuation = nil; return
        }
        // The system deletes `location` when this method returns, so move it first.
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent("hypernova-\(UUID().uuidString).download")
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            continuation?.resume(returning: dest)
        } catch { continuation?.resume(throwing: error) }
        continuation = nil
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { continuation?.resume(throwing: error); continuation = nil }
    }
}
