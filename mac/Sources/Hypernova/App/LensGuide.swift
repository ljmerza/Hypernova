import AppKit
import Combine
import SwiftUI

/// The "Hypernova Lens" window: what Lens is for, its gestures, and voice (Soniox, or Hyperscribe
/// Companion). It opens when glasses pair (the daemon says so on the live feed), when the Lens
/// phone page asks ("Show on my Mac"), from Settings, and once at launch for glasses that paired
/// while the app wasn't running. Its step is kept by the daemon, so the phone and this window move
/// together (docs/PROTOCOL.md, "The Lens guide").
///
/// It has no connection of its own: AppModel's live feed (Core/LiveFeed.swift) hands it the
/// guide's and voice's messages, so the Mac app keeps one WebSocket to the daemon.
@MainActor
final class LensGuideModel: ObservableObject {
    static let shared = LensGuideModel()

    enum Step: String, CaseIterable, Identifiable {
        case about, gestures, voice
        var id: String { rawValue }
        var rail: String {
            switch self {
            case .about: return "What it’s for"
            case .gestures: return "Gestures"
            case .voice: return "Voice"
            }
        }
    }

    @Published var step: Step = .about
    /// The steps this window shows, fixed when it opens: voice is left out when it is ready by then
    /// (set up in Setup, or the Companion is running), so the guide is two steps ending in Done. The
    /// phone asking for the voice step, or the "Voice ready" chip, brings it back.
    @Published private(set) var steps: [Step] = Step.allCases
    /// Voice (Soniox, or the Companion): the same model Setup's Voice step uses.
    let voice: VoiceModel
    private var voiceWatch: AnyCancellable?

    static let windowID = "lens-guide"
    init(voice: VoiceModel? = nil) {
        let voice = voice ?? VoiceModel.shared
        self.voice = voice
        // The header chip and the footer read the voice model, so its changes redraw this window.
        voiceWatch = voice.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }

    private var guide: GuideState?
    /// Set by start(): only the running app reacts to the feed (renders and self-tests of the
    /// popover never open this window).
    private var listening = false
    private var checkedMissed = false
    /// Offscreen renders: sample data, and the daemon is never asked.
    private var sample = false

    // MARK: Start

    /// From now on the live feed's guide and voice messages open and move this window. Called once
    /// at launch by the app (and by `--lens-guide-selftest`); never under `--snapshots`.
    func start() {
        guard !listening, !sample, !SnapshotMode.active else { return }
        listening = true
        if AppModel.shared.feedLive { feedUp() }
    }

    /// A guide or voice message from the live feed (AppModel routes them here).
    func heard(_ m: LiveFeed.Message) {
        guard listening else { return }
        switch m {
        case .guide(let g):
            apply(g)
        case .guideShow(let g, _):
            apply(g)
            show(at: Step(rawValue: g.step))
        case .stt(let s):
            voice.heard(s)
        default:
            break
        }
    }

    /// The feed is up (again): catch up on what may have changed, and open the guide for glasses
    /// that paired while this app wasn't running (once per launch, never if it was seen already).
    func feedUp() {
        guard listening else { return }
        Task {
            await loadGuide()
            await voice.load()
            guard !checkedMissed, let g = guide else { return }
            checkedMissed = true
            if !g.seen, !g.done, let devices = try? await DaemonClient.shared.devices(), devices.contains(where: \.isLens) { show() }
        }
    }

    private func apply(_ g: GuideState) {
        guide = g
        if let s = Step(rawValue: g.step), s != step {
            // The phone is on the voice step: show it here too, even if this window left it out.
            withAnimation(.hnExpo(0.5)) { include(s); step = s }
        }
        if g.done, WindowManager.shared.isOpen(Self.windowID) { WindowManager.shared.close(Self.windowID) }
    }

    func loadGuide() async {
        guard !sample, let r = try? await DaemonClient.shared.request("GET", "/v1/guide", as: GuideEnvelope.self) else { return }
        apply(r.guide)
    }

    // MARK: The window

    /// Opens the window (or brings it forward). Voice is looked at first, so the steps it opens
    /// with are right; that is one quick call to the Mac's own daemon, and none once it's known.
    /// `at`: the step the phone is on when it asked ("Show on my Mac"); it is shown even if left out.
    func show(at: Step? = nil) {
        Task {
            if !WindowManager.shared.isOpen(Self.windowID) {
                if !voice.loaded { await voice.load() }
                let skipVoice = voice.ready
                steps = skipVoice ? Step.allCases.filter { $0 != .voice } : Step.allCases
                voice.clearNotes()
                if !steps.contains(step) { step = .about }
                if let at { include(at); step = at }
            }
            WindowManager.shared.show(id: Self.windowID, title: "Hypernova Lens", size: NSSize(width: 780, height: 560)) {
                LensGuideView().environmentObject(self)
            }
            await voice.loadCompanion()
            if guide?.seen != true || guide?.done == true { await post(["seen": true, "done": false]) }
        }
    }

    /// Adds a step that was left out (voice) to the end, where it belongs.
    private func include(_ s: Step) {
        guard !steps.contains(s) else { return }
        steps = Step.allCases.filter { steps.contains($0) || $0 == s }
    }

    func go(_ s: Step) {
        guard s != step else { return }
        withAnimation(.hnExpo(0.5)) { include(s); step = s }
        voice.clearNotes()
        Task { await post(["step": AnyEncodable(s.rawValue)]) }
    }

    func next() {
        if let i = steps.firstIndex(of: step), i + 1 < steps.count { go(steps[i + 1]) } else { finish() }
    }

    func back() {
        if let i = steps.firstIndex(of: step), i > 0 { go(steps[i - 1]) }
    }

    var isLast: Bool { steps.last == step }

    /// Done: the window closes, and pairing more glasses won't open it again (Settings still can).
    func finish() {
        WindowManager.shared.close(Self.windowID)
        Task { await post(["done": true]) }
    }

    private func post(_ patch: [String: AnyEncodable]) async {
        guard !sample else { return }
        if let r = try? await DaemonClient.shared.request("POST", "/v1/guide", body: patch, as: GuideEnvelope.self) { guide = r.guide }
    }

    // MARK: Offscreen renders

    /// For `--render-lens-guide`: this step and step list, without the daemon (the voice model
    /// has its own `loadSample`).
    func loadSample(step: Step, steps: [Step] = Step.allCases) {
        sample = true
        self.step = step
        self.steps = steps
    }

    private struct GuideEnvelope: Decodable { var guide: GuideState }
}

/// A JSON body of mixed values ("step": "voice", "done": true).
struct AnyEncodable: Encodable, ExpressibleByStringLiteral, ExpressibleByBooleanLiteral {
    private let encodeValue: (Encoder) throws -> Void
    init<T: Encodable>(_ v: T) { encodeValue = v.encode }
    init(stringLiteral v: String) { self.init(v) }
    init(booleanLiteral v: Bool) { self.init(v) }
    func encode(to encoder: Encoder) throws { try encodeValue(encoder) }
}

extension Device {
    /// Hypernova Lens on Even glasses (it pairs with `client: "lens"`).
    var isLens: Bool { client == "lens" }
}
