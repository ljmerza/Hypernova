import Foundation

// The popover's words, apart from its views so the self-tests can read them. They follow the
// Android app's (DECISIONS 2026-10-18, one design across phone, Mac and glasses): short phrases,
// never "·"-joined fields. Android's sources are named beside each, so the two stay in step.

/// Times as the phone says them: within the next 20 hours the time alone ("2:00"), later with the
/// day ("Fri 10:00"). Android: data/Notices.kt `AccountWords.clock`.
enum Clock {
    static func clock(_ d: Date, _ now: Date) -> String {
        (soon(d, now) ? time : dayTime).string(from: d)
    }
    /// "at 2:00", or "Fri 10:00" when it isn't soon: to follow "carries on" or "back".
    static func atClock(_ d: Date, _ now: Date) -> String { soon(d, now) ? "at " + clock(d, now) : clock(d, now) }
    /// "Tue 20:00" (or "Tue 8:00 PM" on a 12-hour Mac), whatever the distance.
    static func weekdayTime(_ d: Date) -> String { dayTime.string(from: d) }
    /// "Tuesday at 20:00", for VoiceOver.
    static func weekdayTimeSaid(_ d: Date) -> String { "\(dayName.string(from: d)) at \(time.string(from: d))" }
    static func soon(_ d: Date, _ now: Date) -> Bool { d.timeIntervalSince(now) <= 20 * 3600 }

    private static let time: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("j:mm"); return f }()
    private static let dayTime: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEE j:mm"); return f }()
    private static let dayName: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEEE"); return f }()
}

/// A model's name as its chip shows it: short, without the provider's prefix or "claude-".
/// Android: data/ModelNames.kt.
enum ModelNames {
    /// "opus" → "opus", "Opus 5.5" → "Opus 5.5", "openai/gpt-5" → "gpt-5",
    /// "anthropic/claude-sonnet-4.5" → "sonnet-4.5", "claude-opus-5-5" → "opus-5-5".
    static func chip(_ model: String) -> String {
        let trimmed = model.trimmingCharacters(in: .whitespaces)
        let name = trimmed.split(separator: "/").last.map(String.init) ?? trimmed
        let bare = name.lowercased().hasPrefix("claude-") && name.count > 7 ? String(name.dropFirst(7)) : name
        return bare.trimmingCharacters(in: .whitespaces).isEmpty ? trimmed : bare
    }

    /// Just the family, for a chip with little room: "Opus 5.5" → "Opus", "sonnet-4.5" → "sonnet".
    /// Nil when there's nothing shorter to say ("opus").
    static func family(_ model: String) -> String? {
        let name = chip(model)
        if let space = name.firstIndex(of: " "), space > name.startIndex { return String(name[..<space]) }
        if let r = name.range(of: #"-\d"#, options: .regularExpression), r.lowerBound > name.startIndex { return String(name[..<r.lowerBound]) }
        return nil
    }
}

/// The permission mode in words, beside its glyph. Android: ui/home/HomeScreen.kt `modeLabel`.
enum ModeWords {
    static func label(_ mode: String?) -> String? {
        switch mode {
        case nil: return nil
        case "default": return "ask first"
        case "acceptEdits": return "accept edits"
        case "plan": return "plan"
        case "auto": return "auto"
        case "bypassPermissions": return "bypass"
        case "dontAsk": return "allowed only"
        // A Codex session's sandbox (docs/PROTOCOL.md, "Codex"): it asks for anything more.
        case "read-only": return "read only"
        case "workspace-write": return "can edit"
        case let m?: return m
        }
    }
}

/// Codex in the popover's words (docs/PROTOCOL.md, "Codex"): what stops it, as a sentence under
/// USAGE (another agent's trouble is a sentence, as another provider's is), never an attention card.
enum CodexWords {
    /// Its trouble, and whether only you can fix it; nil when it's fine (or still connecting).
    static func trouble(_ c: Codex) -> (text: String, waitsOnYou: Bool, help: String)? {
        if c.tooOld {
            let need = c.min.map { "Hypernova needs Codex \($0) or later" } ?? "Hypernova needs a newer Codex"
            return ("Codex needs an update", true, "\(need)\(c.version.map { " (this one is \($0))" } ?? ""). Run codex update in a terminal.")
        }
        if let p = c.problem?.nonEmpty { return ("Can’t reach Codex", false, p + " Hypernova keeps trying.") }
        if c.account?.signedIn == false { return ("Codex is signed out", true, "Run codex login in a terminal; it shows here once you're signed in.") }
        return nil
    }

    /// "Codex reached its limit": one of its windows is used up, where no meter already says so at 100%.
    static func limited(_ c: Codex, meters: [MeterReading]) -> String? {
        guard c.usage?.limited == true, !meters.contains(where: \.atLimit) else { return nil }
        return "Codex reached its limit"
    }

