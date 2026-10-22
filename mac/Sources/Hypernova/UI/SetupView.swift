import AppKit
import SwiftUI

/// The setup window: a progress rail on the left, one calm step on the right.
struct SetupView: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        CosmosBackdrop {
            HStack(spacing: 0) {
                SetupRail()
                    .frame(width: 252)
                    .background(Color.hnBg950.opacity(0.62))
                Rectangle().fill(Color.hnLine).frame(width: 1)
                ZStack {
                    stepView(m.current)
                        .id(m.current)
                        .transition(.asymmetric(insertion: .opacity.combined(with: .scale(scale: 0.98)).combined(with: .offset(y: 8)), removal: .opacity))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
            }
        }
        .environmentObject(m.voice)
        .frame(minWidth: 760, minHeight: 540)
    }

    @ViewBuilder
    private func stepView(_ s: SetupModel.Step) -> some View {
        switch s {
        case .claude: ClaudeStep()
        case .service: ServiceStep()
        case .connect: ConnectStep()
        case .tailscale: TailscaleStep()
        case .voice: VoiceSetupStep()
        case .codex: CodexSetupStep()
        case .phone: PhoneStep()
        }
    }
}

// MARK: - Rail

struct SetupRail: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: 50)
            BrandRow()
            Text("Setup").hnLabel(.hnDim).padding(.top, 10)
            Spacer().frame(height: 34)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(SetupModel.Step.allCases) { s in
                    RailItem(step: s, isLast: s == .phone)
                }
            }
            Spacer()
            VStack(alignment: .leading, spacing: 8) {
                if m.daemonUp {
                    StatusChip(text: "Service running", color: .hnAccent)
                } else {
                    StatusChip(text: "Service off", color: .hnDim)
                }
                Text("You can close this window at any time. Setup picks up where you left off.")
                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.bottom, 24)
        }
        .padding(.horizontal, 26)
    }
}

private struct RailItem: View {
    @EnvironmentObject private var m: SetupModel
    var step: SetupModel.Step
    var isLast: Bool
    @State private var hover = false

    var body: some View {
        let current = m.current == step
        let done = m.isDone(step)
        let attention = m.needsAttention(step)
        Button { withAnimation(.hnExpo(0.5)) { m.current = step } } label: {
            HStack(alignment: .top, spacing: 14) {
                VStack(spacing: 0) {
                    RailNode(done: done, current: current, attention: attention, busy: m.isBusy(step) && current)
                    if !isLast {
                        Rectangle()
                            .fill(done ? Color.hnAccent.opacity(0.55) : Color.hnLine)
                            .frame(width: 1, height: 32)
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(step.title)
                        .font(HNFont.sans(13, current ? .semibold : .medium))
                        .foregroundStyle(current ? Color.hnInk : done ? Color.hnMuted : hover ? Color.hnMuted : Color.hnDim)
                    Text(m.trail(step))
                        .font(HNFont.mono(10))
                        .foregroundStyle(attention ? Color.hnAmber : done ? Color.hnAccentDim : Color.hnGhost)
                        .lineLimit(1)
                }
                .padding(.top, 1)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .accessibilityLabel("\(step.title), \(done ? "done" : m.trail(step))")
        .accessibilityAddTraits(current ? .isSelected : [])
    }
}

private struct RailNode: View {
    var done: Bool
    var current: Bool
    var attention: Bool
    var busy: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if done {
                Circle().fill(Color.hnAccent)
                CheckIcon(size: 10, color: .hnBg)
            } else if current {
                Circle().strokeBorder(attention ? Color.hnAmber : Color.hnAccent, lineWidth: 1.5)
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion || !busy)) { tl in
                    let k = 0.5 - 0.5 * cos(2 * .pi * tl.date.timeIntervalSinceReferenceDate / 1.6)
                    Circle().fill(attention ? Color.hnAmber : Color.hnAccent).frame(width: 6, height: 6)
                        .opacity(busy && !reduceMotion ? 1 - 0.6 * k : 1)
                }
            } else if attention {
                Circle().strokeBorder(Color.hnAmber.opacity(0.8), lineWidth: 1)
                Circle().fill(Color.hnAmber).frame(width: 4, height: 4)
            } else {
                Circle().strokeBorder(Color.hnLineStrong, lineWidth: 1)
            }
        }
        .frame(width: 18, height: 18)
        .animation(.hnExpo(0.5), value: done)
    }
}

// MARK: - Step scaffold

/// Seam, title, one or two plain sentences, the step's content, then actions along the bottom.
struct StepScaffold<Content: View, Actions: View>: View {
    @EnvironmentObject private var m: SetupModel
    var step: SetupModel.Step
    var title: String
    var lede: String
    var activity: Activity = .rest
    @ViewBuilder var content: Content
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: 44)
            Seam(label: "Step \(step.rawValue + 1) of \(SetupModel.Step.allCases.count)", trail: step.title, activity: activity)
            Text(title)
                .font(HNFont.sans(28)).tracking(-0.4)
                .foregroundStyle(Color.hnInk)
                .padding(.top, 22)
            Text(lede)
                .font(HNFont.body).lineSpacing(3)
                .foregroundStyle(Color.hnMuted)
                .frame(maxWidth: 500, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(.top, 24)
            Spacer(minLength: 16)
            HStack(spacing: 10) { actions }
        }
        .padding(.horizontal, 44)
        .padding(.bottom, 30)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// "Waiting for the background service" for steps that need the daemon.
struct NeedsService: View {
    @EnvironmentObject private var m: SetupModel
    var body: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                Seam(label: "Waiting", trail: "background service", activity: .rest)
                Text("This step needs the background service.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                Text("Start it in the Background service step, and this one continues on its own.").font(HNFont.small).foregroundStyle(Color.hnMuted)
            }
        }
    }
}

/// A key/value line in a card.
struct InfoLine: View {
    var key: String
    var value: String
    var mono = true
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key).hnLabel(.hnDim).frame(width: 74, alignment: .leading)
            Text(value).font(mono ? HNFont.monoS : HNFont.small).foregroundStyle(Color.hnInk).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A checklist line: done (check), active (bars), or pending (dot).
