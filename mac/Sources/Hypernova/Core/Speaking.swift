import Foundation
import SwiftUI

/// Read aloud, as the Mac app shows it (docs/PROTOCOL.md, "Read aloud"): which voice reads, where
/// voice is shown (VoicePanel), and a small wave on a session's row while its reply is being spoken,
/// on the phone or on the glasses through the phone. The daemon sends `{t:"speaking"}` to the Mac
/// app's feed alone, a few times a second while a voice speaks, with the loudness the phone hears in
/// what it plays: the wave moves with that, never with a loop.
@MainActor
final class Speaking: ObservableObject {
    static let shared = Speaking()

    /// The daemon's read-aloud setting (`speech`): chosen and in use ("soniox" or "mac"), the voice.
    struct Engine: Decodable, Equatable {
        var engine: String
        var using: String
        var voice: String?
        var macVoice: String?
        var note: String?

        /// "Adrian, from Soniox", "Samantha, the Mac’s voice", "the Mac’s voice".
        var line: String {
            if using == "soniox" { return voice.map { "\($0), from Soniox" } ?? "Soniox" }
            return macVoice.map { "\($0), the Mac’s voice" } ?? "the Mac’s voice"
        }
    }

    @Published private(set) var engine: Engine?
    /// Sessions being spoken now. Changes only when one starts or stops; the loudness doesn't publish.
    @Published private(set) var sessions: Set<String> = []
    /// The loudness last heard per session, read by the wave while it draws.
    private var levels: [String: (level: Double, at: Date)] = [:]
    private var sweeper: Task<Void, Never>?
    /// Renders (`--render-popover`): fixed loudness per session, drawn as a still wave.
    private(set) var sample: [String: Double]?

    func level(_ sessionId: String, now: Date = Date()) -> Double {
        if let s = sample?[sessionId] { return s }
        guard let l = levels[sessionId], now.timeIntervalSince(l.at) < 0.7 else { return 0 }
        return l.level
    }

    /// `{t:"speaking"}` and `{t:"speech"}` from the live feed, as text (LiveFeed hands them over).
    nonisolated static func handle(_ text: String) {
        guard let data = text.data(using: .utf8), let m = try? JSONDecoder().decode(Message.self, from: data) else { return }
        Task { @MainActor in shared.apply(m) }
    }

    private struct Message: Decodable {
        struct Now: Decodable { var sessionId: String?; var state: String?; var level: Double? }
        var t: String
        var speaking: Now?
        var speech: Engine?
    }

    private func apply(_ m: Message) {
        if m.t == "speech", let e = m.speech { if engine != e { engine = e }; return }
        guard m.t == "speaking", let now = m.speaking, let id = now.sessionId else { return }
        if ["ended", "stopped", "failed"].contains(now.state ?? "") {
            levels[id] = nil
            if sessions.contains(id) { sessions.remove(id) }
            return
        }
        levels[id] = (min(1, max(0, now.level ?? 0)), Date())
        if !sessions.contains(id) { sessions.insert(id) }
        sweep()
    }

    /// The feed gone quiet mid-play (the daemon restarted, the phone vanished): the wave goes after 4 s.
    private func sweep() {
        guard sweeper == nil else { return }
        sweeper = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                let stale = self.sessions.filter { id in self.levels[id].map { Date().timeIntervalSince($0.at) > 4 } ?? true }
                if !stale.isEmpty { self.sessions.subtract(stale) }
                if self.sessions.isEmpty { self.sweeper = nil; return }
            }
        }
    }

    /// The setting, asked for when voice is shown (the feed brings changes after that).
    func load() async {
        struct Envelope: Decodable { var speech: Engine? }
        if let e = (try? await DaemonClient.shared.request("GET", "/v1/speech/settings", as: Envelope.self))?.speech, engine != e { engine = e }
    }

    func loadSample(_ levels: [String: Double], engine: Engine? = nil) {
        sample = levels
        sessions = Set(levels.keys)
        if let engine { self.engine = engine }
    }

    func endSample() {
        sample = nil
        sessions = []
        engine = nil
    }
}

/// The speaking wave: five mint bars, the middle one the loudness now and each pair outward the same
/// a beat earlier, so a syllable rises in the middle and spreads out, like a pulsar's beat (the
/// phone draws the same). Silent, five dots. Reduce Motion: a still wave.
struct SpeakingWave: View {
    var sessionId: String
    var height: CGFloat = 12
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var wave = PulsarWave()

    var body: some View {
        let width = CGFloat(PulsarWave.bars) * 2 + CGFloat(PulsarWave.bars - 1) * 2
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion || Speaking.shared.sample != nil)) { tl in
            Canvas { ctx, size in
                let heights: [Double]
                if reduceMotion { heights = PulsarWave.still }
                else if let s = Speaking.shared.sample?[sessionId] { heights = PulsarWave.shape(s) }
                else { heights = wave.step(Speaking.shared.level(sessionId, now: tl.date), at: tl.date.timeIntervalSinceReferenceDate) }
                for (i, h) in heights.enumerated() {
                    let x = 1 + CGFloat(i) * 4
                    let len = (size.height - 2) * CGFloat(h)
                    var p = Path()
                    p.move(to: CGPoint(x: x, y: size.height / 2 - len / 2))
                    p.addLine(to: CGPoint(x: x, y: size.height / 2 + len / 2))
                    ctx.stroke(p, with: .color(.hnAccent), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                }
            }
        }
        .frame(width: width, height: height)
        .accessibilityLabel("speaking")
    }
}

/// The wave's motion, as the phone's (data/Speech.kt PulsarWave): a meter that rises in 40 ms and
/// falls in 220 ms, and a short history that moves outward a slot every 90 ms.
final class PulsarWave {
    static let bars = 5
    static let still: [Double] = [0.35, 0.7, 1, 0.7, 0.35]
    private var history = [Double](repeating: 0, count: 8)
    private var smoothed = 0.0
    private var sinceShift = 0.0
    private var last = Double.nan

    /// A still wave for a loudness (renders).
    static func shape(_ level: Double) -> [Double] { still.map { $0 * level } }

    func step(_ level: Double, at t: Double) -> [Double] {
        let dt = last.isNaN ? 0 : min(200, max(0, (t - last) * 1000))
        last = t
        let target = min(1, max(0, level))
        smoothed += (target - smoothed) * (1 - exp(-dt / (target > smoothed ? 40 : 220)))
        sinceShift += dt
        while sinceShift >= 90 {
            sinceShift -= 90
            history.removeLast()
            history.insert(0, at: 0)
        }
        history[0] = smoothed
        return (0..<Self.bars).map { i in
            let d = abs(i - Self.bars / 2)
            return min(1, max(0, history[min(d, history.count - 1)] * (1 - 0.16 * Double(d))))
        }
    }
}
