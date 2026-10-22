import Foundation

/// The daemon's live feed: the phone API's WebSocket (`/v1/ws?client=mac`, docs/PROTOCOL.md), opened
/// with the admin key. The daemon pushes each change as it happens, so the menu bar learns at once
/// that a session started working, asks for you or stopped, without asking the daemon over and over.
/// It's the app's only feed: the Lens guide's messages (the guide moved or is asked for, voice
/// changed) come on it too, and `client=mac` lets the daemon count it as the Mac app's, so "Show on
/// my Mac" on the phone knows the app is there to hear it.
///
/// It costs next to nothing while nothing happens (a ping every 25 s). Transcript events, the bulk
/// of the traffic while Claude works, are skipped unread; what the menu bar shows arrives decoded,
/// on the main actor. It reconnects by itself (after 1, 2, 4 … 30 s) and drops a connection that
/// has gone quiet for 70 s. `quietFor` says how long the daemon has been silent on it, so the app
/// can tell a busy daemon (still pinging) from a hung one (its socket open, but nothing said).
final class LiveFeed: @unchecked Sendable {
    enum Message: Equatable {
        case snapshot(Snapshot)
        case session(Session)
        case approval(Approval)
        case resolved(id: String)
        case usage(Usage)
        case account(Account)
        /// The Mac's battery changed; nil: it has none (or the daemon's word can't be read).
        case battery(Battery?)
        /// Stay awake changed: the mode, holding or not, the lid, a guard counting down.
        case awake(Awake)
        /// The Lens guide moved, on the phone or here (App/LensGuide.swift).
        case guide(GuideState)
        /// Bring the guide window forward: glasses just paired ("paired"), or "Show on my Mac" on
        /// the phone ("phone").
        case guideShow(GuideState, reason: String)
        /// Voice through Soniox became ready or not.
        case stt(SttState)
        /// A phone or the glasses paired (the message says nothing more).
        case devices
        /// A provider's trouble or balance changed (ProviderModels.swift).
        case providers([Provider])
        /// Codex changed: on or off, connected, its account or its windows; nil: not installed (any more).
        case codex(Codex?)
    }
    enum Event: Equatable {
        /// Connected and hearing from the daemon, or not any more.
        case live(Bool)
        case message(Message)
    }

    private let deliver: @MainActor (Event) -> Void
    private let lock = NSLock()
    private var runner: Task<Void, Never>?
    /// Which run is current. A run that was replaced may still be closing its connection; what it
    /// says then (that it went down) is about a connection nobody uses any more.
    private var epoch = 0
    /// When the current run's connection last carried anything; nil while it has none up.
    private var heardAt: Date?

    /// Seconds since the daemon last said anything on the feed (it pings every 25 s, so a healthy
    /// one is never quiet for much longer); nil while the feed isn't connected.
    var quietFor: TimeInterval? {
        lock.lock(); defer { lock.unlock() }
        return heardAt.map { Date().timeIntervalSince($0) }
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        // Longer than the daemon's 25 s ping, so a quiet but healthy connection never times out.
        c.timeoutIntervalForRequest = 90
        c.timeoutIntervalForResource = 365 * 86_400
        c.connectionProxyDictionary = [:]
        return URLSession(configuration: c)
    }()

    init(deliver: @escaping @MainActor (Event) -> Void) { self.deliver = deliver }

    /// Connects (again, at once: the service just came back, say), and keeps reconnecting.
    func start() {
        lock.lock(); defer { lock.unlock() }
        runner?.cancel()
        epoch += 1
        heardAt = nil
        let mine = epoch
        runner = Task.detached { [weak self] in await self?.run(mine) }
    }

    private func isCurrent(_ e: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return e == epoch
    }

    /// Notes that the daemon just spoke on this run's connection (`true`), or that the connection
    /// is gone (`false`). A replaced run's word doesn't count.
    private func noteHeard(_ yes: Bool, _ run: Int) {
        lock.lock(); defer { lock.unlock() }
        if run == epoch { heardAt = yes ? Date() : nil }
    }

    /// Hands an event to the app, unless this run has been replaced meanwhile.
    private func send(_ e: Event, _ run: Int) async {
        await MainActor.run {
            if isCurrent(run) { deliver(e) }
        }
    }

    private func run(_ run: Int) async {
        // A new run isn't connected yet, whatever the run it replaced was.
        await send(.live(false), run)
        var delay: Double = 1
        while !Task.isCancelled {
            if await connectOnce(run) { delay = 1 }
            if Task.isCancelled { return }
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            delay = min(delay * 2, 30)
        }
    }