struct CheckLine: View {
    enum State { case pending, active, done, off }
    var state: State
    var title: String
    var detail: String?
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            ZStack {
                switch state {
                case .done: CheckIcon(size: 12)
                case .active: PlanetMeter(activity: .live)
                case .pending: Circle().strokeBorder(Color.hnLineStrong, lineWidth: 1).frame(width: 8, height: 8)
                case .off: Circle().fill(Color.hnGhost).frame(width: 4, height: 4)
                }
            }
            .frame(width: 14, height: 12)
            .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 4 }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(HNFont.small).foregroundStyle(state == .pending || state == .off ? Color.hnMuted : Color.hnInk)
                if let detail { Text(detail).font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true) }
            }
        }
    }
}

struct ErrorText: View {
    var text: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            CrossIcon(size: 10).alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 4 }
            Text(text).font(HNFont.small).foregroundStyle(Color.hnDanger).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }
    }
}

// MARK: - a. Claude Code

struct ClaudeStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        StepScaffold(step: .claude, title: "Claude Code.",
                     lede: !m.claudeMissing
                        // Claude Code here: Codex beside it has a step of its own (a Mac without Codex reads as before).
                        ? (m.codexHere
                            ? "Hypernova works with Claude Code and OpenAI’s Codex. Claude Code needs to be on this Mac and signed in to your account; Codex has a step of its own."
                            : "Hypernova is a control room for Claude Code, so Claude Code needs to be on this Mac and signed in to your account.")
                        : m.codexHere
                            ? (m.codexUsable
                                ? "Hypernova works with Claude Code and OpenAI’s Codex. Claude Code needs to be on this Mac and signed in to your account, or you can use Codex alone."
                                : "Hypernova works with Claude Code and OpenAI’s Codex. Claude Code needs to be on this Mac and signed in to your account, or Codex brought up to date.")
                            // Neither agent here yet: both ways in, so nobody installs the one they didn't want.
                            : "Hypernova works with Claude Code and OpenAI’s Codex, together or either one alone. Neither is on this Mac yet.",
                     activity: m.claudePhase == .installing ? .live : m.claudePhase == .needsSignIn ? .alert : .rest) {
            switch m.claudePhase {
            case .checking:
                HStack(spacing: 10) { PlanetMeter(activity: .live); Text("Looking for Claude Code…").font(HNFont.small).foregroundStyle(Color.hnMuted) }
            case .missing where m.codexOnly:
                // Codex alone, chosen: nothing waits here; Claude Code can still come later.
                GlassCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Seam(label: "Codex only", trail: "Claude Code not installed")
                        Text("Hypernova uses Codex on this Mac.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                        Text("Install Claude Code any time to use both: Hypernova installs it with Anthropic's official installer, and its steps come back.")
                            .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: 560, alignment: .leading)
            case .missing:
                GlassCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Seam(label: "Not installed")
                        Text("Claude Code isn't on this Mac yet.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                        Text("Hypernova installs it with Anthropic's official installer. It takes about a minute and doesn't ask for your password.")
                            .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                        if m.codexTooOld {
                            // Codex is here but too old: it can't stand in for Claude Code until it's updated.
                            Rectangle().fill(Color.hnLine).frame(height: 1).padding(.vertical, 4)
                            HStack(spacing: 8) {
                                StatusChip(text: "Too old", color: .hnAmber)
                                Text("Codex \(codexVersion ?? "") is on this Mac; Hypernova needs \(m.codex?.min ?? "a newer one") or later.").font(HNFont.small).foregroundStyle(Color.hnInk)
                            }
                            Text("Update it to use it alone, then Check again. Hypernova doesn't update it for you.")
                                .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                            ShellLine(key: "Update", command: "codex update")
                        } else if m.codexUsable {
                            // Codex is here: Setup can go on without Claude Code (never a dead end).
                            Rectangle().fill(Color.hnLine).frame(height: 1).padding(.vertical, 4)
                            HStack(spacing: 8) {
                                CheckIcon(size: 11)
                                Text("Codex is on this Mac\(codexVersion.map { " (\($0))" } ?? "").").font(HNFont.small).foregroundStyle(Color.hnInk)
                            }
                            Text("Use it alone: Hypernova skips Claude Code's steps and turns Codex on. You can add Claude Code later.")
                                .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                        } else {
                            // No agent at all: Codex is the other way in, installed in a terminal (never from here).
                            Rectangle().fill(Color.hnLine).frame(height: 1).padding(.vertical, 4)
                            Text("Or use OpenAI’s Codex instead. Hypernova doesn’t install it: run these in a terminal, then Check again.")
                                .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                            ShellLine(key: "Install", command: CodexDetector.installNPM)
                            ShellLine(key: "or", command: CodexDetector.installBrew)
                            ShellLine(key: "Sign in", command: "codex login")
                        }
                    }
                }
                .frame(maxWidth: 560, alignment: .leading)
            case .installing, .installFailed:
                LogView(title: "Installing Claude Code", lines: m.claudeLog, running: m.claudePhase == .installing, failed: m.claudePhase != .installing, height: 170)
                if case .installFailed(let msg) = m.claudePhase { ErrorText(text: msg) }
            case .needsSignIn, .ready:
                found
                if m.claudePhase == .needsSignIn {
                    if m.daemonUp {
                        // Through the background service, here: the page, then its code.
                        signInHere
                    } else {
                        Text(m.signInOpened
                             ? "Finish signing in in the Terminal window. When Claude Code shows its prompt, come back here; Hypernova checks again on its own."
                             : "One more thing: sign in once. Hypernova opens Terminal with Claude Code running; follow its steps, then come back.")
                            .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: 500, alignment: .leading)
                    }
                }
                if let e = m.claudeError { ErrorText(text: e) }
            }
        } actions: {
            switch m.claudePhase {
            case .checking: Spacer()
            case .missing where m.codexOnly:
                CompactPill(title: "Install Claude Code", kind: .ghost) { m.installClaude() }
                Spacer()
                ActionPill(title: "Continue") { m.goOn() }
            case .missing:
                CompactPill(title: "Check again", kind: .ghost) { m.recheckClaude() }
                Spacer()
                if m.codexUsable {
                    CompactPill(title: "Use Codex only", kind: .outline) { m.useCodexOnly() }
                        .help("Skip Claude Code's steps and use Codex with Hypernova. Nothing is installed.")
                }
                ActionPill(title: "Install Claude Code") { m.installClaude() }
            case .installing:
                Spacer()
                ActionPill(title: "Installing…", busy: true) {}
            case .installFailed:
                Spacer()
                if m.codexUsable {
                    CompactPill(title: "Use Codex only", kind: .outline) { m.useCodexOnly() }
                }
                ActionPill(title: "Try again") { m.installClaude() }
            case .needsSignIn where m.daemonUp:
                CompactPill(title: "Check again", kind: .ghost) { m.recheckClaude() }
                Spacer()
                switch m.serviceLogin?.state {
                case "waiting_code"?:
                    ActionPill(title: m.signInBusy ? "Sending…" : "Send code", busy: m.signInBusy, disabled: m.signInCode.trimmingCharacters(in: .whitespaces).isEmpty) { m.sendSignInCode() }
                case "starting"?, "finishing"?:
                    ActionPill(title: "Signing in…", busy: true) {}
                default:
                    ActionPill(title: m.signInBusy ? "Starting…" : "Sign in", busy: m.signInBusy) { m.startSignInHere() }
                }
            case .needsSignIn:
                if m.signInOpened {
                    CompactPill(title: "Open Terminal again", kind: .ghost) { m.openSignIn() }
                    Spacer()
                    ActionPill(title: "Check again") { m.recheckClaude() }
                } else {
                    CompactPill(title: "Check again", kind: .ghost) { m.recheckClaude() }
                    Spacer()
                    ActionPill(title: "Open Terminal to sign in") { m.openSignIn() }
                }
            case .ready:
                Spacer()
                ActionPill(title: "Continue") { m.next() }
            }
        }
    }

    /// Codex's version, as the daemon or Setup itself found it.
    private var codexVersion: String? { m.codex?.version ?? m.codexFound?.version }

    /// Signing in through the service: open the page, approve, paste the code it shows.
    @ViewBuilder private var signInHere: some View {
        let login = m.serviceLogin
        VStack(alignment: .leading, spacing: 10) {
            switch login?.state {
            case "waiting_code"?:
                Text("Open the sign-in page, approve Claude Code, then paste the code it shows.")
                    .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                HStack(alignment: .bottom, spacing: 10) {
                    CompactPill(title: "Open the sign-in page", kind: .outline) { m.openSignInPage() }
                        .disabled(login?.url == nil)
                    HNField(label: "Code", text: $m.signInCode, placeholder: "Paste the code from the page", mono: true)
                }
            case "starting"?, "finishing"?:
                HStack(spacing: 10) { PlanetMeter(activity: .live); Text(login?.state == "finishing" ? "Finishing the sign-in…" : "Starting the sign-in…").font(HNFont.small).foregroundStyle(Color.hnMuted) }
            case "failed"?:
                Text(login?.error ?? "The sign-in didn’t finish. Start it again.").font(HNFont.small).foregroundStyle(Color.hnAmber).fixedSize(horizontal: false, vertical: true)
            default:
                Text("One more thing: sign in once. Hypernova opens Claude Code's sign-in page in your browser; you paste the code it shows back here.")
                    .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: 520, alignment: .leading)
    }

    private var found: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 10) {
                Seam(label: "Found", trail: m.claude.version.map { "v\($0)" }, activity: .rest)
                InfoLine(key: "Path", value: Paths.tilde(m.claude.path ?? ""))
                HStack(spacing: 12) {
                    Text("Account").hnLabel(.hnDim).frame(width: 74, alignment: .leading)
                    if m.claude.signedIn == false {
                        StatusChip(text: "Not signed in", color: .hnAmber)
                    } else {
                        HStack(spacing: 6) {
                            CheckIcon(size: 11)
                            Text(m.claude.account ?? "Signed in").font(HNFont.small).foregroundStyle(Color.hnInk).lineLimit(1)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - b. Node.js

// MARK: - c. Background service

struct ServiceStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        StepScaffold(step: .service, title: "Background service.",
                     lede: "A small service runs in the background, so your phone can reach \(m.agentNames) even when this menu is closed. It starts with your Mac and restarts itself if anything goes wrong.",
                     activity: activity) {
            switch m.servicePhase {
            case .checking:
                HStack(spacing: 10) { PlanetMeter(activity: .live); Text("Checking the service…").font(HNFont.small).foregroundStyle(Color.hnMuted) }
            case .running, .needsUpdate:
                GlassCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Seam(label: "Running", trail: "port \(Endpoints.apiPort)", activity: .live)
                        InfoLine(key: "Mac", value: m.health?.name ?? "this Mac", mono: false)
                        InfoLine(key: "Version", value: m.status?.daemonVersion ?? "—")
                        if let n = m.node { InfoLine(key: "Node.js", value: "\(n.version ?? "") \(Paths.tilde(n.path))") }
                        InfoLine(key: "Folder", value: Paths.tilde(Paths.support.path))
                        if m.servicePhase == .needsUpdate {
                            Text("This copy of Hypernova has a newer service. Updating takes a few seconds.").font(HNFont.small).foregroundStyle(Color.hnAmber)
                        }
                    }
                }
            case .notInstalled, .unmanaged, .notAnswering, .installing:
                GlassCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Seam(label: headline, activity: activity)
                        // Node.js is the service's first part: the one it runs on, or Hypernova's own copy.
                        CheckLine(state: lineState(.gettingNode(nil)), title: nodeTitle, detail: nodeDetail)
                        CheckLine(state: lineState(.copying), title: "Copy the service into Hypernova's folder", detail: Paths.tilde(Paths.installedDaemon.path))
                        CheckLine(state: lineState(.writing), title: "Add it to your login items", detail: "A launch agent, so it starts with your Mac")
                        CheckLine(state: lineState(.starting), title: "Start it")
                        CheckLine(state: lineState(.waiting), title: "Check that it answers")
                        if case .installing(.gettingNode(let f?)) = m.servicePhase { ProgressLine(fraction: f) }
                        if m.servicePhase == .unmanaged {
                            note("A copy of the service is already running, but not as a background service, so it won't start with your Mac.", .hnMuted)
                        }
                        if m.servicePhase == .notAnswering {
                            note("The service is installed but isn't answering. Starting it again usually fixes that.", .hnAmber)
                        }
                    }
                }
            case .failed(let msg):
                ErrorText(text: msg.components(separatedBy: "\n\nLast lines").first ?? msg)
                if !m.serviceLogTail.isEmpty {
                    LogView(title: "daemon.log", lines: m.serviceLogTail, running: false, failed: true, height: 140)
                }
            }
        } actions: {
            if m.servicePhase == .running || m.servicePhase == .needsUpdate || { if case .failed = m.servicePhase { return true } else { return false } }() {
                CompactPill(title: "Show log", kind: .ghost) {
                    NSWorkspace.shared.open([Paths.daemonLog], withApplicationAt: URL(fileURLWithPath: "/System/Applications/Utilities/Console.app"), configuration: NSWorkspace.OpenConfiguration())
                }
            }
            Spacer()
            switch m.servicePhase {
            case .checking: EmptyView()
            case .running: ActionPill(title: "Continue") { m.next() }
            case .needsUpdate:
                CompactPill(title: "Update", kind: .outline) { m.installService() }
                ActionPill(title: "Continue") { m.next() }
            case .notInstalled, .unmanaged: ActionPill(title: "Start background service") { m.installService() }
            case .notAnswering: ActionPill(title: "Start it again") { m.installService() }
            case .installing(.gettingNode): ActionPill(title: "Getting Node.js…", busy: true) {}
            case .installing: ActionPill(title: "Starting…", busy: true) {}
            case .failed: ActionPill(title: "Try again") { m.installService() }
            }
        }
    }

    /// "Use Node.js 24.11.1" when there's one to run on, else "Get Node.js".
    private var nodeTitle: String {
        if let n = m.node { return "Use Node.js \(n.version.map { $0.hasPrefix("v") ? String($0.dropFirst()) : $0 } ?? "")" }
        return m.nodeRelease.map { "Get Node.js \($0.version.dropFirst().split(separator: ".").first ?? "")" } ?? "Get Node.js"
    }

    private var nodeDetail: String? {
        if let n = m.node { return "\(n.source.prefix(1).uppercased() + n.source.dropFirst()), at \(Paths.tilde(n.path))" }
        guard m.nodeChecked else { return nil }
        return "The official \(m.nodeRelease.map { "\($0.ltsName) LTS" } ?? "LTS") from nodejs.org, checked against its published checksums, kept in Hypernova's folder"
    }

    private func note(_ text: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Rectangle().fill(Color.hnLine).frame(height: 1)
            Text(text).font(HNFont.sans(12)).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 2)
    }

    private var activity: Activity {
        switch m.servicePhase {
        case .installing: return .live
        case .failed, .notAnswering: return .alert
        default: return .rest
        }
    }

    private var headline: String {
        switch m.servicePhase {
        case .installing: return "Setting up"
        case .notAnswering: return "Not answering"
        default: return "What happens"
        }
    }

    private func lineState(_ p: ServiceInstaller.Progress) -> CheckLine.State {
        guard case .installing(let cur) = m.servicePhase else { return .pending }
        // With a Node.js to run on, nothing is fetched: that line is done from the start.
        if p.rank == 0, cur.rank > 0 || m.node != nil { return .done }
        return p.rank < cur.rank ? .done : p.rank == cur.rank ? .active : .pending
    }
}

