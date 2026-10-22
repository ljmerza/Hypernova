import AppKit
import SwiftUI

// The popover's pieces: notices, approval cards, session rows, action tiles.

// MARK: - Pills and notices

/// A pill sized for the popover (30pt), optionally filling its share of a row.
struct PopoverPill: View {
    var title: String
    var kind: PillKind = .outline
    var busy = false
    var fill = true
    var height: CGFloat = 30
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                if busy { PlanetMeter(activity: .live, size: 10).colorMultiply(kind == .primary ? .hnBg : .hnInk) }
                Text(title).font(HNFont.sans(12, .semibold)).lineLimit(1)
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: fill ? .infinity : nil)
            .frame(height: height)
            .contentShape(Capsule())
        }
        .buttonStyle(PillStyle(kind: kind))
        .disabled(busy)
    }
}

// MARK: - Approvals

/// A tool call waiting for you, as the phone's ApprovalBlock: the family's amber band (NEEDS YOU,
/// the time left before the auto-deny, the tool), where it comes from, Claude Code's reason, the
/// exact command, then Deny / Always / Allow (and "all this session" for reads and web fetches).
struct ApprovalCard: View {
    @EnvironmentObject private var model: AppModel
    var approval: Approval
    var session: Session?

    var body: some View {
        let a = approval
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        VStack(alignment: .leading, spacing: 0) {
            NeedsYouBand(tool: a.tool, expires: a.expiresAt?.date)
            VStack(alignment: .leading, spacing: 10) {
                if let s = session { ApprovalSource(session: s) }
                if let r = a.reason?.trimmingCharacters(in: .whitespacesAndNewlines), !r.isEmpty {
                    Text(r).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(3).fixedSize(horizontal: false, vertical: true)
                }
                CommandWell(prompt: a.tool == "Bash" ? "$" : "›", text: a.detail)
                HStack(spacing: 8) {
                    PopoverPill(title: "Deny", kind: .danger) { model.answer(a, "deny") }
                    if a.canAlways == true {
                        PopoverPill(title: "Always", kind: .outline) { model.answer(a, "always") }
                            .help("Allow, and let Claude Code remember it for this kind of command")
                    }
                    PopoverPill(title: "Allow", kind: .primary) { model.answer(a, "allow") }
                }
                if a.canSession == true {
                    PopoverPill(title: "Allow all \(a.sessionScope) this session", kind: .ghost, height: 28) { model.answer(a, "session") }
                        .help("This one, the others waiting in this session, and the rest of the session")
                }
            }
            .padding(14)
        }
        .background(ZStack {
            shape.fill(Color.hnBg950.opacity(0.9))
            shape.fill(Color.hnAmber.opacity(0.05))
        })
        .clipShape(shape)
        // The ring goes over the band, so the card keeps one clean edge all round.
        .overlay(shape.strokeBorder(Color.hnAmber.opacity(0.45), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Needs you: \(a.tool), \(a.detail)")
    }
}

/// Hypernavigate's filled block, the family's mark for "this is waiting on you": an amber band
/// in background-coloured caps, NEEDS YOU, the minutes left before the auto-deny, and the tool.
private struct NeedsYouBand: View {
    var tool: String
    var expires: Date?

    var body: some View {
        HStack(spacing: 10) {
            Text("Needs you").font(HNFont.sans(10, .bold)).tracking(1.8).textCase(.uppercase).foregroundStyle(Color.hnBg).lineLimit(1)
            if let expires {
                TimelineView(.periodic(from: .now, by: 15)) { tl in
                    let left = expires.timeIntervalSince(tl.date)
                    if left > 0 {
                        Text(left < 60 ? "auto-deny in under 1m" : "auto-deny in \(Int(left / 60))m")
                            .font(HNFont.mono(10, left < 120 ? .semibold : .regular))
                            .foregroundStyle(Color.hnBg.opacity(left < 120 ? 0.95 : 0.7)).lineLimit(1)
                            .help("Unanswered approvals are denied after 10 minutes")
                    }
                }
            }
            Spacer(minLength: 6)
            Text(tool).font(HNFont.sans(10, .bold)).tracking(1.8).textCase(.uppercase).foregroundStyle(Color.hnBg.opacity(0.7)).lineLimit(1)
        }
        .padding(.horizontal, 14).padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hnAmber)
        .accessibilityElement(children: .combine)
    }
}

/// Where a request comes from: the project in tracked caps, then the session's title.
private struct ApprovalSource: View {
    var session: Session

