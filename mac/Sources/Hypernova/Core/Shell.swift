import Foundation

struct ShellResult {
    var status: Int32
    var stdout: String
    var stderr: String
    var timedOut = false
    var ok: Bool { status == 0 && !timedOut }
    var combined: String { [stdout, stderr].filter { !$0.isEmpty }.joined(separator: "\n") }
}

enum Shell {
    /// The PATH GUI apps should use when they shell out (Finder gives apps a minimal one).
    static var basePath: String {
        ["\(Paths.localBin.path)", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"].joined(separator: ":")
    }

    static var baseEnvironment: [String: String] {
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = basePath + (env["PATH"].map { ":" + $0 } ?? "")
        env["HOME"] = Paths.home.path
        if env["LANG"] == nil { env["LANG"] = "en_US.UTF-8" }
        return env
    }

    /// Runs a program to completion, collecting output. Blocking: call it off the main thread.
    static func run(_ exe: String, _ args: [String] = [], timeout: TimeInterval = 15, env: [String: String]? = nil, cwd: URL? = nil) -> ShellResult {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: exe)
        p.arguments = args
        p.environment = env ?? baseEnvironment
        if let cwd { p.currentDirectoryURL = cwd }
        let out = Pipe(), err = Pipe()
        p.standardOutput = out
        p.standardError = err
        p.standardInput = FileHandle.nullDevice
        final class Buffers: @unchecked Sendable {
            let lock = NSLock()
            var out = Data(), err = Data()
            func set(_ d: Data, err isErr: Bool) { lock.lock(); if isErr { err = d } else { out = d }; lock.unlock() }
            func get() -> (Data, Data) { lock.lock(); defer { lock.unlock() }; return (out, err) }
        }
        let buffers = Buffers()
        let group = DispatchGroup()
        do { try p.run() } catch {
            return ShellResult(status: -1, stdout: "", stderr: error.localizedDescription)
        }
        group.enter(); DispatchQueue.global().async { buffers.set(out.fileHandleForReading.readDataToEndOfFile(), err: false); group.leave() }
        group.enter(); DispatchQueue.global().async { buffers.set(err.fileHandleForReading.readDataToEndOfFile(), err: true); group.leave() }
        let done = DispatchSemaphore(value: 0)
        p.terminationHandler = { _ in done.signal() }
        var timedOut = false
        if done.wait(timeout: .now() + timeout) == .timedOut {
            timedOut = true
            p.terminate()
            if done.wait(timeout: .now() + 2) == .timedOut { kill(p.processIdentifier, SIGKILL); _ = done.wait(timeout: .now() + 1) }
        }
        _ = group.wait(timeout: .now() + 2)
        let (outData, errData) = buffers.get()
        return ShellResult(status: p.terminationStatus, stdout: String(decoding: outData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines),
                           stderr: String(decoding: errData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines), timedOut: timedOut)
    }

    static func runAsync(_ exe: String, _ args: [String] = [], timeout: TimeInterval = 15, env: [String: String]? = nil) async -> ShellResult {
        await Task.detached(priority: .userInitiated) { run(exe, args, timeout: timeout, env: env) }.value
    }

    /// `zsh -lc '<script>'`, for finding things the user's login shell knows about.
    static func loginShell(_ script: String, timeout: TimeInterval = 8) -> ShellResult {
        run("/bin/zsh", ["-lc", script], timeout: timeout)
    }

    static func stripANSI(_ s: String) -> String {
        s.replacingOccurrences(of: "\u{1B}\\[[0-9;?]*[ -/]*[@-~]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\u{1B}\\][^\u{07}]*\u{07}", with: "", options: .regularExpression)
    }
}

/// A process whose output streams line by line (the Claude Code installer's live log).
/// `onExit` runs exactly once: after the process exits and its output has ended, or at most
/// 2 s after exit if something it started keeps the pipe open.
final class StreamingProcess: @unchecked Sendable {
    private let process = Process()
    private var buffer = Data()
    private let queue = DispatchQueue(label: "hypernova.stream")
    private var outputEnded = false
    private var exitStatus: Int32?
    private var finished = false
    private var onLine: (@Sendable (String) -> Void)?
    private var onExit: (@Sendable (Int32) -> Void)?

    func start(_ exe: String, _ args: [String], env: [String: String]? = nil, onLine: @escaping @Sendable (String) -> Void, onExit: @escaping @Sendable (Int32) -> Void) {
        self.onLine = onLine
        self.onExit = onExit
        process.executableURL = URL(fileURLWithPath: exe)
        process.arguments = args
        process.environment = env ?? Shell.baseEnvironment
        process.standardInput = FileHandle.nullDevice
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] h in
            let d = h.availableData
            guard let self else { return }
            if d.isEmpty { h.readabilityHandler = nil }
            self.queue.async {
                if d.isEmpty { self.outputEnded = true; self.finishIfDone() }
                else { self.buffer.append(d); self.flush(final: false) }
            }
        }
        process.terminationHandler = { [weak self] p in
            guard let self else { return }
            self.queue.async {
                self.exitStatus = p.terminationStatus
                self.finishIfDone()
            }
            // A child may keep the pipe open; don't wait for it forever.
            self.queue.asyncAfter(deadline: .now() + 2) {
                pipe.fileHandleForReading.readabilityHandler = nil
                self.outputEnded = true
                self.finishIfDone()
            }
        }
        do { try process.run() } catch {
            queue.async {
                onLine("Couldn't start: \(error.localizedDescription)")
                self.finished = true
                onExit(-1)
            }
        }
    }

    /// On `queue` only.
    private func finishIfDone() {
        guard !finished, outputEnded, let status = exitStatus else { return }
        finished = true
        flush(final: true)
        onExit?(status)
    }

    /// On `queue` only.
    private func flush(final: Bool) {
        guard let onLine else { return }
        // Progress bars redraw with \r; treat it as a line break so the log stays readable.
        while let i = buffer.firstIndex(where: { $0 == 0x0A || $0 == 0x0D }) {
            let line = buffer[buffer.startIndex..<i]
            buffer.removeSubrange(buffer.startIndex...i)
            let s = Shell.stripANSI(String(decoding: line, as: UTF8.self)).trimmingCharacters(in: .whitespaces)
            if !s.isEmpty { onLine(s) }
        }
        if final, !buffer.isEmpty {
            let s = Shell.stripANSI(String(decoding: buffer, as: UTF8.self)).trimmingCharacters(in: .whitespacesAndNewlines)
            buffer.removeAll()
            if !s.isEmpty { onLine(s) }
        }
    }

    func cancel() { if process.isRunning { process.terminate() } }
}