// MARK: - d. Connect Claude Code

struct ConnectStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        StepScaffold(step: .connect, title: "Connect Claude Code.",
                     lede: "Hypernova adds a few hooks, a status line tap and a small “claude” shell function, so your phone sees your sessions, approvals and usage, and can work in your terminal sessions too. Everything it adds is marked as Hypernova's, and Uninstall removes exactly that.",
                     activity: m.connectBusy ? .live : .rest) {
            if m.codexOnly {
                // Codex alone: nothing of Claude Code's to connect.
                GlassCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Seam(label: "Skipped", trail: "Codex only")
                        Text("Nothing to connect.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                        Text("Claude Code isn't on this Mac. Once it is, this step connects it, and Codex keeps working beside it.")
                            .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: 560, alignment: .leading)
            } else if !m.daemonUp {
                NeedsService()
            } else {
                let hooks = m.status?.hooks?.installed == true
                let line = m.status?.statusLine
                GlassCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Seam(label: hooks ? "Connected" : "What it adds", activity: m.connectBusy ? .live : .rest)
                        CheckLine(state: hooks ? .done : m.connectBusy ? .active : .pending, title: "Hooks", detail: "Sessions, prompts and approvals, as they happen")
                        // Any status line is wrapped now (the daemon's hooks.ts), so the tap is always there to add.
                        CheckLine(state: line?.installed == true ? .done : m.connectBusy ? .active : .pending,
                                  title: "Status line tap",
                                  detail: "Your 5-hour and weekly limits. Your own status line keeps working and showing what it shows; Hypernova only listens in.")
                        let shell = m.status?.shell
                        CheckLine(state: shell?.supported == false ? .off : shell?.installed == true ? .done : m.connectBusy ? .active : .pending,
                                  title: "Shared terminals",
                                  detail: shell?.supported == false
                                      ? "Your shell isn't zsh, so sessions you start in a terminal stay on the Mac. Sessions you start on the phone are still shared."
                                      : "“claude” in a terminal looks and works the same, but your phone can watch it live and type into it. Open a new terminal tab for it to take effect; “command claude” skips it.")
                    }
                }
                if let e = m.connectError { ErrorText(text: e) }
            }
        } actions: {
            Spacer()
            if m.codexOnly {
                ActionPill(title: "Continue") { m.goOn() }
            } else if !m.daemonUp {
                ActionPill(title: "Go to Background service") { withAnimation(.hnExpo(0.5)) { m.current = .service } }
            } else if m.status?.hooks?.installed == true {
                ActionPill(title: "Continue") { m.next() }
            } else {
                ActionPill(title: m.connectBusy ? "Connecting…" : "Connect Claude Code", busy: m.connectBusy) { m.connect() }
            }
        }
    }
}

