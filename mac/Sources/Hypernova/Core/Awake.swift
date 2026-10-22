import Foundation

/// Stay awake (docs/PROTOCOL.md, "Stay awake"): `Snapshot.mac.awake`, `{t:"awake"}`. The daemon
/// decides and holds; the app shows it and chooses the mode.
struct Awake: Decodable, Equatable {
    enum Mode: String, CaseIterable { case working, backpack, off }

    var mode: Mode
    /// Keeping the Mac awake right now.
    var holding: Bool
    /// A closed lid won't put it to sleep now (macOS's SleepDisabled is on).
    var lidClosedOk: Bool
    /// Backpack's one-time setup is done (and this is a MacBook).
    var backpackReady: Bool
    /// When Backpack ends by itself.
    var until: Date?
    /// "battery", "hot", "time" (a guard), "setup" or "failed" (Backpack's switch); nil: nothing.
    var reason: String?
    /// When a guard lets the Mac sleep (it's counting down).
    var sleepAt: Date?
    /// The lid is closed; nil without a lid.
    var lidClosed: Bool?
    /// A MacBook: Backpack and the battery guard apply.
    var laptop: Bool
    /// The battery guard's level, in percent.
    var minBattery: Int
    /// Whose SleepDisabled is on: "backpack", or "other" (another app's, Amphetamine's say, which
    /// keeps the Mac awake whatever Hypernova does); nil when it's off.
    var sleepDisabledBy: String?
    /// The app macOS names as keeping the Mac awake, when it's another app's.
    var otherApp: String?
    /// Backpack ends within 10 minutes: "+2 h" is on offer.
    var extendable: Bool

    init(mode: Mode, holding: Bool, lidClosedOk: Bool = false, backpackReady: Bool = false, until: Date? = nil, reason: String? = nil,
         sleepAt: Date? = nil, lidClosed: Bool? = nil, laptop: Bool = true, minBattery: Int = 15,
         sleepDisabledBy: String? = nil, otherApp: String? = nil, extendable: Bool = false) {
        (self.mode, self.holding, self.lidClosedOk, self.backpackReady, self.until) = (mode, holding, lidClosedOk, backpackReady, until)
        (self.reason, self.sleepAt, self.lidClosed, self.laptop, self.minBattery) = (reason, sleepAt, lidClosed, laptop, minBattery)
        (self.sleepDisabledBy, self.otherApp, self.extendable) = (sleepDisabledBy, otherApp, extendable)
    }

    /// A mode it doesn't know (a newer daemon's) sinks the value, so nothing wrong is shown; the
    /// rest fall back one by one.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        guard let m = Mode(rawValue: try c.decode(String.self, forKey: .mode)) else {
            throw DecodingError.dataCorruptedError(forKey: .mode, in: c, debugDescription: "Unknown mode")
        }
        mode = m
        holding = (try? c.decodeIfPresent(Bool.self, forKey: .holding)) ?? false
        lidClosedOk = (try? c.decodeIfPresent(Bool.self, forKey: .lidClosedOk)) ?? false
        backpackReady = (try? c.decodeIfPresent(Bool.self, forKey: .backpackReady)) ?? false
        until = (try? c.decodeIfPresent(FlexDate.self, forKey: .until))?.date
        reason = try? c.decodeIfPresent(String.self, forKey: .reason)
        sleepAt = (try? c.decodeIfPresent(FlexDate.self, forKey: .sleepAt))?.date
        lidClosed = try? c.decodeIfPresent(Bool.self, forKey: .lidClosed)
        laptop = (try? c.decodeIfPresent(Bool.self, forKey: .laptop)) ?? true
        minBattery = (try? c.decodeIfPresent(Int.self, forKey: .minBattery)) ?? 15
        sleepDisabledBy = try? c.decodeIfPresent(String.self, forKey: .sleepDisabledBy)
        otherApp = try? c.decodeIfPresent(String.self, forKey: .otherApp)
        extendable = (try? c.decodeIfPresent(Bool.self, forKey: .extendable)) ?? false
    }
    private enum K: String, CodingKey {
        case mode, holding, lidClosedOk, backpackReady, until, reason, sleepAt, lidClosed, laptop, minBattery, sleepDisabledBy, otherApp, extendable
    }

    /// A guard is counting down to letting the Mac sleep.
    var sleepingSoon: Bool { sleepAt != nil }
    /// A guard has let it sleep (holding stopped for it).
    var letGo: Bool { !holding && sleepAt == nil && ["battery", "hot"].contains(reason ?? "") }
    /// Another app keeps the Mac awake (its SleepDisabled is on): Hypernova letting go won't put it to sleep.
    var keptByOther: Bool { sleepDisabledBy == "other" }
    /// That app's name, as macOS gives it.
    var other: String { otherApp ?? "another app" }
}