    /// "Signed in with ChatGPT Plus", "Signed in with an API key": how Codex is signed in, never the
    /// email; nil while it isn't (or isn't known yet).
    static func account(_ a: Codex.Account?) -> String? {
        guard a?.signedIn == true else { return nil }
        switch a?.type {
        case "chatgpt"?:
            let plan = a?.planType?.nonEmpty.map { " " + planName($0) } ?? ""
            return "Signed in with ChatGPT\(plan)"
        case "apiKey"?: return "Signed in with an API key"
        default: return "Signed in"
        }
    }

    /// A ChatGPT plan as OpenAI names it ("prolite" is "Pro Lite"); one it doesn't know, capitalised.
    /// Android: ui/settings/Providers.kt `planWord`.
    static func planName(_ plan: String) -> String {
        switch plan.lowercased() {
        case "prolite": return "Pro Lite"
        case "pro": return "Pro"
        case "plus": return "Plus"
        case "go": return "Go"
        case "free": return "Free"
        case "team": return "Team"
        case "business": return "Business"
        case "enterprise": return "Enterprise"
        case "edu": return "Edu"
        default: return plan.prefix(1).uppercased() + plan.dropFirst()
        }
    }

    /// On an API key: no windows, every turn is paid for (the Agents window says so beside it).
    static func paysPerUse(_ c: Codex) -> Bool { c.account?.signedIn == true && c.account?.type == "apiKey" }

    /// What "Use Codex with Hypernova" does, under the switch. [claude]: Claude Code is here too (a
    /// conversation can switch between the two); [shell]: the shell's functions are in, so `codex` in
    /// a new terminal tab is shared.
    static func switchLine(claude: Bool, shell: Bool) -> String {
        switch (claude, shell) {
        case (true, true): return "Your phone can start Codex sessions, and codex in a new terminal tab is shared, like claude."
        case (true, false): return "Your phone can start Codex sessions and switch a conversation between the two."
        case (false, true): return "Your phone can start Codex sessions, and codex in a new terminal tab is shared with it."
        case (false, false): return "Your phone can start Codex sessions, answer what Codex asks and see its limits."
        }
    }
}

/// The agents in the popover's and the menu bar's words (docs/PROTOCOL.md, "Which agent leads").
/// Claude Code's own trouble names Claude Code, so with Codex beside it, it never reads as the app's.
enum AgentWords {
    /// What only you can fix about Claude Code's account, as the menu bar's tooltip says it.
    static func claudeTrouble(_ state: String) -> String? {
        switch state {
        case "logged_out": return "Claude Code is signed out"
        case "no_subscription": return "no Claude plan"
        case "on_hold": return "Claude Code’s account is on hold"
        default: return nil
        }
    }

    /// The Uninstall question's line: what goes back to how it was.
    static func uninstallLine(_ r: AgentRule) -> String {
        if r.both { return "The background service stops, and Claude Code and Codex go back to how they were." }
        if r.claude || r.codexInUse { return "The background service stops and \(r.claude ? "Claude Code" : "Codex") goes back to how it was." }
        return "The background service stops."
    }

    /// What Uninstall removes, naming only what this Mac has: Claude Code's hooks and status line tap
    /// where Claude Code is, the codex function where Codex was on.
    static func uninstallMore(_ r: AgentRule) -> String {
        let functions = r.claude && r.codexInUse ? "the claude and codex functions" : r.claude ? "the claude function" : r.codexInUse ? "the codex function" : "the shell functions"
        var removed = [String]()
        if r.claude { removed.append("its hooks and status line tap from Claude Code") }
        removed.append("\(functions) it added to ~/.zshrc")
        removed += ["this Mac's private name under hypernova.observer", "Backpack mode's permission", "and the background service with its login item"]
        let stays = r.both ? " Claude Code and Codex themselves stay as they are." : r.claude ? " Claude Code itself stays as it is." : r.codexInUse ? " Codex itself stays as it is." : ""
        return "Hypernova removes " + removed.joined(separator: ", ") + "." + stays
    }

    /// SESSIONS with nothing running: where a session comes from on this Mac. [shell]: the shell's
    /// functions are in (Codex in a terminal is shared only through its function).
    static func nothingRunning(_ r: AgentRule, shell: Bool) -> String {
        if !r.claude {
            guard r.codexInUse else { return "Nothing running." }
            return shell ? "Nothing running. Type codex in a terminal, or start a session from your phone." : "Nothing running. Start a Codex session from your phone."
        }
        if r.both, shell { return "Nothing running. Type claude or codex in a terminal, or start a session from your phone." }
        return "Nothing running. Type claude in a terminal, or start a session from your phone."
    }
}

/// A session as the popover's row says it. Android: data/LiveHub.kt `toSession`, data/Notices.kt
/// `AccountWords.activity`, data/Providers.kt `ProviderWords.activity`.
enum SessionWords {
    enum Tone: Equatable { case accent, amber, danger, muted, dim }
    struct Line: Equatable {
        var text: String
        var tone: Tone
        /// Stopped by a limit, the account or its provider: the line leads with the eclipse mark.
        var eclipse = false
        /// "Thinking" breathes.
        var thinking = false
    }