// MARK: - e. Tailscale

struct TailscaleStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        StepScaffold(step: .tailscale, title: "Tailscale.",
                     // With the local network's listener on, a phone on the same Wi‑Fi pairs without it (owner's audit M4).
                     lede: m.localRoute
                        ? "Away from home, your phone reaches this Mac through Tailscale, a private network between your own devices. On the same Wi‑Fi it needs nothing more. Nothing is opened to the internet."
                        : "Your phone reaches this Mac through Tailscale, a private network between your own devices. Nothing is opened to the internet.",
                     activity: m.serveBusy ? .live : .rest) {
            if !m.daemonUp {
                NeedsService()
            } else if let ts = m.status?.tailscale, let action = m.tailscaleAction {
                card(action, ts)
                if let e = m.serveError { ErrorText(text: e) }
                if let exp = TailscaleStepLogic.expiringSoon(ts, tsnet: m.status?.tsnet) { expiryWarning(exp) }
            } else {
                HStack(spacing: 10) { PlanetMeter(activity: .live); Text("Asking Tailscale…").font(HNFont.small).foregroundStyle(Color.hnMuted) }
            }
        } actions: {
            // Optional on the same Wi‑Fi: leaving is quiet, as on Voice and Codex (not beside the
            // choice of app or built-in node, which already fills the row).
            if m.daemonUp, m.localRoute, m.tailscaleAction != nil, !isReady, m.tailscaleChoice == nil {
                CompactPill(title: "Not now", kind: .ghost) { m.next() }
                    .help("Phones on this Wi‑Fi pair without Tailscale. Come back to this step to reach this Mac from anywhere.")
            }
            if m.daemonUp, m.tailscaleAction != nil, !isReady {
                CompactPill(title: m.tailscaleChecking ? "Checking…" : "Check again", kind: .ghost) { m.checkTailscale() }
            }
            // The Tailscale app or Hypernova's own node, where both are there (owner's audit M2).
            if let c = m.tailscaleChoice {
                CompactPill(title: c.title, kind: .ghost) { m.useBuiltInTailscale(c.builtIn) }
                    .help(c.builtIn ? "Hypernova runs its own Tailscale node: no Tailscale app needed. You sign it in once, here." : "Hand phone access to the Tailscale app on this Mac. Paired phones follow by themselves.")
            }
            Spacer()
            if !m.daemonUp {
                ActionPill(title: "Go to Background service") { withAnimation(.hnExpo(0.5)) { m.current = .service } }
            } else if let a = m.tailscaleAction {
                switch a {
                case .install: ActionPill(title: "Download Tailscale") { NSWorkspace.shared.open(Endpoints.tailscaleDownload) }
                case .openApp: ActionPill(title: "Open Tailscale") { TailscaleDetector.openApp() }
                case .enableHTTPS: ActionPill(title: "Open DNS settings") { NSWorkspace.shared.open(Endpoints.tailscaleDNS) }
                case .turnOnServe: ActionPill(title: m.serveBusy ? "Turning on…" : "Turn on phone access", busy: m.serveBusy) { m.turnOnPhoneAccess() }
                case .ready, .readyBuiltIn: ActionPill(title: "Continue") { m.next() }
                case .signInBuiltIn(let url): ActionPill(title: "Open the sign-in link") { if let u = URL(string: url), u.scheme == "https" { NSWorkspace.shared.open(u) } }
                case .builtInWaiting: EmptyView()
                }
            }
        }
    }

    private var isReady: Bool {
        if case .ready = m.tailscaleAction { return true }
        if case .readyBuiltIn = m.tailscaleAction { return true }
        return false
    }

    @ViewBuilder
    private func card(_ action: TailscaleAction, _ ts: TailscaleStatus) -> some View {
        switch action {
        case .install:
            explain("Not installed", "Tailscale isn't on this Mac yet.", "Download it from tailscale.com, open it and sign in. Hypernova notices when it's ready.")
        case .openApp(let loggedOut):
            explain(loggedOut ? "Not signed in" : "Not running",
                    loggedOut ? "Sign in to Tailscale." : "Tailscale isn't running.",
                    loggedOut ? "Open Tailscale from the menu bar and sign in. Use the same account on your phone." : "Open it and make sure it's connected. Hypernova notices when it is.")
        case .enableHTTPS:
            explain("Needs HTTPS", "Turn on HTTPS certificates.",
                    "Your phone needs a trusted, encrypted connection, so Tailscale issues this Mac a certificate. In the admin console's DNS page, find HTTPS Certificates and click Enable, then come back.")
        case .turnOnServe:
            GlassCard {
                VStack(alignment: .leading, spacing: 10) {
                    Seam(label: "Tailscale app")
                    HStack(spacing: 6) { StatusChip(text: "Off", color: .hnMuted) }
                    if let n = ts.dnsName { InfoLine(key: "This Mac", value: n.hasSuffix(".") ? String(n.dropLast()) : n) }
                    Text("Phone access gives Hypernova a private https address that only devices in your tailnet can open.")
                        .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                }
            }
        // Tailscale built in (the daemon's own node; no Tailscale app): the sign-in as a code, then the address.
        case .signInBuiltIn(let url):
            GlassCard {
                VStack(alignment: .leading, spacing: 10) {
                    Seam(label: "Tailscale built in")
                    // The code beside the words, so the card and the step's buttons fit the window.
                    HStack(alignment: .top, spacing: 20) {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 6) { StatusChip(text: "Sign-in open", color: .hnAmber, live: true) }
                            Text("Sign in to connect your devices.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                            Text("Hypernova carries Tailscale, a private network between your own devices. Scan this with your phone, or open the link, and sign in with Google, GitHub, Apple or Microsoft. Use the same account on your phone.")
                                .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                        }
                        QRCodeView(text: url, side: 150)
                    }
                    HStack(spacing: 10) {
                        Text(url).font(HNFont.monoM).foregroundStyle(Color.hnAccentBright).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                        Spacer(minLength: 8)
                        Button("Copy") { m.copy(url) }.buttonStyle(LinkStyle())
                    }
                }
            }
        case .builtInWaiting(let what):
            GlassCard {
                VStack(alignment: .leading, spacing: 10) {
                    Seam(label: "Tailscale built in", activity: .live)
                    HStack(spacing: 10) {
                        StatusChip(text: what == "NeedsMachineAuth" ? "Waiting for approval" : what == "NeedsLogin" ? "Not signed in" : "Starting", color: .hnMuted, live: true)
                        Text(what == "starting" || what == "Starting" ? "Starting the built-in Tailscale node…" : what == "NeedsLogin" ? "Waiting for the sign-in link…" : what == "NeedsMachineAuth" ? "An admin of your tailnet approves this Mac in the admin console." : what)
                            .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        case .readyBuiltIn(let url, let pinned, let account, let node):
            GlassCard {
                VStack(alignment: .leading, spacing: 10) {
                    Seam(label: "Phone access", trail: "Tailscale built in", activity: .live)
                    // The state, then who it's signed in as and the node's name, as chips.
                    HStack(spacing: 6) {
                        StatusChip(text: "Connected", color: .hnAccent)
                        if let account { TagChip(text: account).help("Signed in to Tailscale as \(account)") }
                        if let node { TagChip(text: node.split(separator: ".").first.map(String.init) ?? node).help("This Mac on the tailnet: \(node)") }
                    }
                    HStack(spacing: 10) {
                        Text(url).font(HNFont.monoM).foregroundStyle(Color.hnAccentBright).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                        Spacer(minLength: 8)
                        Button("Copy") { m.copy(url) }.buttonStyle(LinkStyle())
                    }
                    Text(pinned
                         ? "Your tailnet hasn't enabled HTTPS certificates, so the phone reaches this address with this Mac's own certificate, pinned from the pairing code. (In the admin console's DNS page, HTTPS Certificates → Enable, to use the name instead.)"
                         : "Only devices on your tailnet can open it (your account, or whoever your tailnet's access rules let in). No Tailscale app needed, on this Mac or the phone.")
                        .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Spacer()
                        // Another account, or a fresh start: the node signs out and a new code comes.
                        CompactPill(title: "Sign out", kind: .ghost) { m.tsnetSignOut() }
                    }
                }
            }
        case .ready(let url):
            GlassCard {
                VStack(alignment: .leading, spacing: 10) {
                    Seam(label: "Phone access", trail: "Tailscale app", activity: .live)
                    HStack(spacing: 6) {
                        StatusChip(text: "Connected", color: .hnAccent)
                        if let n = ts.dnsName { TagChip(text: n.split(separator: ".").first.map(String.init) ?? n).help("This Mac on the tailnet: \(n)") }
                    }
                    HStack(spacing: 10) {
                        Text(url).font(HNFont.monoM).foregroundStyle(Color.hnAccentBright).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                        Spacer(minLength: 8)
                        Button("Copy") { m.copy(url) }.buttonStyle(LinkStyle())
                    }
                    Text("Only devices on your tailnet can open it (your account, or whoever your tailnet's access rules let in).").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
                }
            }
        }
    }

    private func explain(_ seam: String, _ title: String, _ body: String) -> some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                Seam(label: "Tailscale app")
                HStack(spacing: 6) { StatusChip(text: seam, color: seam == "Not signed in" ? .hnAmber : .hnMuted) }
                Text(title).font(HNFont.heading).foregroundStyle(Color.hnInk)
                Text(body).font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func expiryWarning(_ date: Date) -> some View {
        let left = date.timeIntervalSinceNow
        let when = left <= 0 ? "has expired" : left < 86_400 ? "expires today" : "expires in \(Int(left / 86_400)) day\(Int(left / 86_400) == 1 ? "" : "s")"
        return GlassCard(needsYou: true) {
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("This Mac's Tailscale key \(when).").font(HNFont.small.weight(.semibold)).foregroundStyle(Color.hnAmber)
                    Text("When it does, your phone loses the connection until you sign in to Tailscale again. In the admin console, open this Mac's ⋯ menu and choose Disable key expiry.")
                        .font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                CompactPill(title: "Open Machines", kind: .outline) { NSWorkspace.shared.open(Endpoints.tailscaleMachines) }
            }
        }
    }
}

