import Foundation

enum DaemonError: LocalizedError, Equatable {
    case notRunning
    case noToken
    case unauthorized
    case server(status: Int, code: String?, message: String)
    case decoding(String)
    /// A refusal that points at a part of the body (`field`, a path like `steps[2].command`) or carries a run (409 `not_asking`): workflows' editor and answers.
    case detail(status: Int, code: String?, message: String, field: String?, run: WFRun?)

    var errorDescription: String? {
        switch self {
        case .notRunning: return "The background service isn't running."
        case .noToken: return "Hypernova couldn't read its admin key. Restart the background service from Setup."
        case .unauthorized: return "The background service didn't accept Hypernova's admin key. Restart it from Setup."
        case .server(_, _, let message): return message
        case .detail(_, _, let message, _, _): return message
        case .decoding(let what): return "The background service sent something unexpected (\(what))."
        }
    }
}

/// Talks to the daemon: health on 47321, the admin API on 47322 (docs/PROTOCOL.md).
struct DaemonClient {
    static let shared = DaemonClient()

    private let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 6
        c.timeoutIntervalForResource = 60
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.connectionProxyDictionary = [:]
        return URLSession(configuration: c)
    }()

    // MARK: Health

    func health(timeout: TimeInterval = 1.5) async -> Health? {
        var req = URLRequest(url: Endpoints.health)
        req.timeoutInterval = timeout
        guard let (data, resp) = try? await session.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        guard let h = try? JSONDecoder().decode(Health.self, from: data), h.ok == true else { return nil }
        return h
    }

    /// Synchronous health check for the CLI self-test.
    func healthSync(timeout: TimeInterval = 1.5) -> Health? {
        final class Box: @unchecked Sendable { var value: Health? }
        let sem = DispatchSemaphore(value: 0), box = Box()
        Task.detached { box.value = await health(timeout: timeout); sem.signal() }
        _ = sem.wait(timeout: .now() + timeout + 1)
        return box.value
    }

    func waitForHealth(seconds: Double) async -> Health? {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if let h = await health() { return h }
            try? await Task.sleep(nanoseconds: 700_000_000)
        }
        return nil
    }

    // MARK: Admin API

    func request<T: Decodable>(_ method: String, _ path: String, body: Encodable? = nil, timeout: TimeInterval = 8, as: T.Type = T.self) async throws -> T {
        guard let token = Paths.readAdminToken() else {
            throw (await health()) == nil ? DaemonError.notRunning : DaemonError.noToken
        }
        var req = URLRequest(url: URL(string: path, relativeTo: Endpoints.local)!)
        req.httpMethod = method
        req.timeoutInterval = timeout
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(body)
        } else if method != "GET" && method != "DELETE" {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data("{}".utf8)
        }
        let data: Data, resp: URLResponse
        do { (data, resp) = try await session.data(for: req) } catch {
            let ns = error as NSError
            if ns.domain == NSURLErrorDomain, [NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost, NSURLErrorCannotFindHost].contains(ns.code) {
                throw DaemonError.notRunning
            }
            if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorTimedOut {
                throw DaemonError.server(status: 0, code: "timeout", message: "The background service took too long to answer.")
            }
            throw error
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw DaemonError.unauthorized }
        guard (200..<300).contains(status) else {
            let e = try? JSONDecoder().decode(APIErrorBody.self, from: data)
            let fallback = String(decoding: data.prefix(300), as: UTF8.self)
            if let e, e.error?.field != nil || e.run != nil || e.error?.run != nil {
                throw DaemonError.detail(status: status, code: e.error?.code, message: e.error?.message ?? "The background service answered \(status).", field: e.error?.field, run: e.run ?? e.error?.run)
            }
            throw DaemonError.server(status: status, code: e?.error?.code, message: e?.error?.message ?? (fallback.isEmpty ? "The background service answered \(status)." : fallback))
        }
        if T.self == EmptyResponse.self { return EmptyResponse() as! T }
        do { return try JSONDecoder().decode(T.self, from: data) } catch {
            throw DaemonError.decoding("\(method) \(path)")
        }
    }

    /// [qr]: a download or pairing code is on screen, so the daemon publishes the Mac's private name for it (names.ts).
    func status(fresh: Bool = false, qr: Bool = false) async throws -> LocalStatus {
        let query = [fresh ? "fresh=1" : nil, qr ? "qr=1" : nil].compactMap { $0 }.joined(separator: "&")
        return try await request("GET", "/local/status" + (query.isEmpty ? "" : "?" + query), timeout: fresh ? 15 : 6)
    }
    /// The private name under hypernova.observer on or off (off gives it up at once, and says whether that got through).
    struct NameSettingReply: Decodable { var nameRelease: NameRelease? }
    func setNameService(_ on: Bool) async throws -> NameRelease? { try await request("POST", "/local/settings", body: ["nameService": on], timeout: 30, as: NameSettingReply.self).nameRelease }
    /// Setup has shown what the private name is and Let's Encrypt's terms: the daemon makes nothing before.
    func setNameTermsShown() async throws { _ = try await request("POST", "/local/settings", body: ["nameTermsShown": true], as: EmptyResponse.self) }
    func snapshot() async throws -> Snapshot { try await request("GET", "/v1/snapshot") }
    /// Asks the daemon to read the usage now (it looks at Claude Code's /usage unless it did lately).
    struct UsageEnvelope: Decodable { var usage: Usage? }
    func refreshUsage() async throws -> Usage? { try await request("POST", "/v1/usage/refresh", timeout: 60, as: UsageEnvelope.self).usage }
    /// Hypernova's own Tailscale node on or off (the daemon's tsnet.ts, `builtInTailscale`).
    func setBuiltInTailscale(_ on: Bool) async throws { _ = try await request("POST", "/local/settings", body: ["builtInTailscale": on], timeout: 30, as: EmptyResponse.self) }
    /// Browse outside projects on or off (the daemon's files.ts, `browseOutsideProjects`): phones hear of it at once.
    func setBrowseOutsideProjects(_ on: Bool) async throws { _ = try await request("POST", "/local/settings", body: ["browseOutsideProjects": on], as: EmptyResponse.self) }
    /// Codex for Hypernova on or off (the daemon's codex/client.ts): it connects to Codex's app-server, and
    /// the shell's `codex` comes into shared terminals with it. Nothing is installed.
    func setCodex(enabled: Bool) async throws { _ = try await request("POST", "/local/settings", body: ["codex": enabled], timeout: 15, as: EmptyResponse.self) }
    struct CodexEnvelope: Decodable { var codex: Codex? }
    /// Codex's account and limits read now (`POST /v1/codex/refresh`; only while it's on).
    func refreshCodex() async throws -> Codex? { try await request("POST", "/v1/codex/refresh", timeout: 20, as: CodexEnvelope.self).codex }
    struct CodexModels: Decodable { var models: [Lenient<CodexModel>]? }
    /// Codex's models for a picker (the daemon reads them at most once an hour). 409 `codex_off`, 503 `codex_unavailable`.
    func codexModels() async throws -> [CodexModel] { (try await request("GET", "/v1/codex/models", timeout: 20, as: CodexModels.self).models ?? []).compactMap(\.value) }
    func devices() async throws -> [Device] { try await request("GET", "/local/devices", as: DeviceList.self).devices }
    func removeDevice(_ id: String) async throws { _ = try await request("DELETE", "/local/devices/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)", as: EmptyResponse.self) }
    /// [forName]: Setup's code, whose download link may use the Mac's private name (names.ts), so it counts for it; the menu bar's doesn't.
    func pairStart(forName: Bool = false) async throws -> PairStart { try await request("POST", forName ? "/local/pair/start?name=1" : "/local/pair/start") }
    /// The code the popover stopped showing: it stops pairing now, not at its expiry (POST /local/pair/cancel).
    func pairCancel(_ code: String) async throws { _ = try await request("POST", "/local/pair/cancel", body: ["code": code], as: EmptyResponse.self) }
    func tailscaleServe() async throws -> TailscaleStatus { try await request("POST", "/local/tailscale/serve", timeout: 45) }
    /// The built-in Tailscale node (the daemon's tsnet.ts): sign out.
    func tsnetLogout() async throws -> LocalStatus { try await request("POST", "/local/tsnet/logout") }
    func installIntegration() async throws { _ = try await request("POST", "/local/integration/install", timeout: 30, as: EmptyResponse.self) }
    func uninstallIntegration() async throws { _ = try await request("POST", "/local/integration/uninstall", timeout: 30, as: EmptyResponse.self) }
    /// Gives up this Mac's name under hypernova.observer (the daemon's names.ts): its DNS records go at once.
    struct NameRelease: Decodable { var released: Bool?; var name: String? }
    func releaseName() async throws -> NameRelease { try await request("POST", "/local/name/release", timeout: 30) }
    /// Answers an approval: "allow", "deny", "always" (allow and keep Claude Code's suggested rule)
    /// or "session" (that tool for the rest of the session). 409 means it was already answered.
    func answerApproval(_ id: String, decision: String) async throws {
        _ = try await request("POST", "/v1/approvals/\(Self.segment(id))", body: ["decision": decision], timeout: 10, as: EmptyResponse.self)
    }
    /// Tells a session stopped while you were away that you're back.
    func continueSession(_ id: String) async throws { _ = try await request("POST", "/v1/sessions/\(Self.segment(id))/continue", timeout: 10, as: EmptyResponse.self) }
    static func segment(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s }

    /// Signs Claude Code in again: the daemon runs `claude auth login` and opens it in a terminal
    /// window on this Mac (the browser opens from there). 409 `not_needed` when the account is fine.
    func startLogin(where place: String = "mac") async throws -> Account? {
        try await request("POST", "/v1/account/login", body: ["where": place], timeout: 20, as: AccountEnvelope.self).account
    }
    /// The code the sign-in page shows ("…#state"), typed into the sign-in for you (a sign-in started
    /// without a terminal window: `where: "phone"`, Setup's).
    func sendLoginCode(_ code: String) async throws {
        _ = try await request("POST", "/v1/account/login/code", body: ["code": code], timeout: 30, as: EmptyResponse.self)
    }

    struct Opened: Decodable { var app: String?; var already: Bool? }
    /// Open a shared session in a terminal window (or bring the one it's in to the front).
    func openSession(_ id: String) async throws -> Opened { try await request("POST", "/v1/sessions/\(Self.segment(id))/open", timeout: 15) }

    // MARK: Stay awake (docs/PROTOCOL.md)

    struct AwakeEnvelope: Decodable { var awake: Awake }
    struct AwakeChange: Encodable { var mode: String?; var minBattery: Int?; var extend: Bool? }
    func awake() async throws -> Awake { try await request("GET", "/v1/mac/awake", as: AwakeEnvelope.self).awake }
    /// Choose While working, Backpack or Off (409 `needs_setup` for Backpack before its setup), or
    /// the battery level the Mac is let go at.
    func setAwake(mode: Awake.Mode? = nil, minBattery: Int? = nil) async throws -> Awake {
        try await request("POST", "/v1/mac/awake", body: AwakeChange(mode: mode?.rawValue, minBattery: minBattery), timeout: 10, as: AwakeEnvelope.self).awake
    }

    /// Backpack's "+2 h", in its last 10 minutes (409 `too_early` before them, `not_backpack` outside it).
    func extendAwake() async throws -> Awake {
        try await request("POST", "/v1/mac/awake", body: AwakeChange(extend: true), timeout: 10, as: AwakeEnvelope.self).awake
    }

    func providers() async throws -> [Provider] { try await request("GET", "/v1/providers", as: ProviderList.self).providers }
    func deleteProvider(_ id: String) async throws { _ = try await request("DELETE", "/v1/providers/\(id)", as: EmptyResponse.self) }
}

/// For calls whose body we don't need.
struct EmptyResponse: Decodable {}
