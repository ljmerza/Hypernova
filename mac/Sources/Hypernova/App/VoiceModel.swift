import AppKit
import SwiftUI

/// Voice on this Mac, in one place: Soniox through the daemon (a key kept in <support>/stt.json),
/// or Hyperscribe Companion when it runs here. Setup's Voice step, the Lens guide, the popover's
/// notice and the phone's mic all depend on the same answer, so they all ask this model
/// (docs/specs/spec-voice.md, 4.2). The Mac never listens itself: nothing here records audio.
///
/// The live feed (via LensGuideModel) tells it when `stt` changes; `load()` reads both sources.
@MainActor
final class VoiceModel: ObservableObject {
    static let shared = VoiceModel()

    /// Where a panel is shown: the two lines that differ are worded for it.
    enum Context { case setup, guide }

    @Published private(set) var stt: SttState?
    @Published var keyDraft = ""
    @Published private(set) var saving = false
    @Published private(set) var testing = false
    /// The last word on the key (Connect, Test), in plain words; `ok` colours it.
    @Published private(set) var result: (text: String, ok: Bool)?
    /// "Remove key" was pressed and waits for Remove or Keep.
    @Published var confirmingRemove = false
    /// Hyperscribe Companion as an app on this Mac, if it's installed as one.
    @Published private(set) var companionApp: URL?
    /// Where the Companion answers when it runs here with its tailnet route
    /// (`GET /v1/speech/companion`): the phone and Lens stream straight to it.
    @Published private(set) var companionURL: String?
    /// The daemon has answered at least once, so `ready` is a fact and not just "not asked yet".
    /// The popover's notice waits for it, so it never flashes at an app that is still starting.
    @Published private(set) var loaded = false

    static let sonioxConsole = URL(string: "https://console.soniox.com")!
    /// Whether Setup and the Lens guide offer Hyperscribe Companion at all. Off until it has a public
    /// page to point at (owner, 2026-10-16); Soniox is the one offered route. The rest of the code
    /// path stays, so turning this on brings the link back.
    static let companionOffered = false

    /// The Companion's source and setup steps. It has no app to download yet: it runs from source.
    static let companionSource = URL(string: "https://github.com/prudnik-web/Hyperscribe-Companion#readme")!
    /// How a packaged Companion would be found: its bundle id, else its name in an Applications folder.
    private static let companionBundleID = "com.prudnikweb.hyperscribe-companion"
    private static let companionAppName = "Hyperscribe Companion.app"

    /// Offscreen renders: sample data, and the daemon is never asked.
    private var sample = false
    private var loadedAt: Date?

    /// Voice works: Soniox is ready, or the Companion runs here.
    var ready: Bool { stt?.ready == true || companionURL != nil }
    var companionReady: Bool { companionURL != nil }

    // MARK: Reading

    /// Both sources, at once.
    func load() async {
        guard !sample else { return }
        async let s: Void = loadStt()
        async let c: Void = loadCompanion()
        _ = await (s, c)
    }

    /// `load()` unless it ran in the last minute: for places that look often (the popover opening).
    func loadIfStale() {
        guard !sample, !SnapshotMode.active else { return }
        if let at = loadedAt, Date().timeIntervalSince(at) < 60 { return }
        Task { await load() }
    }

    func loadStt() async {
        guard !sample, let s = try? await DaemonClient.shared.request("GET", "/v1/stt", as: SttState.self) else { return }
        if s != stt { stt = s }
        loaded = true
        loadedAt = Date()
    }

    /// Whether the Companion is installed as an app, and whether it runs here for Lens and the phone.
    func loadCompanion() async {
        guard !sample else { return }
        let app = Self.findCompanion()
        if app != companionApp { companionApp = app }
        let url = (try? await DaemonClient.shared.request("GET", "/v1/speech/companion", as: CompanionEnvelope.self))?.url ?? nil
        if url != companionURL { companionURL = url }
    }

    /// The live feed says voice changed (`{t:"stt"}`).
    func heard(_ s: SttState) {
        guard !sample else { return }
        // The feed doesn't carry the key's last four characters; keep them until the read below.
        stt = SttState(provider: s.provider, ready: s.ready, error: s.error, last4: s.provider == nil ? nil : stt?.last4)
        loaded = true
        Task { await loadStt() }
    }