/// Stay awake in words, the same on every screen of the Mac app.
enum AwakeWords {
    static func title(_ m: Awake.Mode) -> String {
        switch m {
        case .working: return "While working"
        case .backpack: return "Backpack"
        case .off: return "Off"
        }
    }

    static func help(_ m: Awake.Mode) -> String {
        switch m {
        case .working: return "Awake while sessions, agents or automations run, or a phone or the glasses are connected; it sleeps as usual after."
        case .backpack: return "Awake with the lid closed, on battery, for up to 8 hours: Hypernova keeps working in your bag."
        case .off: return "Hypernova never keeps this Mac awake; it sleeps as macOS decides."
        }
    }

    /// "battery 15%", "it's hot", "8 hours are up".
    static func why(_ reason: String?, battery: Battery?) -> String? {
        switch reason {
        case "battery": return battery.map { "battery \($0.shown)%" } ?? "battery low"
        case "hot": return "it's running hot"
        case "time": return "8 hours are up"
        default: return nil
        }
    }

    /// The seam's trail as a phrase: "awake", "awake with the lid closed", "sleeps in 2 minutes",
    /// "off". Never "sleeps" while another app keeps the Mac awake: it won't.
    static func trail(_ a: Awake, now: Date) -> String {
        if let at = a.sleepAt { return (a.keptByOther ? "lets go in " : "sleeps in ") + soon(at.timeIntervalSince(now)) }
        if a.mode == .off { return "off" }
        if a.letGo { return a.keptByOther ? "kept awake by \(a.other)" : "letting it sleep" }
        // Backpack's end is the line under the picker's to say.
        if a.holding { return a.lidClosed == true ? "awake with the lid closed" : "awake" }
        return "sleeps when idle"
    }

    /// The line under the control, when there's something to say; nil otherwise.
    static func line(_ a: Awake, battery: Battery?, now: Date) -> (text: String, warn: Bool)? {
        if let at = a.sleepAt {
            let inTime = soon(at.timeIntervalSince(now))
            // Another app's SleepDisabled keeps the Mac awake after Hypernova lets go: say that, not sleep.
            if a.keptByOther {
                if a.reason == "time" { return ("Backpack ends in \(inTime), but \(a.other) is still keeping your Mac awake", true) }
                let why = why(a.reason, battery: battery).map { " (\($0))" } ?? ""
                return ("Hypernova lets go in \(inTime)\(why), but \(a.other) is still keeping your Mac awake", true)
            }
            // As the phone says it: "Your Mac will sleep in 2 minutes: battery 15%".
            let why = why(a.reason, battery: battery).map { ": \($0)" } ?? ""
            return ("Your Mac will sleep in \(inTime)\(why)", true)
        }
        if a.letGo, a.keptByOther {
            let at = a.reason == "battery" ? " at \(battery?.shown ?? a.minBattery)%" : ": it's running hot"
            return ("Hypernova let go\(at), but \(a.other) is still keeping your Mac awake", true)
        }
        if a.letGo, let why = why(a.reason, battery: battery) {
            return (a.reason == "battery" ? "Letting it sleep: \(why). Plug it in to keep it awake." : "Letting it sleep: \(why). It carries on once it cools down.", true)
        }
        if a.mode == .backpack {
            if a.reason == "setup" { return ("Backpack mode's permission is gone. Set it up again in Settings.", true) }
            if a.reason == "failed" { return ("macOS didn't take the lid-closed setting. Hypernova tries again in a moment.", true) }
            if a.holding, let u = a.until {
                return ("Awake with the lid closed until \(clock.string(from: u)). It sleeps at \(a.minBattery)% battery.", false)
            }
        }
        return nil
    }

    /// Backpack's last ten minutes, with "+2 h" beside it: "Backpack ends in 9 minutes". Nil outside them.
    static func ending(_ a: Awake, now: Date) -> String? {
        guard a.extendable, a.mode == .backpack, let until = a.until, until > now else { return nil }
        return "Backpack ends in \(soon(until.timeIntervalSince(now)))"
    }

    /// "2 minutes", "1 minute", "40 seconds".
    static func soon(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds.rounded()))
        if s >= 90 { return "\(Int((Double(s) / 60).rounded())) minutes" }
        if s >= 60 { return "1 minute" }
        return "\(s) seconds"
    }

    static let clock: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("j:mm")
        return f
    }()
}
