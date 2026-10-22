import AppKit
import SwiftUI

/// The menu bar popover: a small control room. The main page puts what needs you on top
/// (approvals you can answer right here), then your sessions (click one to open its terminal),
/// usage, and quick actions; Pair and Settings are pages of their own, so nothing that used to be
/// in the menu is more than one click away. A question before something that can't be undone
/// floats above it all as a glass card (Attention.swift).
struct MenuPopover: View {
    enum Page: Equatable { case main, pair, settings }

    @EnvironmentObject private var model: AppModel
    @StateObject private var pairing: PopoverPairing
    @State private var page: Page
    var dismiss: () -> Void
    /// Its height whenever it changes, so the panel around it can follow.
    var onHeight: (CGFloat) -> Void

    init(page: Page = .main, pairing: PopoverPairing? = nil, dismiss: @escaping () -> Void = {}, onHeight: @escaping (CGFloat) -> Void = { _ in }) {
        _page = State(initialValue: page)
        _pairing = StateObject(wrappedValue: pairing ?? PopoverPairing())
        self.dismiss = dismiss
        self.onHeight = onHeight
    }

    var body: some View {
        DialogHost(dialog: model.dialog, working: model.dialogWorking) {
            VStack(alignment: .leading, spacing: 0) {
                PopoverHeader(onConnection: { go(.settings) })
                    .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 14)
                ZStack(alignment: .top) {
                    switch page {
                    case .main: MainPage(go: go, dismiss: dismiss).transition(.opacity)
                    case .pair: PairPage(pairing: pairing, go: go, dismiss: dismiss).transition(.opacity)
                    case .settings: SettingsPage(go: go, dismiss: dismiss).transition(.opacity)
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 14)
                if let note = model.note {
                    NoteLine(text: note.text)
                        .padding(.horizontal, 16).padding(.bottom, 10)
                        .transition(.opacity)
                }
                PopoverFooter()
            }
        }
        .frame(width: StatusItemController.width)
        .fixedSize(horizontal: false, vertical: true)
        .background(GeometryReader { g in
            Color.clear
                .onAppear { onHeight(g.size.height) }
                .onChange(of: g.size.height) { _, h in onHeight(h) }
        })
        .background(PopoverBackdrop())
        .overlay(GrainView(opacity: 0.22).allowsHitTesting(false))
        .environment(\.colorScheme, .dark)
        .onAppear { if page == .pair { pairing.begin() } }
        .onDisappear { pairing.end(); model.closeDialog() }
    }

    private func go(_ p: Page) {
        if p == .pair { pairing.begin() } else if page == .pair { pairing.end() }
        withAnimation(.hnExpo(0.35)) { page = p }
    }
}

// MARK: - Chrome

/// Warm near-black glass: the blurred desktop (an NSVisualEffectView behind this) shows through a
/// dense tint, with the sky drifting faintly on top. Offscreen renders paint a stand-in desktop.
struct PopoverBackdrop: View {
    var body: some View {
        ZStack {
            if SnapshotMode.active { FauxDesktop(blurred: true) }
            LinearGradient(colors: [Color.hnBg.opacity(0.80), Color.hnBg.opacity(0.88)], startPoint: .top, endPoint: .bottom)
            // Faint: stars are atmosphere behind the text, never something to read as a dot in the UI.
            CosmosView(light: 0.5, labels: false, base: false)
                .opacity(0.42)
        }
        .accessibilityHidden(true)
    }
}

/// A dim, colourful desktop for offscreen renders, so the glass reads as glass.
struct FauxDesktop: View {
    var blurred = false
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x1E2A44), Color(hex: 0x3A2C4E), Color(hex: 0x1C3330)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Circle().fill(Color(hex: 0x4A6FA5).opacity(0.55)).frame(width: 260).offset(x: -120, y: -160)
            Circle().fill(Color(hex: 0xB0648A).opacity(0.35)).frame(width: 300).offset(x: 150, y: 120)
            Circle().fill(Color(hex: 0x3E8C7A).opacity(0.35)).frame(width: 220).offset(x: -60, y: 260)
        }
        .blur(radius: blurred ? 40 : 18)
    }
}

/// Brand, this Mac's name, and the connection as a quiet glass pill; under them, only while the
/// Mac's battery is low and not charging, one quiet amber line about it.
struct PopoverHeader: View {
    @EnvironmentObject private var model: AppModel
    var onConnection: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .center, spacing: 11) {
                if model.firstPollDone && !model.daemonUp {
                    NovaMark(size: 24, color: .hnDim, hollow: true)
                } else {
                    PlanetMeter(activity: activity, size: 24, stroke: 1.2, brand: true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    (Text("hypernova").foregroundColor(.hnMuted) + Text("/").foregroundColor(.hnAccent))
                        .font(HNFont.sans(17, .ultraLight)).tracking(-0.3)
                    Text(model.macName).font(HNFont.mono(10)).foregroundStyle(Color.hnDim).lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 8)
                ConnectionPill(action: onConnection)
            }
            if let b = model.lowBattery {
                // Under the name, in line with it: it's about this Mac.
                BatteryLine(battery: b).padding(.leading, 35)
                    .transition(.opacity)
            }
        }
        .animation(.hnExpo(0.35), value: model.lowBattery)
        .accessibilityElement(children: .contain)
    }

    private var activity: Activity {
        if model.needsYouCount > 0 || model.accountNeedsYou { return .alert }
        return model.workingCount > 0 || model.agentsCount > 0 ? .live : .rest
    }
}