    private static func findCompanion() -> URL? {
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: companionBundleID) { return url }
        let home = FileManager.default.homeDirectoryForCurrentUser
        return [URL(fileURLWithPath: "/Applications"), home.appendingPathComponent("Applications")]
            .map { $0.appendingPathComponent(companionAppName) }
            .first { FileManager.default.fileExists(atPath: $0.path) }
    }

    /// Opens the installed Companion, or its setup page when there's no app.
    func openCompanion() {
        if let app = companionApp ?? Self.findCompanion() {
            NSWorkspace.shared.openApplication(at: app, configuration: NSWorkspace.OpenConfiguration())
        } else {
            NSWorkspace.shared.open(Self.companionSource)
        }
    }

    // MARK: Soniox

    /// Sends the key to the daemon, which tries it with Soniox before keeping it (<support>/stt.json, 0600).
    func connect(in context: Context) {
        let key = keyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !saving else { return }
        saving = true
        result = nil
        confirmingRemove = false
        Task {
            defer { saving = false }
            do {
                let s = try await DaemonClient.shared.request("POST", "/v1/stt/key", body: ["provider": "soniox", "apiKey": AnyEncodable(key)] as [String: AnyEncodable], timeout: 25, as: SttSaved.self)
                keyDraft = ""
                stt = SttState(provider: s.provider, ready: s.ready, error: s.error, last4: s.last4)
                loaded = true
                result = Self.savedWords(s, context)
            } catch {
                result = (error.localizedDescription, false)
            }
        }
    }

    /// What a saved key comes to, in the daemon's words: untried (Soniox out of reach), the
    /// account's problem, kept while Soniox answered something else (busy, its own problem), or ready.
    private static func savedWords(_ s: SttSaved, _ context: Context) -> (text: String, ok: Bool) {
        if s.checked == false { return ("Saved. Soniox couldn’t be reached to check it just now.", false) }
        if !s.ready { return (s.error ?? "Saved.", false) }
        if let p = s.problem, !p.isEmpty { return ("Saved. \(p)", false) }
        switch context {
        case .setup: return ("Connected. Your phone gets a mic in the composer.", true)
        case .guide: return ("Connected. Hold the temple and talk.", true)
        }
    }

    func test() {
        guard !testing else { return }
        testing = true
        result = nil
        Task {
            defer { testing = false }
            do {
                let r = try await DaemonClient.shared.request("POST", "/v1/stt/test", timeout: 25, as: SttTest.self)
                result = (r.message, r.ok)
                await loadStt()
            } catch {
                result = (error.localizedDescription, false)
            }
        }
    }

    /// Removes the key once "Remove" confirms it (the card asks first: voice stops until a new one).
    func remove() {
        confirmingRemove = false
        Task {
            do {
                let s = try await DaemonClient.shared.request("DELETE", "/v1/stt/key", as: SttState.self)
                stt = s
                result = nil
            } catch {
                result = (error.localizedDescription, false)
            }
        }
    }

    /// A step that opens fresh: the last key result and a half-open "Remove key?" are old news.
    func clearNotes() {
        result = nil
        confirmingRemove = false
    }

    // MARK: Offscreen renders

    /// For the renders: this voice state, without the daemon.
    func loadSample(stt: SttState?, keyDraft: String = "", result: (String, Bool)? = nil, confirmingRemove: Bool = false,
                    saving: Bool = false, companionApp: URL? = nil, companionURL: String? = nil) {
        sample = true
        self.stt = stt
        self.keyDraft = keyDraft
        self.result = result.map { (text: $0.0, ok: $0.1) }
        self.confirmingRemove = confirmingRemove
        self.saving = saving
        self.companionApp = companionApp
        self.companionURL = companionURL
        self.loaded = true
    }

    /// Back to a model that asks the daemon (after a render borrowed the shared one).
    func endSample() {
        sample = false
        stt = nil; keyDraft = ""; result = nil; confirmingRemove = false; saving = false
        companionApp = nil; companionURL = nil; loaded = false
    }

    private struct CompanionEnvelope: Decodable { var url: String? }
    private struct SttSaved: Decodable {
        var provider: String?; var ready: Bool; var error: String?; var last4: String?
        /// False when Soniox couldn't be reached to try the key (kept, untried).
        var checked: Bool?
        /// Soniox's words when it answered but the try didn't pass (busy, a problem on its side).
        var problem: String?
    }
    private struct SttTest: Decodable { var ok: Bool; var message: String }
}