    var body: some View {
        // The project in tracked caps, then the title a gap apart (no "·" between them).
        HStack(spacing: 12) {
            if let p = SessionWords.project(session) {
                Text(p).font(HNFont.label).tracking(1.2).textCase(.uppercase).foregroundStyle(Color.hnDim).lineLimit(1).layoutPriority(1)
            }
            if session.displayTitle != session.projectName {
                Text(session.displayTitle).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(1)
            }
        }
    }
}

/// A question or a plan: one line here; the answer takes the phone or the terminal.
struct AskCard: View {
    @EnvironmentObject private var model: AppModel
    var approval: Approval
    var session: Session?
    var dismiss: () -> Void

    var body: some View {
        let a = approval
        let shared = session?.term != nil
        // Started from the phone without a shared terminal (the Agent SDK fallback): there's no
        // terminal anywhere, only the phone.
        let sdk = !shared && session?.origin == "hypernova"
        let phones = (model.status?.devices?.count ?? 0) > 0
        VStack(alignment: .leading, spacing: 9) {
            ApprovalHead(label: a.kind == "plan" ? "Plan" : "Question", approval: a, session: session)
            Text(a.headline(codex: session?.isCodex == true)).font(HNFont.small).foregroundStyle(Color.hnInk).lineLimit(2).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                if let s = session, shared {
                    PopoverPill(title: "Open terminal", kind: .outline, fill: false) { model.openInTerminal(s, then: dismiss) }
                }
                Text(shared ? (phones ? "or answer on your phone" : "It's waiting there.")
                     : sdk ? "Answer it on your phone."
                     : (phones ? "Answer it on your phone, or in its terminal." : "It's waiting in its terminal."))
                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .background(NeedsYouCardBackground())
        .accessibilityElement(children: .contain)
    }
}

/// QUESTION  PROJECT … time left: a question's or a plan's head (a tool's has the band).
private struct ApprovalHead: View {
    var label: String
    var approval: Approval
    var session: Session?

    var body: some View {
        HStack(spacing: 12) {
            Text(label).hnLabel(.hnAmber).lineLimit(1)
            if let s = session, let p = SessionWords.project(s) {
                Text(p).font(HNFont.label).tracking(1.2).textCase(.uppercase).foregroundStyle(Color.hnDim).lineLimit(1)
            }
            Spacer(minLength: 6)
            if let exp = approval.expiresAt?.date { ExpiryText(expires: exp) }
        }
    }
}

/// "8m left": unanswered approvals are denied after 10 minutes.
private struct ExpiryText: View {
    var expires: Date
    var body: some View {
        TimelineView(.periodic(from: .now, by: 15)) { tl in
            let left = expires.timeIntervalSince(tl.date)
            Text(left <= 60 ? "under 1m left" : "\(Int(left / 60))m left")
                .font(HNFont.mono(10))
                .foregroundStyle(left < 120 ? Color.hnAmber : Color.hnDim)
                .help("Unanswered approvals are denied after 10 minutes")
        }
    }
}

/// A question's or a plan's card: softer than a tool's, which has the band.
private struct NeedsYouCardBackground: View {
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        ZStack {
            shape.fill(Color.hnBg950.opacity(0.9))
            shape.fill(Color.hnAmber.opacity(0.03))
            shape.strokeBorder(Color.hnAmber.opacity(0.3), lineWidth: 1)
        }
    }
}

/// The command it wants to run, in a well: `$` in amber, the rest in ink.
struct CommandWell: View {
    var prompt: String
    var text: String
    var body: some View {
        (Text(prompt + " ").foregroundColor(.hnAmber) + Text(text).foregroundColor(.hnInk))
            .font(HNFont.mono(11.5)).lineSpacing(2).lineLimit(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.hnBg.opacity(0.9)))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.hnLine, lineWidth: 1))
            .help(text)
    }
}