/// "Your Mac is at 14%. Plug it in or its sessions stop.": the sessions run on this Mac, so a flat
/// battery ends them wherever you're watching from. One sentence, the same on the phone and the
/// glasses. Quiet amber text, no pill: a heads-up, not a task.
struct BatteryLine: View {
    var battery: Battery

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: battery.shown <= 10 ? "battery.0percent" : "battery.25percent")
                .font(.system(size: 11, weight: .regular))
                .foregroundStyle(Color.hnAmber.opacity(0.85))
                .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 3.5 }
            (Text(BatteryWords.lead(battery)).font(HNFont.sans(11.5, .semibold)).foregroundColor(.hnAmber.opacity(0.9))
             + Text(" " + BatteryWords.rest(battery)).font(HNFont.sans(11.5)).foregroundColor(.hnAmber.opacity(0.72)))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .help(help)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(BatteryWords.lead(battery) + " " + BatteryWords.rest(battery))
    }

    private var help: String {
        var parts: [String] = []
        if let m = battery.minutesLeft, m > 0 { parts.append("About \(Format.duration(m * 60)) left at this rate.") }
        if battery.onAC == true { parts.append("It's plugged in but not charging.") }
        parts.append("Your sessions run on this Mac, so they stop if it sleeps or shuts down.")
        return parts.joined(separator: " ")
    }
}

/// The battery sentence. Plugged in yet not charging (a charger too weak for it), it can still run
/// down, and "plug it in" would be no help, so the second half says what would be.
enum BatteryWords {
    static func lead(_ b: Battery) -> String { "Your Mac is at \(b.shown)%." }
    static func rest(_ b: Battery) -> String { b.onAC == true ? "Use a stronger charger or its sessions stop." : "Plug it in or its sessions stop." }
}

/// How this Mac is reachable, in the phone's connection chip's vocabulary: a point of light, the
/// route as a glyph (a globe for the tailnet, Wi‑Fi's fan for the local network), then the phones
/// paired as a phone glyph and their count, and the glasses as their glyph. No "·" between them.
struct ConnectionPill: View {
    @EnvironmentObject private var model: AppModel
    var action: () -> Void
    @State private var hover = false

    struct Look: Equatable {
        enum Route: Equatable { case tailnet, lan, none }
        var dot: Color
        var glow = false
        var route: Route = .none
        /// The word after the route: "tailnet", "Wi‑Fi", or the state when there's no route.
        var word: String
        var help: String
        var phones = 0
        var glasses = false
    }

