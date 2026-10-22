import Foundation

// Automations as the Mac sees them (docs/specs/spec-automations.md §3 and §7; docs/PROTOCOL.md,
// "Automations"). The Mac only shows one line and a pause switch, so it reads a little of what the
// daemon sends: `/local/status.automations` and `Snapshot.automations`. Both decode leniently, so
// a field a newer daemon changes never sinks the status or snapshot it rides in.

/// The session an automation started, on `Session.automation`.
struct AutomationRef: Decodable, Equatable, Hashable {
    var id: String?
    var name: String?
    var runId: String?
}

/// What the popover needs about automations, whichever message it came from.
struct AutomationsState: Equatable {
    struct Next: Equatable, Hashable { var id: String?; var name: String; var at: Date }
    struct Running: Equatable, Hashable {
        var id: String?; var name: String; var sessionId: String?; var startedAt: Date?
        /// Its run waits on you (a question, an approval): said in amber, not "running".
        var needsYou = false
    }
    var paused = false
    /// All automations, on or off.
    var count = 0
    /// The ones that can fire (not turned off). nil when the source didn't say.
    var enabled: Int?
    /// Soonest first; one entry from `/local/status`, up to three from the snapshot.
    var upcoming: [Next] = []
    var running: [Running] = []
    /// A named automation that fires on an event rather than a time, for the line when nothing is scheduled.
    var eventNames: [String] = []
    /// What the daemon can do of workflows (docs/PROTOCOL.md, "Workflows"); nil from an older daemon, which the app treats as today's.
    var features: [String]?
    /// Runs waiting at an ask step: attention items.
    var asks: [WFAskItem] = []
    var workflows: Bool { features != nil }
}

/// `/local/status`: `automations: {paused, count, next: {id, name, at} | null, running: [{id, name, sessionId, startedAt}]}`.
struct AutomationsStatus: Decodable, Equatable {
    var state: AutomationsState

    init(from decoder: Decoder) throws {
        var s = AutomationsState()
        if let c = try? decoder.container(keyedBy: K.self) {
            s.paused = ((try? c.decodeIfPresent(Bool.self, forKey: .paused)) ?? nil) ?? false
            s.count = ((try? c.decodeIfPresent(Int.self, forKey: .count)) ?? nil) ?? 0
            if let n = (try? c.decodeIfPresent(Next.self, forKey: .next)) ?? nil, let at = n.at?.date {
                s.upcoming = [.init(id: n.id, name: n.name ?? "Automation", at: at)]
            }
            let runs = ((try? c.decodeIfPresent([Lenient<Run>].self, forKey: .running)) ?? nil) ?? []
            s.running = runs.compactMap(\.value).map { .init(id: $0.id, name: $0.name ?? "Automation", sessionId: $0.sessionId, startedAt: $0.startedAt?.date) }
        }
        state = s
    }
    init(_ s: AutomationsState) { state = s }

    private struct Next: Decodable { var id: String?; var name: String?; var at: FlexDate? }
    private struct Run: Decodable { var id: String?; var name: String?; var sessionId: String?; var startedAt: FlexDate? }
    private enum K: String, CodingKey { case paused, count, next, running }
}

/// `Snapshot.automations`: `{paused, list: Automation[], running, queued, notes, glasses}`. Only the
/// parts the line uses are read: each automation's name, whether it's on, when it fires next, and its
/// last run while it works.
struct AutomationsSnapshot: Decodable, Equatable {
    var state: AutomationsState

    init(from decoder: Decoder) throws {
        var s = AutomationsState()
        if let c = try? decoder.container(keyedBy: K.self) {
            s.paused = ((try? c.decodeIfPresent(Bool.self, forKey: .paused)) ?? nil) ?? false
            let list = (((try? c.decodeIfPresent([Lenient<Item>].self, forKey: .list)) ?? nil) ?? []).compactMap(\.value)
            s.count = list.count
            s.enabled = list.filter { $0.enabled != false }.count
            s.upcoming = list.compactMap { a in
                guard a.enabled != false, let at = a.nextAt?.date else { return nil }
                return .init(id: a.id, name: a.name ?? "Automation", at: at)
            }.sorted { $0.at < $1.at }.prefix(3).map { $0 }
            s.running = list.compactMap { a in
                guard let r = a.lastRun, ["running", "needs_you"].contains(r.state ?? "") else { return nil }
                return .init(id: a.id, name: a.name ?? "Automation", sessionId: r.sessionIds?.last, startedAt: r.startedAt?.date, needsYou: r.state == "needs_you")
            }
            s.eventNames = list.filter { $0.enabled != false && $0.nextAt == nil }.compactMap(\.name)
            s.features = (try? c.decodeIfPresent([String].self, forKey: .features)) ?? nil
            s.asks = list.compactMap { a in
                guard let r = a.lastRun, r.state == "needs_you", let ask = r.ask, let runId = r.id, let until = ask.until?.date else { return nil }
                return WFAskItem(runId: runId, automationId: a.id, name: a.name ?? "Automation", stepId: ask.stepId ?? "", question: ask.question ?? "", answers: ask.answers ?? [], default: ask.default ?? "", until: until)
            }.filter { !$0.answers.isEmpty && !$0.stepId.isEmpty }
        }
        state = s
    }
    init(_ s: AutomationsState) { state = s }