/// An approval answered here, folded to one line for a moment before it goes.
struct FoldedApproval: View {
    @EnvironmentObject private var model: AppModel
    var answered: AppModel.Answered

    var body: some View {
        let a = answered.approval
        let denied = answered.decision == "deny"
        let label: String = {
            if !a.isTool { return denied ? (a.kind == "plan" ? "Kept planning" : "Skipped") : (a.kind == "plan" ? "Plan approved" : "Answered") }
            switch answered.decision {
            case "always": return "Always allowed"
            case "session": return "Allowed for this session"
            case "deny": return "Denied"
            default: return "Allowed"
            }
        }()
        HStack(spacing: 8) {
            if denied { CrossIcon(size: 10) } else { CheckIcon(size: 11) }
            Text(label).font(HNFont.sans(12, .semibold)).foregroundStyle(denied ? Color.hnDanger : Color.hnAccent).fixedSize()
            Text(a.isTool ? a.detail : a.headline(codex: model.session(a.sessionId)?.isCodex == true)).font(HNFont.mono(11)).foregroundStyle(Color.hnDim).lineLimit(1).truncationMode(.middle)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).frame(height: 34)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.hnBg950.opacity(0.6)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.hnLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Account

/// Claude Code's account as an attention card, only when something's wrong with it: "Session limit
/// until 2:00" with its eclipse (it lifts by itself), or amber when only you can fix it ("Signed out"
/// with Sign in). Sign in opens `claude auth login` in a terminal window here, through the daemon.
/// With Codex in use beside it, its trouble names Claude Code ("Claude Code is signed out"), so it
/// never reads as the whole app's. Android: data/Notices.kt (`headline`, the account card).
struct AccountLine: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var dismissed = DismissedNotices.shared
    var content: Content

    struct Content: Equatable {
        enum Act: Equatable { case signIn, open(URL), setup }
        var title: String
        /// The one line under the title.
        var detail: String? = nil
        /// Amber: it waits on you. Otherwise a limit that lifts by itself.
        var amber = true
        var action: String? = nil
        var act: Act? = nil
        var busy = false
        var help: String
        /// What it's about now: a card swiped away comes back when this changes.
        var key: String = ""
    }

    /// What to say about this account at `now`, or nil when there's nothing to say. [named]: Codex is
    /// in use too, so a title that would read as the app's names Claude Code.
    static func content(_ a: Account, now: Date, signingIn: Bool, named: Bool = false) -> Content? {
        let key = [a.state, a.limit?.window, a.limit?.resetsAt.map { "\($0.date.timeIntervalSince1970)" }, a.login?.state].compactMap { $0 }.joined(separator: "/")
        if let login = a.login, a.state != "ok" {
            switch login.state {
            case "waiting_code":
                if login.where == "phone", SetupModel.shared.signingInHere {
                    return Content(title: "Signing in, in Setup", detail: "Paste the code from the sign-in page there.", action: "Open setup", act: .setup,
                                   help: "A sign-in started in Setup is waiting for the code its page shows.", key: key)
                }
                if login.where == "phone" {
                    return Content(title: "Signing in on your phone", detail: "It’s waiting for the code there.", help: "A sign-in started on your phone is waiting for its code there.", key: key)
                }
                return Content(title: "Signing in", detail: "Finish in your browser.", action: login.url == nil ? nil : "Open page",
                               act: login.url.flatMap(URL.init(string:)).map(Content.Act.open),
                               help: "Approve Claude Code on the page in your browser. If it shows a code, paste it into the terminal window that opened.", key: key)
            case "failed":
                return Content(title: "Sign-in didn’t finish", detail: "Claude Code is still signed out.", action: "Try again", act: .signIn, busy: signingIn,
                               help: login.error ?? "Claude Code didn't finish signing in.", key: key)
            default:
                return Content(title: "Signing in…", busy: true, help: "Claude Code is signing in. Its terminal window shows how it's going.", key: key)
            }
        }
        let said = a.text.map { $0 + "\n\n" } ?? ""
        switch a.state {
        case "logged_out":
            return Content(title: named ? "Claude Code is signed out" : "Signed out",
                           detail: named ? "Its sessions can’t reply. Codex keeps working." : "Sessions on your subscription can’t reply.", action: "Sign in", act: .signIn, busy: signingIn,
                           help: said + "Claude Code is signed out on this Mac, so sessions on your subscription can't reply. Sign in opens a terminal window and your browser.", key: key)
        case "no_subscription":
            let title = switch a.reason {
            case "ended", "trial_ended": "Claude plan ended"
            case "downgraded", "org_disabled": "Plan doesn’t cover Claude Code"
            case "rejected": "Claude plan may have ended"
            default: "No Claude plan"
            }
            return Content(title: title, detail: "Renew Pro or Max on claude.ai, then sign in again.", action: "Sign in", act: .signIn, busy: signingIn,
                           help: said + "Renew Pro or Max on claude.ai, then sign in again. Sessions on another provider\(named ? ", and Codex's," : "") keep working.", key: key)
        case "on_hold":
            return Content(title: named ? "Claude Code’s account is on hold" : "Account on hold", detail: "Anthropic has put this account on hold.", action: "Details", act: URL(string: "https://claude.ai/restricted").map(Content.Act.open),
                           help: said + "Anthropic has put this account on hold. The page explains why and how to appeal.", key: key)
        case "no_credit":
            let title = switch a.reason {
            case "spend_cap": "Spending limit reached"
            case "api_credit": "Credit balance too low"
            default: "Out of usage credits"
            }
            let until = LimitWords.until(a.limit?.resetsAt?.date, now)
            return Content(title: title, detail: until.map { "Claude Code can’t reply \($0)." } ?? "Claude Code can’t reply until you add credits.",
                           help: said + "Claude Code can't reply until the limit resets or you add credits.", key: key)
        case "limited":
            let reset = a.limit?.resetsAt?.date
            // Past its reset it's lifting: say nothing rather than something stale.
            if let reset, reset <= now { return nil }
            let label = LimitWords.label(a.limit)
            if a.limit?.usingCredits == true {
                return Content(title: "\(label) reached", detail: "Claude Code is on usage credits.", amber: false, help: said + "Claude Code carries on on usage credits.", key: key)
            }
            var help = said
            let resume = a.limit?.autoResumeAt?.date
            if let resume, resume > now { help += "Claude Code carries on by itself \(Clock.atClock(resume, now))." }
            else if let reset { help += "It lifts \(Clock.atClock(reset, now))." }
            let title = LimitWords.until(reset, now).map { "\(label) \($0)" } ?? label
            let line = resume.map { $0 > now } == true ? (named ? "Claude Code’s sessions carry on by themselves then." : "Sessions carry on by themselves then.") : "Claude Code can reply again then."
            return Content(title: title, detail: reset == nil ? "Claude Code can’t reply for now." : line, amber: false, help: help.trimmingCharacters(in: .whitespacesAndNewlines), key: key)
        default:
            return nil
        }
    }

    var body: some View {
        let n = notice
        if dismissed.shows(n) {
            AttentionCard(notice: n, onDismiss: content.busy ? nil : { dismissed.dismiss(n) })
        }
    }

    private var notice: Notice {
        Notice(id: "account", tone: content.amber ? .amber : .accent, title: content.title, line: content.detail, action: content.busy ? nil : content.action,
               busy: content.busy, help: content.help, eclipse: !content.amber, key: content.key, run: perform)
    }

    private func perform() {
        switch content.act {
        case .signIn: model.signIn()
        case .open(let url): NSWorkspace.shared.open(url)
        case .setup: WindowManager.shared.showSetup(startAt: .claude)
        case nil: break
        }
    }
}

// MARK: - Sessions

/// One session, as the phone's card says it, in three quiet lines: its planet (spinning while it
/// works or its agents do, amber when it needs you), the title with its stamp ("c7  ◷ 1m"); what
/// it's doing, waits on, or why Claude can't reply (the eclipse mark, then Android's words); and
/// the project with its tags on the left, the model chip and the mode tag on the right. Clicking a
/// shared one opens its terminal; a phone glyph means a phone has it open.
struct SessionRow: View {
    @EnvironmentObject private var model: AppModel
    var session: Session
    var dismiss: () -> Void
    @State private var hover = false
    /// Its reply being read aloud, on the phone or the glasses: the speaking wave (Core/Speaking.swift).
    @ObservedObject private var speaking = Speaking.shared

    /// A shared terminal to open, or a Codex session (the daemon starts `codex resume` in one for it).
    private var canOpen: Bool { session.term != nil || session.isCodex }
    /// Wears the CODEX tag: only while Codex is in use beside Claude Code (a Mac with it off looks as
    /// before; on one with Codex alone, every session is Codex's, so the tag would say nothing).
    private var codexTag: Bool { session.isCodex && model.codexOn && model.agents.both }
    private var shownInWindow: Bool { (session.term?.mac ?? 0) > 0 }
    private var needs: Bool { model.needsYou(session) }
    private var activity: Activity {
        if needs { return .alert }
        return session.isBusy ? .live : .rest
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { tl in
            row(now: tl.date)
        }
        .padding(.horizontal, 8).padding(.vertical, 7)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.hnInk.opacity(hover && canOpen ? 0.055 : 0)))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture { open() }
        .animation(.hnExpo(0.15), value: hover)
        .help(help)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(canOpen ? .isButton : [])
        .accessibilityAction { open() }
        // Combining the row hides its links from VoiceOver; offer them as named actions.
        .accessibilityActions {
            ForEach(actions, id: \.title) { a in Button(a.title, action: a.run) }
        }
    }