    /// The short id beside the age: the id's first two letters or digits ("c7").
    static func hex(_ id: String) -> String {
        let s = String(id.filter { $0.isLetter || $0.isNumber }.prefix(2)).lowercased()
        return s.isEmpty ? "··" : s
    }

    /// The project in caps; a session in the home folder is HOME, not the user's name.
    static func project(_ s: Session, home: String = Paths.home.path) -> String? {
        if let cwd = s.cwd, cwd.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == home.trimmingCharacters(in: CharacterSet(charactersIn: "/")) { return "Home" }
        guard let p = s.projectName, !p.isEmpty else { return nil }
        return p
    }

    /// The model its chip names: the session's own, else its provider's default, else "default model".
    static func model(_ s: Session, provider: Provider?) -> String {
        if let m = s.model?.trimmingCharacters(in: .whitespaces), !m.isEmpty { return ModelNames.chip(m) }
        if let p = provider, p.kind != "anthropic", let d = p.models?.default, !d.isEmpty { return ModelNames.chip(d) }
        return "default model"
    }

    /// "1 needs you", "2 working": the SESSIONS seam's marks, needs first; none means "all quiet".
    static func counts(needs: Int, working: Int) -> [(text: String, needs: Bool)] {
        var out: [(text: String, needs: Bool)] = []
        if needs > 0 { out.append((needs == 1 ? "1 needs you" : "\(needs) need you", true)) }
        if working > 0 { out.append(("\(working) working", false)) }
        return out
    }

    static func agents(_ n: Int) -> String { n == 1 ? "1 agent" : "\(n) agents" }

    /// The row's line under the title: what it waits on, what it does, or why Claude can't reply.
    static func line(_ s: Session, approval: Approval?, provider: Provider?, account: Account?, now: Date) -> Line {
        if let a = approval {
            let what = a.isTool ? (a.summary?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? a.detail : a.headline(codex: s.isCodex)
            return Line(text: what, tone: .amber)
        }
        switch s.state {
        case "needs_you":
            return Line(text: s.activity?.nonEmpty ?? "waiting for your answer", tone: .amber)
        case "starting":
            return Line(text: s.activity?.nonEmpty ?? "starting…", tone: .accent)
        case "working":
            let a = s.activity?.nonEmpty
            return Line(text: a ?? "working…", tone: .accent, thinking: a == "Thinking")
        case "error":
            return Line(text: "stopped on an error", tone: .danger)
        default:
            if let b = blocked(s, provider: provider, account: account, now: now) {
                return Line(text: b.text, tone: b.waitsOnYou ? .amber : .muted, eclipse: true)
            }
            if s.stoppedForAway == true { return Line(text: "paused after 10 minutes without an answer", tone: .amber) }
            if let last = readable(s.lastText) { return Line(text: last, tone: .muted) }
            return Line(text: "waiting for your next prompt", tone: .dim)
        }
    }

    /// Why Claude can't reply in it now, as a short phrase ("session limit until 2:00, then carries
    /// on", "OpenRouter is out of credits"), and whether only you can lift it. Only on an idle session
    /// (a block stops it, and a real reply clears it); a limit whose reset has passed no longer stops it.
    static func blocked(_ s: Session, provider: Provider?, account: Account?, now: Date) -> (text: String, waitsOnYou: Bool)? {
        guard s.state == "idle", let b = s.blocked, let kind = b.kind else { return nil }
        switch kind {
        case "limit", "model":
            if let r = b.resetsAt?.date, r <= now, b.resume?.armed != true, b.resume?.ready != true { return nil }
            let l = limitLabel(b, account: account).lowercased()
            if b.resume?.ready == true { return ("\(l) over, ready to continue", false) }
            if let at = b.autoResumeAt?.date, at > now { return ("\(l) until \(Clock.clock(at, now)), then carries on", false) }
            if let r = b.resetsAt?.date, r > now { return ("\(l) until \(Clock.clock(r, now))", false) }
            // A model this plan can't use, or one that needs usage credits: nothing to wait for.
            if kind == "model" { return ("this model isn’t available", true) }
            return ("paused at the \(l)", false)
        case "logged_out": return ("signed out on the Mac", true)
        case "no_subscription": return ("can’t reply without a Claude plan", true)
        case "on_hold": return ("account on hold", true)
        case "no_credit": return ("out of usage credits", true)
        case "provider":
            return ProviderWords.activity(b, provider: provider, now: now)
        default:
            return (b.text?.nonEmpty ?? "paused", false)
        }
    }

    /// The limit a session's block names: the account's when it's the same one, else Claude Code's
    /// own line ("hit your session limit"), else "Session limit" or "Model limit".
    static func limitLabel(_ b: Session.Blocked, account: Account?) -> String {
        if let own = account?.limit, (b.kind == "model") == (own.window == "model") { return LimitWords.label(own) }
        if let t = b.text, let r = t.range(of: #"hit your ([^·]+?)\s*(·|$)"#, options: .regularExpression) {
            let m = String(t[r]).replacingOccurrences(of: "hit your ", with: "").replacingOccurrences(of: "·", with: "").trimmingCharacters(in: .whitespaces)
            if !m.isEmpty { return m.prefix(1).uppercased() + m.dropFirst() }
        }
        return b.kind == "model" ? "Model limit" : "Session limit"
    }

    /// The first plain line of a reply, without markdown marks (they'd show literally in mono).
    static func readable(_ text: String?) -> String? {
        guard let text else { return nil }
        for raw in text.split(separator: "\n") {
            var l = raw.trimmingCharacters(in: .whitespaces)
            while let f = l.first, "#>*-".contains(f) { l.removeFirst(); l = l.trimmingCharacters(in: .whitespaces) }
            l = l.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "`", with: "")
            if !l.isEmpty { return l }
        }
        return nil
    }
}

/// The account's limits in words. Android: data/Notices.kt `AccountWords.label`.
enum LimitWords {
    /// "Session limit", "Weekly limit", "Opus limit": Claude Code's own words when it gave them.
    static func label(_ limit: Account.Limit?) -> String {
        if let own = limit?.label?.trimmingCharacters(in: .whitespaces), !own.isEmpty { return own.prefix(1).uppercased() + own.dropFirst() }
        switch limit?.window {
        case "seven_day": return "Weekly limit"
        case "model": return "Model limit"
        case "credits": return "Usage credit limit"
        default: return "Session limit"
        }
    }