    var body: some View {
        let l = look
        Button(action: action) {
            HStack(spacing: 6) {
                Circle().fill(l.dot).frame(width: 5, height: 5)
                    .shadow(color: l.dot.opacity(l.glow ? 0.9 : 0), radius: 3)
                switch l.route {
                case .tailnet: GlyphView(glyph: .globe, size: 11, color: ink.opacity(0.85))
                case .lan: GlyphView(glyph: .lan, size: 11, color: ink.opacity(0.85))
                case .none: EmptyView()
                }
                Text(l.word).font(HNFont.sans(11)).foregroundStyle(ink).lineLimit(1)
                if l.route != .none {
                    if l.phones > 0 {
                        HStack(spacing: 2) {
                            GlyphView(glyph: .phone, size: 11, color: ink.opacity(0.85))
                            Text("\(l.phones)").font(HNFont.mono(10.5)).foregroundStyle(ink)
                        }
                        .padding(.leading, 3)
                    }
                    if l.glasses { GlyphView(glyph: .glasses, size: 13, color: ink.opacity(0.85)) }
                }
            }
            .padding(.horizontal, 10).frame(height: 24)
            .background(Capsule().fill(LinearGradient(colors: [Color.hnBg700.opacity(0.62), Color.hnBg800.opacity(0.5)], startPoint: .top, endPoint: .bottom)))
            .overlay(alignment: .top) {
                LinearGradient(colors: [.clear, Color.hnInk.opacity(0.22), .clear], startPoint: .leading, endPoint: .trailing)
                    .frame(height: 1).padding(.horizontal, 8)
            }
            .overlay(Capsule().strokeBorder(Color.hnInk.opacity(hover ? 0.18 : 0.09), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .animation(.hnExpo(0.15), value: hover)
        .help(l.help)
        .accessibilityLabel("Connection: " + said(l))
        .accessibilityHint("Shows connection settings")
    }

    private var ink: Color { hover ? .hnInk : .hnMuted }

    private func said(_ l: Look) -> String {
        guard l.route != .none else { return l.word }
        var parts = [l.route == .tailnet ? "on the tailnet" : "on this Wi‑Fi"]
        parts.append(l.phones == 0 ? "no phone paired" : l.phones == 1 ? "1 phone paired" : "\(l.phones) phones paired")
        if l.glasses { parts.append("glasses paired") }
        return parts.joined(separator: ", ")
    }

    private var look: Look {
        if !model.firstPollDone { return Look(dot: .hnDim, word: "checking", help: "Checking the background service") }
        if !model.daemonUp { return Look(dot: .hnDanger, word: "service off", help: "The background service isn't answering") }
        guard let s = model.status, s.tailscale != nil, let action = model.tailscaleAction else { return Look(dot: .hnDim, word: "connecting", help: "Asking the background service") }
        let items = s.devices?.items ?? []
        let phones = items.filter { !$0.isLens }.count
        let glasses = items.contains { $0.isLens || $0.hasGlasses }
        // Phones and glasses online, without this app's own feed (the daemon counts it too).
        let online = model.clientsOnline
        let connected = online == 0 ? "No phone is connected right now." : online == 1 ? "1 connected right now." : "\(online) connected right now."
        func on(_ route: Look.Route, _ word: String, _ help: String, dot: Color = .hnAccent) -> Look {
            Look(dot: dot, glow: online > 0 && dot == .hnAccent, route: route, word: word, help: help, phones: phones, glasses: glasses)
        }
        switch action {
        // Hypernova's own node (no Tailscale app), the daemon's tsnet.ts.
        case .readyBuiltIn(let url, _, _, _):
            return on(.tailnet, "tailnet", "Hypernova's built-in Tailscale serves phone access at \(url). \(connected)")
        case .ready(let url):
            if let exp = model.keyExpiry {
                let expired = exp.timeIntervalSinceNow <= 0
                return on(.tailnet, expired ? "key expired" : "tailnet", "This Mac's Tailscale key \(expired ? "has expired" : "expires soon"). Phone access is at \(url). \(connected)", dot: .hnAmber)
            }
            return on(.tailnet, "tailnet", "Phone access is on at \(url). \(connected)")
        case .signInBuiltIn: return Look(dot: .hnAmber, word: "sign in to Tailscale", help: "Hypernova's built-in Tailscale needs your sign-in: open Setup for the code")
        case .builtInWaiting: return Look(dot: .hnDim, word: "Tailscale starting", help: "Hypernova's built-in Tailscale is starting")
        case .install, .openApp, .enableHTTPS, .turnOnServe:
            // No tailnet, but the local network's route: phones on this Wi‑Fi still reach it.
            if model.localRoute {
                return on(.lan, "Wi‑Fi", "Phones on this Wi‑Fi reach this Mac. Turn on Tailscale in Setup to reach it from anywhere. \(connected)", dot: .hnAccentDim)
            }
            switch action {
            case .install: return Look(dot: .hnAmber, word: "no Tailscale", help: "Tailscale isn't set up, so phones can't reach this Mac")
            case .openApp(let out): return Look(dot: .hnAmber, word: out ? "Tailscale signed out" : "Tailscale off", help: "Phones reach this Mac through Tailscale")
            default: return Look(dot: .hnAmber, word: "phone access off", help: "Phone access isn't on yet")
            }
        }
    }
}

/// The app's and the service's versions as two quiet figures a gap apart, and Quit.
struct PopoverFooter: View {
    @EnvironmentObject private var model: AppModel
    @State private var hover = false

    var body: some View {
        HStack(spacing: 14) {
            ForEach(figures, id: \.self) { f in
                Text(f).font(HNFont.mono(10)).foregroundStyle(Color.hnGhost).lineLimit(1)
            }
            Spacer(minLength: 8)
            Button { NSApp.terminate(nil) } label: {
                HStack(spacing: 7) {
                    Text("Quit").font(HNFont.sans(12)).foregroundStyle(hover ? Color.hnInk : Color.hnMuted)
                    Text("⌘Q").font(HNFont.mono(10)).foregroundStyle(Color.hnGhost)
                }
                .padding(.horizontal, 8).frame(height: 24)
                .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color.hnInk.opacity(hover ? 0.06 : 0)))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { hover = $0 }
            .keyboardShortcut("q")
            .help("Quit Hypernova. The background service keeps running, so your phone still reaches \(model.agents.names).")
        }
        .padding(.leading, 16).padding(.trailing, 8)
        .frame(height: 38)
        .overlay(alignment: .top) { Rectangle().fill(Color.hnLine.opacity(0.8)).frame(height: 1) }
    }

    private var figures: [String] {
        var parts: [String] = []
        if let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String { parts.append("app \(v)") }
        if let v = model.daemonVersion { parts.append("service \(v)") }
        // So a test copy is never mistaken for the real one.
        if Paths.testCopy { parts.append("test :\(Endpoints.localPort)") }
        return parts.isEmpty ? ["hypernova"] : parts
    }
}

/// Something that didn't work, said once at the foot of the popover.
struct NoteLine: View {
    var text: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            CrossIcon(size: 9).alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 3.5 }
            Text(text).font(HNFont.sans(12)).foregroundStyle(Color.hnDanger).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Main page

struct MainPage: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var voice = VoiceModel.shared
    @ObservedObject private var dismissed = DismissedNotices.shared
    var go: (MenuPopover.Page) -> Void
    var dismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if !model.firstPollDone {
                HStack(spacing: 10) {
                    PlanetMeter(activity: .live)
                    Text("Checking the background service…").font(HNFont.small).foregroundStyle(Color.hnMuted)
                }
                .padding(.vertical, 6)
            } else if !model.daemonUp {
                ServiceDownCard(dismiss: dismiss)
            } else {
                notices
                let items = model.needsYouItems
                if !items.isEmpty { NeedsYouSection(items: items, dismiss: dismiss) }
                SessionsSection(dismiss: dismiss)
                // Only with an agent to measure: Claude Code, or Codex in use (not a Mac with Codex left off and nothing else).
                if UsageSection.shows(model.agents) { UsageSection() }
                StayAwakeSection(dismiss: dismiss)
            }
            QuickActions(go: go, dismiss: dismiss)
        }
        // Voice may have been set up since the popover last looked (a Companion, a key from the phone).
        .onAppear { voice.loadIfStale() }
    }

    /// Attention cards, most useful first: an update, an unfinished setup, a Tailscale key about to
    /// expire, or the service refusing the app's key. Each can be swiped away (Undo for a moment).
    @ViewBuilder private var notices: some View {
        let shown = noticeList.filter { dismissed.shows($0) }
        if !shown.isEmpty || dismissed.undo != nil {
            VStack(spacing: 6) {
                ForEach(shown) { n in
                    AttentionCard(notice: n, onDismiss: n.busy ? nil : { dismissed.dismiss(n) })
                        .transition(.asymmetric(insertion: .opacity, removal: .opacity.combined(with: .move(edge: .trailing))))
                }
                UndoLine(dismissed: dismissed)
            }
            .animation(.hnExpo(0.35), value: shown.map(\.id))
        }
    }

    private var noticeList: [Notice] {
        var out: [Notice] = []
        if let e = model.lastError {
            out.append(Notice(id: "error", tone: .danger, title: "The service didn’t answer that", line: e, action: model.restarting ? "Restarting…" : "Restart", busy: model.restarting, key: e) { model.restartService() })
        }
        // A run waiting at an ask step: its answers as buttons, the default and its countdown in one line.
        // Swiped away, the Mac still takes the default when the time is up.
        for a in model.automationAsks {
            out.append(Notice(id: "ask-\(a.runId)", tone: .amber, title: a.question, line: a.name, key: a.runId,
                              figure: { WorkflowWords.askCountdown(a, now: $0) }, choices: a.answers, choose: { model.answerAsk(a, $0) }))
        }
        if model.serviceUpdate {
            out.append(Notice(id: "update", tone: .accent, title: "A newer service is ready", line: "Updating takes a few seconds. Shared terminals keep running.",
                              action: model.updatingService ? "Updating…" : "Update", busy: model.updatingService, key: "update") { model.updateService() })
        }
        // Claude Code's SDK didn't come with the service (offline at setup, say): only with Claude Code here.
        if model.sdkNeedsFetch {
            out.append(Notice(id: "sdk", tone: .amber, title: model.claudeSDK?.missing == false ? "Claude Code’s SDK is out of date" : "Claude Code’s SDK isn’t here yet",
                              line: "Sessions the service runs itself, the “/” list and Rewind need it (\(ClaudeSDK.downloadSize) from npm).",
                              action: model.fetchingSDK ? "Fetching…" : "Fetch again", busy: model.fetchingSDK,
                              help: ClaudeSDK.lastProblem ?? "Fetches it from npm into the background service, which keeps running.", key: model.claudeSDK?.wanted ?? "sdk") { model.fetchSDK() })
        }
        if let step = model.setupNext {
            switch step {
            case .phone:
                out.append(Notice(id: "setup", tone: .accent, title: "Pair your phone to get started", line: "Scan a code with Hypernova for Android.", action: "Pair", key: "phone") { go(.pair) })
            default:
                let what = switch step {
                case .connect: "Connect Claude Code to Hypernova."
                // A too-old Codex can't be turned on: updating it comes first.
                case .codex: model.codex?.tooOld == true ? "Update Codex with codex update, then turn it on in Setup." : "Turn on Codex, so your phone can start Codex sessions."
                case .claude: "Install Claude Code or Codex, so there’s an agent to work with."
                default: "Turn on phone access, so your phone reaches this Mac."
                }
                out.append(Notice(id: "setup", tone: .amber, title: step == .claude ? "No agent on this Mac yet" : "Setup isn’t finished", line: what, action: "Open", key: "\(step)") {
                    dismiss()
                    WindowManager.shared.showSetup(startAt: step)
                })
            }
        }
        // Set up before voice existed, or never answered: one quiet offer, until voice is ready or
        // someone says Not now in Setup. Only once the daemon has said whether voice is ready.
        if model.daemonUp, model.setupNext == nil, voice.loaded, !voice.ready, !Defaults.store.bool(forKey: Defaults.voiceAsked) {
            out.append(Notice(id: "voice", tone: .accent, title: "Talk to your sessions", line: "Hold a mic on your phone, say what to do, send.", action: "Set up",
                              help: "Hold a mic on your phone, say what to do, read it, send. Opens Setup at the Voice step.", key: "voice") {
                dismiss()
                WindowManager.shared.showSetup(startAt: .voice)
            })
        }
        if let exp = model.keyExpiry {
            let left = exp.timeIntervalSinceNow
            let title = left <= 0 ? "This Mac’s Tailscale key has expired" : "Tailscale key expires in \(Format.duration(left).components(separatedBy: " ").first ?? "")"
            out.append(Notice(id: "key", tone: .amber, title: title, line: "Turn key expiry off for this Mac, so your phone keeps the connection.", action: "Fix",
                              help: "Disable key expiry for this Mac in the Tailscale admin console, so your phone never loses the connection.", key: "\(Int(exp.timeIntervalSince1970 / 86_400))") {
                NSWorkspace.shared.open(Endpoints.tailscaleMachines)
                dismiss()
            })
        }
        return out
    }
}

