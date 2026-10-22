import Foundation

/// One usage window as a projection meter (docs/DESIGN.md, "Mac menu bar and mini app"): how much is
/// used, where the current pace leads by the reset, and the one short tag beside the bar. Pure: the
/// popover's Meter draws it, and VoiceOver reads [sentence].
///
/// The pace is Usage.rate (%/min), which the daemon sends only while something works, so an idle
/// window has no projection. Projected = used + pace × minutes to the reset, from the reading the
/// pace was measured against (Usage.observedAt). When that reaches 100% first, the limit comes
/// before the reset: the daemon says when (Rate.fiveHourEtaMs, also from the reading), and the tag
/// counts down to it instead of to the reset.
struct MeterReading: Equatable {
    enum Tag: Equatable {
        /// The limit comes before the reset, this many seconds from now: "47m", in red.
        case limit(TimeInterval)
        /// The window resets this many seconds from now (0 once it has): dim, after the reset glyph.
        case reset(TimeInterval)
        /// A model's week resets at this day and time ("Tue 20:00"), more than about a day off.
        case resetOn(Date)
    }

    /// What VoiceOver calls the window: "5-hour window", "Fable week" (Android's words).
    var name: String
    /// The pace in %/min while something is spent (nil when idle).
    var pace: Double?
    /// When it resets.
    var resetAt: Date?
    /// The reset reads as a weekday and time more than about a day off (a model's week).
    var weekday = false
    /// Percent used (0 once the window has reset), or nil while not measured.
    var used: Double?
    /// 0…100: where the pace leads by the reset; nil while nothing is spent (no faint fill).
    var projected: Double?
    /// The limit would come before the reset: the faint fill runs to the end, which gets a red notch.
    var limitFirst = false
    var limitIn: TimeInterval?
    var resetIn: TimeInterval?

    /// [pace] in %/min and [etaMs] (time to 100% at that pace, nil when the reset comes first) are
    /// the window's from Usage.rate; [observedAt] is when [window]'s percentage was read.
    /// [limitAt]: when the daemon says the limit comes at this pace (absolute, so it holds still
    /// between readings and reads the same on every client); it wins over [etaMs].
    init(name: String, window: UsageWindow?, pace: Double? = nil, etaMs: Double? = nil, limitAt: Date? = nil, observedAt: Date? = nil, now: Date, weekday: Bool = false) {
        self.name = name
        self.weekday = weekday
        let reset = window?.resetsAt?.date
        let past = reset.map { $0 <= now } ?? false
        // Once a window has reset, the old percentage no longer holds.
        used = window.map { past ? 0 : max(0, $0.pct) }
        resetIn = reset.map { max(0, $0.timeIntervalSince(now)) }
        resetAt = past ? nil : reset
        guard let u = used, u < 100, !past, let p = pace, p >= Self.idlePerMin else { return }
        self.pace = p
        let base = min(observedAt ?? now, now)
        var eta = etaMs.map { $0 / 1000 }
        if let reset {
            let at = u + p * reset.timeIntervalSince(base) / 60
            // The daemon's word decides, but a pace that plainly reaches 100% first is never drawn
            // stopping short of the end without its notch.
            if eta == nil, at >= 100 { eta = (100 - u) / p * 60 }
            projected = eta == nil ? at : 100
        } else if eta != nil {
            projected = 100
        }
        if let limitAt, reset.map({ limitAt < $0 }) ?? true {
            limitFirst = true
            projected = 100
            limitIn = max(0, limitAt.timeIntervalSince(now))
        } else if let eta {
            limitFirst = true
            limitIn = max(0, base.addingTimeInterval(eta).timeIntervalSince(now))
        }
    }

    /// Under this the daemon counts the pace as standing still (daemon/src/usage-rate.ts).
    static let idlePerMin = 0.005

    var atLimit: Bool { (used ?? 0) >= 100 }

    /// A faint fill worth drawing: the pace leads somewhere past what's used.
    var paceAhead: Bool {
        guard let projected, let used else { return false }
        return projected - used >= 0.5
    }

    var tag: Tag? {
        if limitFirst, let limitIn { return .limit(limitIn) }
        if weekday, let at = resetAt, let r = resetIn, r > Self.dayIsh { return .resetOn(at) }
        return resetIn.map(Tag.reset)
    }

    /// Past this, a model's week reads as a weekday and time (Android: data/ModelWeeks.kt).
    static let dayIsh: TimeInterval = 20 * 3600

    /// One full sentence, as Android's TalkBack says it (ui/components/ProjectionMeter.kt
    /// `projectionSaid`), with the durations spoken: "5-hour window 62% used, spending 0.8% a
    /// minute: at this pace you reach the limit in about 47 minutes, before the reset in 2 hours 1
    /// minute." A reset shown as a day and time is said as one: "… resets on Tuesday at 20:00."
    var sentence: String {
        guard let used else { return "\(name) not measured yet." }
        let on: String? = { if case .resetOn(let d)? = tag { return Clock.weekdayTimeSaid(d) } else { return nil } }()
        let reset: String? = on.map { "on \($0)" } ?? resetIn.flatMap { $0 > 0 ? "in \(Self.spoken($0))" : nil }
        let pct = Int(min(100, max(0, used)).rounded())
        if used >= 100 { return reset.map { "\(name): limit reached, resets \($0)." } ?? "\(name): limit reached." }
        let rate = pace.map { $0 >= 0.1 ? String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0) : String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), $0) }
        if limitFirst, let limitIn, let rate, let reset {
            return "\(name) \(pct)% used, spending \(rate)% a minute: at this pace you reach the limit in about \(Self.spoken(limitIn)), before the reset \(reset)."
        }
        if let rate, let reset { return "\(name) \(pct)% used, spending \(rate)% a minute: at this pace it lasts until the reset \(reset)." }
        if let reset { return "\(name) \(pct)% used, resets \(reset)." }
        return "\(name) \(pct)% used."
    }

    /// The tag's time: "47m", "2h 1m", "3d 5h"; a minute at least, as the meter looks again only
    /// every 30 seconds.
    static func short(_ seconds: TimeInterval) -> String {
        Format.duration(max(60, (seconds / 60).rounded(.up) * 60))
    }

    /// "47 minutes", "2 hours 1 minute", "3 days 5 hours", "less than a minute".
    static func spoken(_ seconds: TimeInterval) -> String {
        let m = Int((seconds / 60).rounded())
        if m < 1 { return "less than a minute" }
        func n(_ v: Int, _ unit: String) -> String { "\(v) \(unit)\(v == 1 ? "" : "s")" }
        let d = m / 1440, h = (m % 1440) / 60, mm = m % 60
        if d > 0 { return h > 0 ? "\(n(d, "day")) \(n(h, "hour"))" : n(d, "day") }
        if h > 0 { return mm > 0 ? "\(n(h, "hour")) \(n(mm, "minute"))" : n(h, "hour") }
        return n(mm, "minute")
    }
}