    private struct Item: Decodable {
        var id: String?; var name: String?; var enabled: Bool?; var nextAt: FlexDate?; var lastRun: LastRun?
    }
    private struct LastRun: Decodable { var id: String?; var state: String?; var sessionIds: [String]?; var startedAt: FlexDate?; var ask: Ask? }
    private struct Ask: Decodable { var stepId: String?; var question: String?; var answers: [String]?; var `default`: String?; var until: FlexDate? }
    private enum K: String, CodingKey { case paused, list, features }
}

/// The words of the one line, apart from the view so a test can read them: the automation's name,
/// then a figure with its glyph ("◷ 9:00", "⏱ 3m"), never "Next 9:00 · Morning brief".
enum AutomationWords {
    enum Kind: Equatable { case paused, running, next, ready }
    struct Line: Equatable {
        var kind: Kind
        /// "Morning brief", "2 automations running", "Automations paused".
        var text: String
        /// The time it runs next (a clock) or how long it has run (a stopwatch).
        var figure: (glyph: Glyph, text: String)? = nil
        /// A word after it: "ready".
        var tag: String? = nil
        /// Its run waits on you: an amber NEEDS YOU, not mint "running".
        var needsYou = false
        /// The hover: the next three, or what runs.
        var help: String
        /// "Resume", only while paused.
        var action: String? = nil

        static func == (a: Line, b: Line) -> Bool {
            a.kind == b.kind && a.text == b.text && a.figure?.text == b.figure?.text && a.tag == b.tag && a.needsYou == b.needsYou && a.help == b.help && a.action == b.action
        }
        /// The whole line in words, for VoiceOver and the tests.
        var said: String {
            [text, figure.map { ($0.glyph == .timer ? "running " : "next at ") + $0.text }, tag, needsYou ? "needs you" : nil].compactMap { $0 }.joined(separator: ", ")
        }
    }

    /// nil when there's nothing to say: no automations, or every one is off. [needsYou]: a run
    /// whose session waits on you (the status doesn't say; the sessions do).
    static func line(_ s: AutomationsState, now: Date, needsYou: (AutomationsState.Running) -> Bool = { $0.needsYou }) -> Line? {
        guard s.count > 0 || s.paused else { return nil }
        if s.paused { return Line(kind: .paused, text: "Automations paused", help: "Nothing starts by itself until you resume. Runs already working carry on.", action: "Resume") }
        if let first = s.running.first {
            let waiting = s.running.contains(where: needsYou)
            if s.running.count > 1 {
                return Line(kind: .running, text: "\(s.running.count) automations running", needsYou: waiting, help: s.running.map(\.name).joined(separator: ", "))
            }
            let since = first.startedAt.map { (glyph: Glyph.timer, text: Format.short(max(60, now.timeIntervalSince($0)))) }
            return Line(kind: .running, text: first.name, figure: since, needsYou: waiting, help: waiting ? "It waits on you. Click to open its terminal." : "Click to open its terminal.")
        }
        if s.enabled == 0 { return nil }
        if let n = s.upcoming.first {
            let more = s.upcoming.prefix(3).map { "\($0.name) at \(clock($0.at, now))" }.joined(separator: "\n")
            return Line(kind: .next, text: n.name, figure: (glyph: .clock, text: clock(n.at, now)), help: s.upcoming.count > 1 ? "Next up:\n" + more : "Edit automations on your phone.")
        }
        // Only event-driven ones: they wait for their moment.
        let names = s.eventNames
        if names.count == 1 { return Line(kind: .ready, text: names[0], tag: "ready", help: "It runs when its moment comes.") }
        return Line(kind: .ready, text: "\(max(s.enabled ?? s.count, 1)) automations ready", help: names.isEmpty ? "They run when their moment comes." : names.joined(separator: "\n"))
    }

    /// Settings' trail, as the phone's Automations tab says it: "3 on, next 9:00", "3 on", "paused".
    static func trail(_ s: AutomationsState, now: Date) -> String {
        if s.paused { return "paused" }
        if s.count == 0 { return "none yet" }
        let on = s.enabled ?? s.count
        if let n = s.upcoming.first { return "\(on) on, next \(clock(n.at, now))" }
        return "\(on) on"
    }

    /// "9:00" today, "Mon 9:00" after.
    static func clock(_ d: Date, _ now: Date) -> String {
        (Calendar.current.isDate(d, inSameDayAs: now) ? time : dayTime).string(from: d)
    }
    private static let time: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("j:mm"); return f }()
    private static let dayTime: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEE j:mm"); return f }()
}