struct ServiceDownCard: View {
    @EnvironmentObject private var model: AppModel
    var dismiss: () -> Void

    var body: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 10) {
                if let gone = model.serviceNodeGone {
                    // Its Node.js went (an nvm version uninstalled, say): a restart reuses the same
                    // launch agent, so only a repair helps.
                    Seam(label: "Service", trail: "Node.js gone", activity: .alert)
                    Text("The Node.js the service used is gone.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                    Text("It ran on \(Paths.tilde(gone)). Repair gets Node.js and starts the service again; shared terminals keep running.")
                        .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 8) {
                        PopoverPill(title: "Open setup", kind: .ghost, fill: false) { dismiss(); WindowManager.shared.showSetup(startAt: .service) }
                        PopoverPill(title: repairWords, kind: .primary, busy: model.repairing != nil, fill: false) { model.repairService() }
                    }
                    .padding(.top, 2)
                } else {
                    Seam(label: "Service", trail: model.serviceInstalled ? "not answering" : "not set up", activity: .alert)
                    Text(model.serviceInstalled ? "The background service isn't answering." : "Hypernova isn't set up on this Mac yet.")
                        .font(HNFont.heading).foregroundStyle(Color.hnInk)
                    Text(model.serviceInstalled ? "Your phone can't reach your sessions until it's back. Restarting usually fixes it; shared terminals keep running." : "Setup takes a few minutes and walks you through every step.")
                        .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 8) {
                        if model.serviceInstalled {
                            PopoverPill(title: "Open setup", kind: .ghost, fill: false) { dismiss(); WindowManager.shared.showSetup() }
                            PopoverPill(title: model.restarting ? "Restarting…" : "Restart service", kind: .primary, busy: model.restarting, fill: false) { model.restartService() }
                        } else {
                            PopoverPill(title: "Open setup", kind: .primary, fill: false) { dismiss(); WindowManager.shared.showSetup() }
                        }
                    }
                    .padding(.top, 2)
                }
            }
        }
    }

    private var repairWords: String {
        switch model.repairing {
        case nil: return "Repair"
        case .gettingNode(let f?)?: return "Getting Node.js \(Int(f * 100))%"
        case .gettingNode?: return "Getting Node.js…"
        default: return "Starting…"
        }
    }
}