    /// One connection, until it closes. True when the daemon was heard on it.
    private func connectOnce(_ run: Int) async -> Bool {
        guard let token = Paths.readAdminToken(), let url = URL(string: "ws://127.0.0.1:\(Endpoints.apiPort)/v1/ws?client=mac") else { return false }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let ws = Self.session.webSocketTask(with: req)
        // A snapshot with many sessions is larger than the 1 MB default.
        ws.maximumMessageSize = 64 << 20
        ws.resume()
        let lastFrame = Clock()
        // The daemon pings every 25 s, so 70 s of silence means it hung or the link is gone.
        let watchdog = Task.detached {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                if lastFrame.age > 70 { ws.cancel(with: .goingAway, reason: nil); return }
            }
        }
        let heard = await withTaskCancellationHandler {
            var heard = false
            do {
                while !Task.isCancelled {
                    let frame = try await ws.receive()
                    lastFrame.touch()
                    noteHeard(true, run)
                    guard case .string(let text) = frame else { continue }
                    if !heard {
                        heard = true
                        await send(.live(true), run)
                    }
                    if text.hasPrefix(#"{"t":"ping""#) {
                        ws.send(.string(#"{"t":"pong"}"#)) { _ in }
                        continue
                    }
                    // Telescope asks for a window of the app a session builds (Core/WindowCapture.swift).
                    if text.hasPrefix(#"{"t":"telescope.window""#) {
                        WindowCapture.handle(text)
                        continue
                    }
                    // Read aloud: the speaking wave on a session's row, and which voice reads (Core/Speaking.swift).
                    if text.hasPrefix(#"{"t":"speaking""#) || text.hasPrefix(#"{"t":"speech""#) {
                        Speaking.handle(text)
                        continue
                    }
                    if let m = Self.decode(text) { await send(.message(m), run) }
                }
            } catch {}
            return heard
        } onCancel: {
            ws.cancel(with: .goingAway, reason: nil)
        }
        watchdog.cancel()
        ws.cancel(with: .normalClosure, reason: nil)
        noteHeard(false, run)
        if heard { await send(.live(false), run) }
        return heard
    }

    /// What the app needs from one message, or nil for the rest. The daemon writes `t` first, so
    /// the busiest kinds are recognised without parsing them: what Claude writes (`event`), and
    /// agents and workflows at work (their session's count comes with the session).
    static func decode(_ text: String) -> Message? {
        for kind in ["event", "agent", "workflow", "projects"] where text.hasPrefix(#"{"t":"\#(kind)""#) {
            return nil
        }
        guard let data = text.data(using: .utf8), let env = try? JSONDecoder().decode(Envelope.self, from: data) else { return nil }
        return env.message
    }

    private struct Envelope: Decodable {
        var message: Message?

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: K.self)
            switch try c.decode(String.self, forKey: .t) {
            case "snapshot": message = (try? c.decode(Snapshot.self, forKey: .snapshot)).map(Message.snapshot)
            case "session": message = (try? c.decode(Session.self, forKey: .session)).map(Message.session)
            case "approval": message = (try? c.decode(Approval.self, forKey: .approval)).map(Message.approval)
            case "approval.resolved": message = (try? c.decode(String.self, forKey: .id)).map { Message.resolved(id: $0) }
            case "usage": message = (try? c.decode(Usage.self, forKey: .usage)).map(Message.usage)
            case "account": message = (try? c.decode(Account.self, forKey: .account)).map(Message.account)
            // Without the field it says nothing; one it can't read hides the battery rather than
            // leave an old percentage on screen.
            case "battery": message = c.contains(.battery) ? .battery(try? c.decodeIfPresent(Battery.self, forKey: .battery)) : nil
            case "awake": message = (try? c.decode(Awake.self, forKey: .awake)).map(Message.awake)
            case "guide": message = (try? c.decode(GuideState.self, forKey: .guide)).map(Message.guide)
            case "guide.show":
                message = (try? c.decode(GuideState.self, forKey: .guide)).map { .guideShow($0, reason: (try? c.decode(String.self, forKey: .reason)) ?? "phone") }
            case "stt": message = (try? c.decode(SttState.self, forKey: .stt)).map(Message.stt)
            case "devices": message = .devices
            case "providers": message = (try? c.decode(FlexList<Provider>.self, forKey: .providers)).map { .providers($0.items) }
            // Without the field it says nothing; null is Codex gone from this Mac.
            case "codex": message = c.contains(.codex) ? .codex(try? c.decodeIfPresent(Codex.self, forKey: .codex)) : nil
            default: message = nil
            }
        }
        private enum K: String, CodingKey { case t, snapshot, session, approval, id, usage, account, battery, awake, guide, reason, stt, providers, codex }
    }

    /// When the connection last carried anything, shared with its watchdog.
    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var at = Date()
        func touch() { lock.lock(); at = Date(); lock.unlock() }
        var age: TimeInterval { lock.lock(); defer { lock.unlock() }; return Date().timeIntervalSince(at) }
    }
}