// MARK: - f. Voice

/// Optional, and asked before the phone pairs so a phone that pairs already has its mic. Three ways
/// out: connect Soniox, use Hyperscribe Companion (found by itself), or Not now. The Mac doesn't
/// listen here: no "hold to try" (docs/specs/spec-voice.md, D14).
struct VoiceSetupStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        StepScaffold(step: .voice, title: "Voice.",
                     lede: "Talk to your sessions from your phone and your glasses: hold the mic, say what to do, read it, send.",
                     activity: m.isBusy(.voice) ? .live : .rest) {
            if !m.daemonUp {
                NeedsService()
            } else {
                VoicePanel(context: .setup)
            }
        } actions: {
            if !m.daemonUp {
                Spacer()
                ActionPill(title: "Go to Background service") { withAnimation(.hnExpo(0.5)) { m.current = .service } }
            } else if m.voice.ready {
                Spacer()
                ActionPill(title: "Continue") { m.next() }
            } else {
                // Connect in the card is the primary; leaving is quiet.
                CompactPill(title: "Not now", kind: .ghost) { m.skipVoice() }
                Spacer()
            }
        }
    }
}

// MARK: - g. Codex

/// Optional, and never installed from here: what's on this Mac (where, which version, signed in),
/// the plain line to run in a terminal for what's missing, and the switch that lets Hypernova use it.
/// Asked before the phone pairs, so a phone that pairs already offers Codex. A Mac without Codex
/// sees one low-key card and goes on.
struct CodexSetupStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        let c = m.codex
        StepScaffold(step: .codex, title: "Codex.",
                     lede: m.claudeMissing
                        ? "OpenAI’s Codex can be the agent on this Mac: start Codex sessions from your phone, answer what it asks, and see its limits."
                        : "Optional. OpenAI’s Codex can work next to Claude Code: start Codex sessions from your phone, and carry a conversation over from one to the other.",
                     activity: m.codexBusy ? .live : m.needsAttention(.codex) ? .alert : .rest) {
            if !m.daemonUp {
                NeedsService()
            } else if let c {
                found(c)
            } else {
                missing
            }
            if let e = m.codexError { ErrorText(text: e) }
        } actions: {
            if !m.daemonUp {
                Spacer()
                ActionPill(title: "Go to Background service") { withAnimation(.hnExpo(0.5)) { m.current = .service } }
            } else if c?.on == true {
                CompactPill(title: "Check again", kind: .ghost) { m.checkCodex() }
                Spacer()
                // Codex alone: on to whatever is still to do (Setup may have opened here first).
                ActionPill(title: "Continue") { if m.claudeMissing { m.goOn() } else { m.next() } }
            } else {
                // The switch in the card is the primary; leaving is quiet.
                CompactPill(title: "Not now", kind: .ghost) { m.skipCodex() }
                CompactPill(title: "Check again", kind: .ghost) { m.checkCodex() }
                Spacer()
            }
        }
    }

    /// Not on this Mac: how to get it, in a terminal, quietly.
    private var missing: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 10) {
                Seam(label: "Not installed", trail: m.claudeMissing ? nil : "optional")
                Text("Codex isn’t on this Mac. Hypernova doesn’t install it; to use it, run one of these in a terminal, then sign in.")
                    .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                ShellLine(key: "Install", command: CodexDetector.installNPM)
                ShellLine(key: "or", command: CodexDetector.installBrew)
                ShellLine(key: "Sign in", command: "codex login")
            }
        }
        .frame(maxWidth: 560, alignment: .leading)
    }

    private func found(_ c: Codex) -> some View {
        let trouble = c.enabled ? CodexWords.trouble(c) : nil
        return GlassCard(needsYou: trouble?.waitsOnYou == true) {
            VStack(alignment: .leading, spacing: 10) {
                Seam(label: "Found", trail: c.enabled ? (c.connected ? "connected" : c.problem == nil ? "connecting" : "not connected") : nil,
                     activity: c.enabled && c.connected ? .live : .rest)
                InfoLine(key: "Path", value: Paths.tilde(c.path ?? ""))
                HStack(spacing: 12) {
                    Text("Version").hnLabel(.hnDim).frame(width: 74, alignment: .leading)
                    if c.tooOld {
                        StatusChip(text: "Too old", color: .hnAmber)
                        Text("Hypernova needs \(c.min ?? "a newer one") or later").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted)
                    } else {
                        HStack(spacing: 6) {
                            CheckIcon(size: 11)
                            Text(c.version ?? "unknown").font(HNFont.monoS).foregroundStyle(Color.hnInk)
                        }
                    }
                }
                HStack(spacing: 12) {
                    Text("Account").hnLabel(.hnDim).frame(width: 74, alignment: .leading)
                    if !c.enabled {
                        Text("Read once it’s on").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
                    } else {
                        switch c.account?.signedIn {
                        case true?:
                            HStack(spacing: 6) {
                                CheckIcon(size: 11)
                                Text(CodexWords.account(c.account) ?? "Signed in").font(HNFont.small).foregroundStyle(Color.hnInk).lineLimit(1)
                            }
                        case false?:
                            StatusChip(text: "Not signed in", color: .hnAmber)
                        case nil:
                            HStack(spacing: 8) { PlanetMeter(activity: .live); Text("Checking…").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted) }
                        }
                    }
                }
                if c.tooOld { ShellLine(key: "Update", command: "codex update") }
                if c.enabled, !c.tooOld, c.account?.signedIn == false { ShellLine(key: "Sign in", command: "codex login") }
                if c.enabled, !c.tooOld, let p = c.problem?.nonEmpty {
                    Text(p).font(HNFont.sans(12)).foregroundStyle(Color.hnAmber).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
                Rectangle().fill(Color.hnLine).frame(height: 1).padding(.vertical, 2)
                HStack(alignment: .top, spacing: 12) {
                    HNSwitch(isOn: c.enabled, label: "Use Codex with Hypernova") { m.setCodex($0) }
                        .padding(.top, 1)
                        // Too old: it can be turned off, never on.
                        .disabled(m.codexBusy || (c.tooOld && !c.enabled))
                        .opacity(c.tooOld && !c.enabled ? 0.4 : 1)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Use Codex with Hypernova").font(HNFont.sans(13)).foregroundStyle(Color.hnInk)
                        Text(CodexWords.switchLine(claude: !m.claudeMissing, shell: m.status?.shell?.installed == true))
                            .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .frame(maxWidth: 560, alignment: .leading)
    }
}

/// A line to run in a terminal: its label, the command in mono in a quiet well, and Copy (which says
/// Copied for a moment). Setup's and the Agents & providers window's.
struct ShellLine: View {
    var key: String
    var command: String
    @State private var copied = false

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(key).hnLabel(.hnDim).frame(width: 74, alignment: .leading)
            (Text("$ ").foregroundColor(.hnDim) + Text(command).foregroundColor(.hnInk))
                .font(HNFont.mono(11.5)).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                // The prompt sign is decoration: VoiceOver reads the command alone.
                .accessibilityLabel(Text(command))
                // One width for every line, so their Copy links line up.
                .frame(width: 230, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.hnBg.opacity(0.7)))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Color.hnLine, lineWidth: 1))
            Button(copied ? "Copied" : "Copy") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(command, forType: .string)
                copied = true
                Task { try? await Task.sleep(nanoseconds: 1_600_000_000); copied = false }
            }
            .buttonStyle(LinkStyle(color: copied ? .hnMuted : .hnAccent))
            .help("Copy \(command), to paste into a terminal")
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - h. Your phone