// MARK: - Needs you

struct NeedsYouSection: View {
    @EnvironmentObject private var model: AppModel
    var items: [NeedsYouItem]
    var dismiss: () -> Void
    /// More than this and the popover gets long; the rest wait on the phone and in their terminals.
    private let shown = 3

    var body: some View {
        let waiting = items.filter { if case .waiting = $0 { return true } else { return false } }.count
        let visible = visibleItems
        VStack(alignment: .leading, spacing: 10) {
            Seam(label: "Needs you", trail: waiting == 0 ? "answered" : waiting == 1 ? "1 waiting" : "\(waiting) waiting", activity: waiting > 0 ? .alert : .rest)
            ForEach(visible) { item in
                Group {
                    switch item {
                    case .answered(let a): FoldedApproval(answered: a)
                    case .waiting(let a):
                        if a.isTool { ApprovalCard(approval: a, session: model.session(a.sessionId)) }
                        else { AskCard(approval: a, session: model.session(a.sessionId), dismiss: dismiss) }
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            let hidden = items.count - visible.count
            if hidden > 0 {
                Text("\(hidden) more waiting. Answer \(hidden == 1 ? "it" : "them") on your phone or in \(hidden == 1 ? "its terminal" : "their terminals").")
                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
            }
        }
        .animation(.hnExpo(0.45), value: items)
    }

    /// The first few waiting, plus any just answered (those go on their own in a moment).
    private var visibleItems: [NeedsYouItem] {
        var out: [NeedsYouItem] = []
        var waiting = 0
        for item in items {
            if case .waiting = item {
                if waiting >= shown { continue }
                waiting += 1
            }
            out.append(item)
        }
        return out
    }
}

// MARK: - Sessions

/// A seam whose trail is count marks ("● 1 needs you  ● 2 working"), or "all quiet".
struct CountSeam: View {
    var label: String
    var needs: Int
    var working: Int
    var activity: Activity

    var body: some View {
        let marks = SessionWords.counts(needs: needs, working: working)
        HStack(spacing: 10) {
            PlanetMeter(activity: activity)
            Text(label).hnLabel(activity == .alert ? .hnAmber : .hnMuted).lineLimit(1)
                .animation(.easeOut(duration: 0.9), value: activity)
                .layoutPriority(2)
            Rectangle().fill(Color.hnLine).frame(height: 1)
            if marks.isEmpty {
                Text("all quiet").font(HNFont.label).tracking(0.9).foregroundStyle(Color.hnDim).lineLimit(1).layoutPriority(1)
            } else {
                HStack(spacing: 12) {
                    ForEach(marks, id: \.text) { m in CountMark(text: m.text, color: m.needs ? .hnAmber : .hnAccent) }
                }
                .layoutPriority(1)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(marks.map(\.text).joined(separator: ", ") + ".")
            }
        }
    }
}

struct SessionsSection: View {
    @EnvironmentObject private var model: AppModel
    var dismiss: () -> Void
    private let shown = 6

    var body: some View {
        let list = model.sessions
        let needs = list.filter { model.needsYou($0) }.count
        let working = list.filter { $0.isBusy && !model.needsYou($0) }.count
        VStack(alignment: .leading, spacing: 6) {
            CountSeam(label: "Sessions", needs: needs, working: working, activity: needs > 0 ? .alert : working > 0 ? .live : .rest)
            if list.isEmpty {
                Text(AgentWords.nothingRunning(model.agents, shell: model.status?.shell?.installed == true))
                    .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
            } else {
                VStack(spacing: 1) {
                    ForEach(list.prefix(shown)) { s in SessionRow(session: s, dismiss: dismiss) }
                }
                .padding(.horizontal, -8)
                if list.count > shown {
                    // "on your phone" only once there is one to look on.
                    Text("+ \(list.count - shown) more" + (model.phonesPaired > 0 ? " on your phone" : "")).font(HNFont.mono(10)).foregroundStyle(Color.hnDim).padding(.top, 2)
                }
            }
            // One quiet line, only when automations exist: the next run, the one working, or paused.
            AutomationLine(dismiss: dismiss).padding(.horizontal, -8)
        }
    }
}

// MARK: - Usage

/// The 5-hour and 7-day meters (projection meters: what's used, where the pace leads by the reset,
/// one tag), then one for each model with a week of its own, its chip before "7 DAYS", with the
/// account's card above them when something's wrong with it (a limit, or signed out with Sign in),
/// and other providers' trouble or balance under them, as sentences.
///
/// The agents (AgentRule): Claude Code alone, as it always was. Codex in use beside it: Claude
/// Code's meters wear a CLAUDE chip and Codex's a CODEX chip, each named for its agent. Codex alone:
/// its meters plain, as Claude Code's are on their own, and what only you can fix about it is the
/// account's card.
struct UsageSection: View {
    @EnvironmentObject private var model: AppModel

    /// Something to measure: Claude Code, or Codex in use.
    static func shows(_ r: AgentRule) -> Bool { r.claude || r.codexInUse }

    var body: some View {
        let r = model.agents
        let u = r.claude ? model.usage : nil
        let codex = model.codexOn ? model.codex : nil
        let has = u?.fiveHour != nil || u?.sevenDay != nil
        // While the account is signed out or without a plan, the daemon keeps the last numbers but
        // can't measure new ones.
        let stale = r.claude && u?.source == "stale"
        VStack(alignment: .leading, spacing: 12) {
            Seam(label: "Usage", trail: trail(r, has: has, stale: stale, codex: codex), activity: model.accountNeedsYou ? .alert : .rest)
            TimelineView(.periodic(from: .now, by: 30)) { tl in
                VStack(alignment: .leading, spacing: 12) {
                    if r.claude {
                        if let a = model.account, let line = AccountLine.content(a, now: tl.date, signingIn: model.signingIn, named: r.both) {
                            AccountLine(content: line)
                        }
                        if stale {
                            Text(staleText).font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
                        } else {
                            if !has {
                                Text("Waiting for the first numbers from Claude Code.").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
                            }
                            ForEach(Self.meters(u, now: tl.date, named: r.both), id: \.reading.name) { m in Meter(label: m.label, chip: m.chip, reading: m.reading) }
                            // The probe couldn't read /usage lately: the per-model weeks are the last good ones.
                            if u?.probe?.ok == false, !(u?.models ?? []).isEmpty {
                                Text("Per-model weeks not read since \(u?.probe?.since.map { Clock.clock($0.date, tl.date) } ?? "a while").")
                                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim)
                            }
                        }
                    }
                    // Codex's own windows, only while it's in use (a Claude-only Mac sees none of this).
                    if let c = codex {
                        if r.codexOnly { CodexUsage(codex: c, now: tl.date, lead: true) }
                        else if CodexUsage.shows(c, now: tl.date) { CodexUsage(codex: c, now: tl.date) }
                    }
                    // Other providers have no windows: their trouble as a sentence, what's left on
                    // them after a banknote (ProviderWords). They're Claude Code's.
                    if r.claude {
                        ForEach(otherProviders, id: \.id) { p in
                            HStack(spacing: 8) {
                                if let st = p.status {
                                    Text(ProviderWords.sentence(p.name, st, now: tl.date)).font(HNFont.sans(12)).foregroundStyle(st.needsYou ? Color.hnAmber.opacity(0.9) : Color.hnMuted).lineLimit(1)
                                } else {
                                    Text(p.name).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(1)
                                }
                                Spacer(minLength: 8)
                                if let f = ProviderWords.figure(p) { GlyphFigure(glyph: .note, text: f, color: .hnMuted, size: 11) }
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
            }
        }
    }

    /// The seam's trail: Claude Code's state where it's here (not measured while signed out,
    /// waiting for the first numbers); with Codex alone, Codex's.
    private func trail(_ r: AgentRule, has: Bool, stale: Bool, codex: Codex?) -> String? {
        if r.claude { return stale ? staleTrail : has ? nil : "waiting for numbers" }
        guard let c = codex else { return nil }
        if CodexWords.trouble(c) != nil { return nil }
        if CodexWords.paysPerUse(c) { return "billed per use" }
        return Self.codexMeters(c, now: Date()).isEmpty && c.usage?.limited != true ? "waiting for numbers" : nil
    }

    /// The meters, in order: the 5-hour and 7-day windows (with the pace, the faint fill, only while
    /// a session works: the daemon sends none otherwise), then the weeks only /usage shows, read by
    /// the daemon every 15 minutes (every 2 while something works): the model's chip before "7
    /// days", plain (no pace is measured for them), their reset a weekday and time more than about
    /// a day off. [named]: Codex is in use too, so the windows wear Claude's chip and name (a model's
    /// week already names its model). Android: ui/home/ModelWeekMeters.kt.
    static func meters(_ u: Usage?, now: Date, named: Bool = false) -> [(label: String, chip: String?, reading: MeterReading)] {
        guard let u else { return [] }
        var out: [(label: String, chip: String?, reading: MeterReading)] = []
        if u.fiveHour != nil || u.sevenDay != nil {
            let rate = u.rate, seen = u.observedAt?.date
            let chip: String? = named ? "Claude" : nil
            out.append(("5 hours", chip, MeterReading(name: named ? "Claude 5-hour window" : "5-hour window", window: u.fiveHour, pace: rate?.fiveHour, etaMs: rate?.fiveHourEtaMs,
                                                      limitAt: rate?.fiveHourLimitAt?.date, observedAt: seen, now: now)))
            out.append(("7 days", chip, MeterReading(name: named ? "Claude weekly window" : "Weekly window", window: u.sevenDay, pace: rate?.sevenDay, etaMs: rate?.sevenDayEtaMs,
                                                     limitAt: rate?.sevenDayLimitAt?.date, observedAt: seen, now: now)))
        }
        for m in u.models ?? [] {
            out.append(("7 days", m.label, MeterReading(name: "\(m.label) week", window: UsageWindow(pct: m.pct, resetsAt: m.resetsAt), now: now, weekday: true)))
        }
        return out
    }

    /// Codex's windows as the same meters, its chip before the label ("[CODEX] 5 HOURS"), as a model's
    /// own week wears its chip: only the windows its plan has (a plan may have only the week, an API key
    /// none). No pace: the daemon measures none for Codex. [chip]: false where Codex is the only agent,
    /// whose meters stand alone as Claude Code's do.
    static func codexMeters(_ c: Codex, now: Date, chip: Bool = true) -> [(label: String, chip: String?, reading: MeterReading)] {
        guard let u = c.usage else { return [] }
        var out: [(label: String, chip: String?, reading: MeterReading)] = []
        let tag: String? = chip ? "Codex" : nil
        if let w = u.fiveHour { out.append(("5 hours", tag, MeterReading(name: "Codex 5-hour window", window: w, observedAt: u.observedAt?.date, now: now))) }
        if let w = u.sevenDay { out.append(("7 days", tag, MeterReading(name: "Codex weekly window", window: w, observedAt: u.observedAt?.date, now: now))) }
        return out
    }

    /// Other providers worth a line: in trouble, or with a balance to show.
    private var otherProviders: [Provider] {
        model.providers.filter { $0.builtIn != true && ($0.status != nil || $0.balance?.words != nil) }
    }

    private var staleTrail: String {
        switch model.account?.state {
        case "logged_out": return "while signed out"
        case "no_subscription": return "without a plan"
        case "on_hold": return "while on hold"
        default: return "not measured"
        }
    }

    private var staleText: String {
        switch model.account?.state {
        case "logged_out": return "Not measured while signed out."
        case "no_subscription": return "Not measured without a plan."
        case "on_hold": return "Not measured while the account is on hold."
        default: return "Not measured right now."
        }
    }
}

/// Codex under USAGE, while it's in use. Beside Claude Code: what stops it as one sentence beside its
/// chip (an update, its app-server out of reach, signed out), else its windows as meters, and one quiet
/// line when it's at a limit that no meter shows at 100%. [lead]: Codex is the only agent here, so it's
/// shown as Claude Code is on its own: meters without a chip, and what only you can fix (signed out,
/// too old) as the account's card, with what to do.
struct CodexUsage: View {
    var codex: Codex
    var now: Date
    var lead = false

    /// Something to show beside Claude Code: trouble, a window, or a limit (an API key has no windows: nothing).
    static func shows(_ c: Codex, now: Date) -> Bool {
        CodexWords.trouble(c) != nil || !UsageSection.codexMeters(c, now: now).isEmpty || c.usage?.limited == true
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let t = CodexWords.trouble(codex) {
                if lead, t.waitsOnYou {
                    CodexAccountCard(codex: codex, trouble: t)
                } else {
                    HStack(spacing: 8) {
                        if !lead { TagChip(text: "Codex") }
                        Text(t.text).font(HNFont.sans(12)).foregroundStyle(t.waitsOnYou ? Color.hnAmber.opacity(0.9) : Color.hnMuted).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .help(t.help)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Codex: \(t.text). \(t.help)")
                }
            } else {
                let meters = UsageSection.codexMeters(codex, now: now, chip: !lead)
                if lead, meters.isEmpty, codex.usage?.limited != true {
                    Text(CodexWords.paysPerUse(codex) ? "Codex is on an API key: no windows, each turn is billed to it." : "Waiting for the first numbers from Codex.")
                        .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(meters, id: \.reading.name) { m in Meter(label: m.label, chip: m.chip, reading: m.reading) }
                if let line = CodexWords.limited(codex, meters: meters.map(\.reading)) {
                    Text(line).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted)
                }
            }
        }
    }
}

/// What only you can fix about Codex, as the account's card where Codex is the only agent: signed
/// out (Sign in opens a terminal window running `codex login`), or too old (Setup's Codex step has
/// the line to run). Hypernova never signs in or updates Codex by itself.
private struct CodexAccountCard: View {
    @ObservedObject private var dismissed = DismissedNotices.shared
    var codex: Codex
    var trouble: (text: String, waitsOnYou: Bool, help: String)

    var body: some View {
        let n = notice
        if dismissed.shows(n) {
            AttentionCard(notice: n, onDismiss: { dismissed.dismiss(n) })
        }
    }

    private var notice: Notice {
        if codex.tooOld {
            return Notice(id: "codex-account", tone: .amber, title: trouble.text,
                          line: codex.min.map { "Hypernova needs Codex \($0) or later. Run codex update." } ?? "Run codex update in a terminal.",
                          action: "Open", help: trouble.help + " Setup's Codex step has the line to copy.", key: "too-old/\(codex.version ?? "")",
                          run: { WindowManager.shared.showSetup(startAt: .codex) })
        }
        return Notice(id: "codex-account", tone: .amber, title: trouble.text, line: "Its sessions can’t reply until you sign in.", action: codex.path == nil ? nil : "Sign in",
                      help: "Codex is signed out on this Mac. Sign in opens a terminal window running codex login, and your browser.", key: "signed-out",
                      run: { AppModel.shared.openCodexSignIn() })
    }
}

// MARK: - Quick actions

struct QuickActions: View {
    @EnvironmentObject private var model: AppModel
    var go: (MenuPopover.Page) -> Void
    var dismiss: () -> Void

    var body: some View {
        let checklist = Self.showsChecklist
        HStack(spacing: 8) {
            // A phone paired already: the next is "another" (each code pairs one device).
            ActionTile(symbol: "iphone.gen3", title: model.phonesPaired == 0 ? "Pair a phone" : checklist ? "Pair another" : "Pair another phone") { go(.pair) }
                .disabled(!model.daemonUp)
                .help("Show a pairing code. Each code works once, and every time you get a new one, so you can pair more than one device.")
            if checklist {
                ActionTile(symbol: "checklist", title: "Checklist") {
                    NSWorkspace.shared.open(Endpoints.qa)
                    dismiss()
                }
                .disabled(!model.daemonUp)
                .help("The testing checklist, in your browser")
            }
            ActionTile(symbol: "gearshape", title: "Settings") { go(.settings) }
                .help("Connection, phones, the background service, agents and providers, and setup")
        }
    }

    /// The testing checklist is the owner's: a tile only for a developer (`defaults write
    /// com.prudnikweb.hypernova developer -bool true`) or once checklist results exist on this Mac.
    static let showsChecklist: Bool = {
        if Defaults.store.bool(forKey: Defaults.developer) { return true }
        let qa = Paths.support.appendingPathComponent("qa", isDirectory: true)
        return ((try? FileManager.default.contentsOfDirectory(atPath: qa.path)) ?? []).contains { !$0.hasPrefix(".") }
    }()
}