    private func row(now: Date) -> some View {
        let line = SessionWords.line(session, approval: model.approval(for: session), provider: otherProvider, account: model.account, now: now)
        return HStack(alignment: .top, spacing: 10) {
            PlanetMeter(activity: activity).padding(.top, 3)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(session.displayTitle).font(HNFont.sans(13)).foregroundStyle(Color.hnInk).lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 6)
                    if speaking.sessions.contains(session.id) {
                        SpeakingWave(sessionId: session.id).help("Its reply is being read aloud").transition(.opacity)
                    }
                    if session.openOnPhone {
                        GlyphView(glyph: .phone, size: 10, color: Color.hnAccent.opacity(0.8))
                            .help("Open on a phone right now")
                            .accessibilityLabel("open on a phone")
                    }
                    if hover && canOpen {
                        Text(shownInWindow ? "Show" : "Open").font(HNFont.label).tracking(1.2).textCase(.uppercase).foregroundStyle(Color.hnAccent)
                    } else if let d = session.lastActivityAt?.date {
                        SessionStamp(hex: SessionWords.hex(session.id), age: Format.short(now.timeIntervalSince(d)))
                            .accessibilityLabel("Session \(SessionWords.hex(session.id)), active \(Format.ago(d, now: now))")
                    }
                }
                activityLine(line)
                metaLine
            }
        }
    }

    /// What it's doing now, waits on, or why Claude can't reply, in mono; the links for a block.
    private func activityLine(_ line: SessionWords.Line) -> some View {
        HStack(alignment: .center, spacing: 6) {
            if line.eclipse { EclipseMark(color: color(line.tone), size: 10) }
            if line.thinking {
                ThinkingLabel(text: "Thinking")
            } else {
                Text(line.text).font(HNFont.mono(10.5)).foregroundStyle(color(line.tone)).lineLimit(1).truncationMode(.tail)
            }
            if let q = session.queued, q > 0 {
                Text("\(q) queued").font(HNFont.mono(10)).foregroundStyle(Color.hnDim).fixedSize()
            }
            Spacer(minLength: 0)
        }
    }

    /// PROJECT and its tags on the left; the model chip and the mode tag on the right, the mode's
    /// words giving way first when the row is tight, and both to the links that lift a block
    /// (Continue, Don't, Add credits, Fix key) when there are some.
    private var metaLine: some View {
        let chip = SessionWords.model(session, provider: providerOfSession)
        let mode = ModeWords.label(session.permissionMode)
        let links = actions
        func line(_ words: Bool, meta: Bool = true) -> some View {
            HStack(spacing: 6) {
                if let p = SessionWords.project(session) {
                    Text(p).font(HNFont.label).tracking(1.1).textCase(.uppercase).foregroundStyle(Color.hnDim).lineLimit(1).layoutPriority(1)
                }
                if session.automation != nil { AutoTag(name: session.automation?.name) }
                if codexTag { CodexTag(continued: session.switchedFrom != nil) }
                if session.agentsBusy > 0 { TagChip(text: SessionWords.agents(session.agentsBusy), color: Color.hnAccent.opacity(0.85)) }
                Spacer(minLength: 8)
                if links.isEmpty || meta {
                    // On another provider: its ring, as its moon wears on the phone.
                    if otherProvider != nil {
                        Ellipse().strokeBorder(Color.hnDim, lineWidth: 1).frame(width: 9, height: 4)
                    }
                    TagChip(text: chip)
                    if session.permissionMode != nil { ModeTag(mode: session.permissionMode, label: words ? mode : nil) }
                }
                ForEach(links, id: \.title) { a in
                    Button(a.title, action: a.run).buttonStyle(LinkStyle()).help(a.help).fixedSize().layoutPriority(2)
                }
            }
        }
        return ViewThatFits(in: .horizontal) {
            line(true)
            line(false)
            line(false, meta: false)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([SessionWords.project(session), codexTag ? "Codex." : nil, "Model: \(chip).", mode.map { "Permission mode: \($0)." }].compactMap { $0 }.joined(separator: " "))
    }

    private func color(_ t: SessionWords.Tone) -> Color {
        switch t {
        case .accent: return Color.hnAccent.opacity(0.75)
        case .amber: return Color.hnAmber.opacity(0.9)
        case .danger: return .hnDanger
        case .muted: return .hnMuted
        case .dim: return .hnDim
        }
    }

    private func open() {
        guard canOpen else { return }
        model.openInTerminal(session, then: dismiss)
    }

    /// The provider it runs on, when that isn't the subscription.
    private var otherProvider: Provider? {
        guard let id = session.providerId, id != "anthropic" else { return nil }
        return model.providers.first { $0.id == id && $0.builtIn != true }
    }
    private var providerOfSession: Provider? { model.providers.first { $0.id == session.providerId } }

    /// The one thing to do from here: Continue after being away; for a block, Don't, Continue
    /// automatically, Continue, or Add credits. Claude Code's own switch works only in a shared
    /// terminal (the daemon types it).
    private var actions: [(title: String, help: String, run: () -> Void)] {
        let s = session
        guard s.state == "idle", !needs else { return [] }
        if SessionWords.blocked(s, provider: otherProvider, account: model.account, now: Date()) == nil {
            if s.stoppedForAway == true { return [("Continue", "Tell it you're back, so it carries on", { model.continueSession(s) })] }
            return []
        }
        guard let b = s.blocked else { return [] }
        if let r = b.resume {
            if r.ready { return [("Continue", "Carry on where the limit stopped it", { model.continueAfterLimit(s) })] }
            if r.armed {
                // A limit that carries on by itself offers both ways out (owner, 2026-10-16): leave it
                // be, or go now. Continue now is the ordinary continue after a limit: it interrupts nothing.
                var out: [(title: String, help: String, run: () -> Void)] = []
                if r.canCancel == true { out.append(("Don’t", "Don’t carry on by itself after the limit", { model.autoContinue(s, on: false) })) }
                out.append(("Continue now", "Carry on now, without waiting for the time it named", { model.continueAfterLimit(s) }))
                return out
            }
            if r.off, r.canArm == true { return [("Continue automatically", "Carry on by itself once the limit lifts", { model.autoContinue(s, on: true) })] }
        }
        if b.kind == "provider", let p = otherProvider {
            switch p.status?.state {
            case "no_credit" where p.kind == "openrouter":
                return [("Add credits", "Top up OpenRouter in the browser", { NSWorkspace.shared.open(URL(string: "https://openrouter.ai/settings/credits")!) })]
            case "bad_key":
                return [("Fix key", "Paste a new key for \(p.name)", { dismiss(); WindowManager.shared.showProviders(editing: p.id) })]
            case "down", "limited":
                return [("Try again", "Ask \(p.name) again now", { model.checkProvider(p) })]
            default: break
            }
        }
        return []
    }

    private var help: String {
        let place = session.cwd.map { Paths.tilde($0) } ?? ""
        // Claude Code's own words for a block lead, as the account card's do.
        let blocked = SessionWords.blocked(session, provider: otherProvider, account: model.account, now: Date()) != nil
        let said = blocked ? (session.blocked?.text.map { $0 + "\n\n" } ?? "") : ""
        let on = session.isCodex ? (session.switchedFrom != nil ? "On Codex, continued from Claude Code. " : "On Codex. ")
            : (otherProvider.map { "On \($0.name). " } ?? "") + (session.switchedFrom != nil ? "Continued from Codex. " : "")
        guard canOpen else { return said + on + "Not a shared terminal, so it opens only where it runs. \(place)" }
        return said + on + (shownInWindow ? "Bring its terminal window forward. " : "Open it in a terminal window. ") + place
    }
}