struct PhoneStep: View {
    @EnvironmentObject private var m: SetupModel

    var body: some View {
        StepScaffold(step: .phone, title: titleText, lede: ledeText, activity: m.phonePhase == .pair ? .live : .rest) {
            if !m.daemonUp {
                NeedsService()
            } else if !m.canPair, !isPaired {
                GlassCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Seam(label: "Waiting", trail: "Tailscale")
                        Text("Turn on phone access first.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                        Text("Your phone talks to this Mac through Tailscale, set up in the Tailscale step.").font(HNFont.small).foregroundStyle(Color.hnMuted)
                    }
                }
            } else {
                switch m.phonePhase {
                case .getApp: getApp
                case .pair: pairView
                case .paired(let name): paired(name)
                }
            }
        } actions: {
            if !m.daemonUp {
                Spacer()
                ActionPill(title: "Go to Background service") { withAnimation(.hnExpo(0.5)) { m.current = .service } }
            } else if !m.canPair, !isPaired {
                Spacer()
                ActionPill(title: "Go to Tailscale") { withAnimation(.hnExpo(0.5)) { m.current = .tailscale } }
            } else {
                switch m.phonePhase {
                case .getApp:
                    if (m.status?.devices?.count ?? 0) > 0 {
                        CompactPill(title: "Done", kind: .ghost) { m.finish() }
                    }
                    Spacer()
                    ActionPill(title: "I have the app") { m.goPair() }
                case .pair:
                    CompactPill(title: "Back", kind: .ghost) { m.backToGetApp() }
                    if let link = m.pair?.link { CompactPill(title: "Copy link", kind: .ghost) { m.copy(link) } }
                    Spacer()
                    ActionPill(title: "Waiting for your phone…", kind: .outline, busy: true) {}
                case .paired:
                    CompactPill(title: "Pair another phone", kind: .ghost) { m.goPair() }
                    Spacer()
                    ActionPill(title: "Finish") { m.finish() }
                }
            }
        }
    }

    private var isPaired: Bool { if case .paired = m.phonePhase { return true } else { return false } }

    private var titleText: String {
        switch m.phonePhase {
        case .getApp: return "Your phone."
        case .pair: return "Pair your phone."
        case .paired(let n): return "Paired with \(n)."
        }
    }

    private var ledeText: String {
        switch m.phonePhase {
        case .getApp:
            // Without a tailnet (Tailscale left for later), the phone pairs over the Wi‑Fi alone.
            let wifiOnly = m.phoneBaseURL == nil
            let wifiLine = "It pairs over your Wi‑Fi; sign in to Tailscale in its step to reach this Mac from anywhere too."
            if let first = m.status?.devices?.items.first {
                let n = m.status?.devices?.count ?? 1
                return "Paired with \(first.displayName)\(n > 1 ? " and \(n - 1) more" : ""). To add another phone, get the app on it, then pair it. "
                    + (wifiOnly ? wifiLine : "The app signs in to Tailscale itself, with the same account as this Mac.")
            }
            return "Get Hypernova for Android, then pair it with this Mac. "
                + (wifiOnly ? wifiLine : "The app signs in to Tailscale itself, with the same account as this Mac (or uses the Tailscale app, if you have it).")
        case .pair: return "Scan this code with your phone. It works once, and refreshes on its own."
        case .paired: return "Your phone can now see sessions, answer approvals and start new ones. Hypernova stays in your menu bar from here on."
        }
    }

    private var getApp: some View {
        HStack(alignment: .top, spacing: 28) {
            if let download = m.downloadURL {
                VStack(spacing: 8) {
                    QRCodeView(text: download, side: 196)
                    if m.downloadOnName {
                        // Through the Mac's own name under hypernova.observer: a real certificate, nothing to compare;
                        // the checksum stays, quietly, for anyone who wants to.
                        Text("Scan to download the app over your Wi‑Fi.")
                            .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).frame(width: 196)
                        if let sum = m.status?.apk?.sha256 {
                            Text("SHA-256 " + sum.grouped4).font(HNFont.mono(9)).foregroundStyle(Color.hnMuted.opacity(0.7)).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).frame(width: 196).textSelection(.enabled)
                        }
                    } else if m.downloadIsLocal {
                        // The local network's listener speaks with this Mac's own certificate, which the phone's
                        // browser can't check: the checksum is what ties the page to this Mac.
                        Text("Over your Wi‑Fi. The browser can't vouch for the page (this Mac's own certificate), so compare its checksum with this one before installing:")
                            .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).frame(width: 196)
                        if let sum = m.status?.apk?.sha256 {
                            Text(sum.grouped4).font(HNFont.mono(10)).foregroundStyle(Color.hnMuted).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).frame(width: 196).textSelection(.enabled)
                        }
                    }else {
                        Text("Scan to download the app.")
                            .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).frame(width: 196)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 14) {
                Seam(label: "Get the app")
                numbered(1, "Point your phone's camera at the code and open the link.")
                numbered(2, "Android asks before installing from your browser: tap Settings, allow installs from this source, go back and tap Install.")
                numbered(3, "Open Hypernova, then tap I have the app here.")
                // The same link as the code (through the Mac's name when it has one).
                if let link = m.downloadURL {
                    Button("Copy download link") { m.copy(link) }.buttonStyle(LinkStyle()).padding(.top, 2)
                }
                if let anywhere = m.downloadAnywhereURL {
                    Button("Copy the link from anywhere (needs Tailscale on the phone)") { m.copy(anywhere) }.buttonStyle(LinkStyle())
                }
                if let on = m.nameSetting {
                    // The private name (the daemon's names.ts): on by default, made the first time a code is shown here.
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            HNSwitch(isOn: on, label: "Use a private name for this Mac") { m.setNameService($0) }
                            Text("Use a private name for this Mac").font(HNFont.sans(11)).foregroundStyle(Color.hnMuted)
                        }
                        Text("So your phone's browser trusts the download page. Hypernova gets a free certificate from Let's Encrypt for the name and accepts their [terms](https://letsencrypt.org/repository/) on your behalf. The name is public; this Mac's Wi‑Fi address is in DNS only while a code is shown.")
                            .font(HNFont.sans(10)).foregroundStyle(Color.hnDim).tint(Color.hnMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        if let note = m.nameNote {
                            Text(note).font(HNFont.sans(10)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(.top, 4)
                    // Shown: the daemon may make the name from now on (nothing is made before this line has been on screen).
                    .onAppear { m.noteNameTermsShown() }
                }
            }
            .frame(maxWidth: 300, alignment: .leading)
        }
    }

    private func numbered(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)").font(HNFont.mono(11, .medium)).foregroundStyle(Color.hnAccent).frame(width: 12, alignment: .leading)
            Text(text).font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
        }
    }

    private var pairView: some View {
        HStack(alignment: .top, spacing: 28) {
            ZStack {
                if let link = m.pair?.link {
                    QRCodeView(text: link, side: 196)
                } else {
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.hnBg800.opacity(0.6)).frame(width: 196, height: 196)
                        .overlay(PlanetMeter(activity: .live))
                }
            }
            VStack(alignment: .leading, spacing: 14) {
                TimelineView(.periodic(from: .now, by: 1)) { tl in
                    Seam(label: "Pairing", trail: countdown(tl.date), activity: .live)
                }
                HStack(spacing: 10) {
                    PlanetMeter(activity: .live)
                    Text("Waiting for your phone…").font(HNFont.small).foregroundStyle(Color.hnInk)
                }
                Text("Point your phone's camera at the code; it opens Hypernova and pairs. Or copy the link and use Paste link in the app.")
                    .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                if m.phoneBaseURL == nil {
                    // Pairing over the local network alone (owner's audit M4).
                    Text("Works on this Wi‑Fi. Sign in to Tailscale to reach this Mac from anywhere.")
                        .font(HNFont.small).foregroundStyle(Color.hnAmber.opacity(0.9)).fixedSize(horizontal: false, vertical: true)
                }
                if let e = m.pairError {
                    ErrorText(text: e)
                    Button("Try again") { m.startPairing(resetKnown: false) }.buttonStyle(LinkStyle())
                }
            }
            .frame(maxWidth: 300, alignment: .leading)
        }
    }

    private func countdown(_ now: Date) -> String? {
        guard let exp = m.pair?.expiresAt?.date else { return nil }
        let left = exp.timeIntervalSince(now)
        return left > 20 ? "refreshes in \(Format.countdown(left - 20))" : "refreshing"
    }

    private func paired(_ name: String) -> some View {
        HStack {
            Spacer()
            VStack(spacing: 6) {
                Ignition(size: 110)
                Text("Connected").hnLabel(.hnAccent)
            }
            Spacer()
        }
        .padding(.top, -10)
    }
}