    /// "until 14:00", or "until Fri 10:00" when that's more than most of a day away; nil once past.
    static func until(_ d: Date?, _ now: Date) -> String? {
        guard let d, d > now else { return nil }
        return "until " + Clock.clock(d, now)
    }
}

/// Other providers' trouble as sentences. Android: data/Providers.kt `ProviderWords`.
enum ProviderWords {
    /// "OpenRouter is out of credits", "… reached its budget", "… refused its key", "… is rate
    /// limited until 14:05", "… isn't answering", "… can't reply".
    static func sentence(_ name: String, _ st: ProviderStatus, now: Date) -> String {
        switch st.state {
        case "no_credit": return st.budget == true ? "\(name) reached its budget" : "\(name) is out of credits"
        case "bad_key": return "\(name) refused its key"
        case "limited":
            if let u = LimitWords.until(st.resetsAt?.date, now) { return "\(name) is rate limited \(u)" }
            return "\(name) is rate limited"
        case "down": return "\(name) isn’t answering"
        default: return "\(name) can’t reply"
        }
    }

    /// A session's line while its provider stops it: "OpenRouter is out of credits", "OpenRouter
    /// carries on by itself at 14:05", "OpenRouter is ready to continue".
    static func activity(_ b: Session.Blocked, provider: Provider?, now: Date) -> (text: String, waitsOnYou: Bool) {
        guard let p = provider else { return (b.text?.nonEmpty ?? "its provider can’t reply", true) }
        if let r = b.resume, r.armed, let at = r.at?.date, at > now { return ("\(p.name) carries on by itself at \(Clock.clock(at, now))", false) }
        if b.resume?.ready == true { return ("\(p.name) is ready to continue", false) }
        if let st = p.status { return (sentence(p.name, st, now: now), st.needsYou) }
        return ("\(p.name) can’t reply", true)
    }

    /// What's left on it as a figure for the banknote glyph: "$4.20 left", "$0.31 today", "37 of 50
    /// free today"; nil when nothing's known.
    static func figure(_ p: Provider) -> String? { p.balance?.words(under: p.status) }

    /// Right after a new key was saved: what the daemon's read of it said. "Key works. $4.20 left."
    static func verdict(_ p: Provider) -> (text: String, amber: Bool)? {
        if p.status?.state == "bad_key" || p.balance?.error == "bad_key" { return ("Key refused. Check it and paste it again.", true) }
        if let b = p.balance, b.error == nil { return (b.words.map { "Key works. \($0)." } ?? "Key works.", false) }
        if p.balance?.error == "unreachable" { return ("Saved. Couldn’t reach \(p.name) to check the key.", true) }
        return nil
    }
}

extension String {
    var nonEmpty: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