/// The small chip after a Codex session's project (docs/PROTOCOL.md, "Codex"): a name, like the
/// model's chip, so it never reads as part of the project. [continued]: it went on from a Claude
/// Code session (Switching agents).
struct CodexTag: View {
    var continued = false

    var body: some View {
        TagChip(text: "Codex")
            .help(continued ? "An OpenAI Codex session, continued from Claude Code" : "An OpenAI Codex session")
    }
}

// MARK: - Tiles and page headers

/// A quick action: a glass tile with an icon over a short label.
struct ActionTile: View {
    var symbol: String
    var title: String
    var action: () -> Void
    @State private var hover = false
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: symbol).font(.system(size: 14, weight: .regular))
                    .foregroundStyle(hover ? Color.hnAccent : Color.hnMuted).frame(height: 16)
                Text(title).font(HNFont.sans(11.5)).foregroundStyle(hover ? Color.hnInk : Color.hnMuted).lineLimit(1)
            }
            .frame(maxWidth: .infinity).frame(height: 58)
            .background(shape.fill(LinearGradient(colors: [Color.hnBg800.opacity(0.8), Color.hnBg900.opacity(0.72)], startPoint: .top, endPoint: .bottom)))
            .overlay(alignment: .top) {
                LinearGradient(colors: [.clear, Color.hnInk.opacity(0.2), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1).padding(.horizontal, 10)
            }
            .overlay(shape.strokeBorder(Color.hnInk.opacity(hover ? 0.16 : 0.08), lineWidth: 1))
            .contentShape(shape)
            .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(PressStyle())
        .onHover { hover = $0 && enabled }
        .animation(.hnExpo(0.15), value: hover)
    }
}

/// Moves 1pt down while pressed, like the pills.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .offset(y: configuration.isPressed ? 1 : 0)
            .animation(.hnExpo(0.15), value: configuration.isPressed)
    }
}

/// A sub-page's header: Back, then the page's seam.
struct PageSeam: View {
    var title: String
    var trail: String? = nil
    var activity: Activity = .rest
    var back: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 10) {
            Button(action: back) {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left").font(.system(size: 8.5, weight: .semibold))
                    Text("Back").font(HNFont.sans(11.5))
                }
                .foregroundStyle(hover ? Color.hnInk : Color.hnMuted)
                .padding(.horizontal, 9).frame(height: 22)
                .background(Capsule().fill(Color.hnInk.opacity(hover ? 0.08 : 0.04)))
                .overlay(Capsule().strokeBorder(Color.hnInk.opacity(0.09), lineWidth: 1))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .onHover { hover = $0 }
            .keyboardShortcut("[", modifiers: .command)
            Seam(label: title, trail: trail, activity: activity)
        }
    }
}
