import AppKit
import ServiceManagement
import SwiftUI
import SystemConfiguration

/// App-wide state for the menu bar. Three reads from the daemon: health (is it answering), the
/// snapshot (sessions, approvals, usage, the account, the Mac's battery: what the icon and the
/// popover show) and /local/status (Tailscale, phones, Claude Code integration).
///
/// The snapshot is kept current by the daemon's live feed (LiveFeed): each change arrives as it
/// happens, so the icon starts turning the moment a session works, and nothing is polled for it.
/// With the feed up, everything is read again once a minute while the popover is closed (a safety
/// net) and every 6 s while it's open. Without it (the service is down, or an old one), the old
/// rhythm: the snapshot every 1.5 s open and every 5 s closed, the status every 6 s and 30 s.
///
/// The feed is the daemon talking, so while it's live the daemon is up: a health read that fails
/// then (a busy moment) changes nothing on screen and is tried again every 2 s. That holds only
/// while the feed has heard the daemon in the last 30 s (it pings every 25 s): a hung daemon keeps
/// its socket open but says nothing, so it shows as down within about half a minute, and the feed
/// starts over.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var health: Health?
    @Published private(set) var status: LocalStatus?
    @Published private(set) var snapshot: Snapshot?
    @Published private(set) var lastError: String?
    @Published private(set) var serviceInstalled = FileManager.default.fileExists(atPath: Paths.launchAgent.path)
    /// This copy of the app carries a newer background service than the one installed.
    @Published private(set) var serviceUpdate = false
    @Published private(set) var firstPollDone = false
    @Published var restarting = false
    @Published var updatingService = false
    @Published var loginItemEnabled = !Paths.testCopy && SMAppService.mainApp.status == .enabled
    /// The daemon's live feed is connected.
    @Published private(set) var feedLive = false
    /// "Sign in" was pressed and the daemon hasn't answered yet.
    @Published private(set) var signingIn = false
    /// Approvals answered from the menu bar. They show folded ("Allowed · git push") for a moment,
    /// and stay out of the waiting list until the daemon has caught up.
    @Published private(set) var answered: [String: Answered] = [:]
    /// A short line at the foot of the popover when something didn't work; it fades on its own.
    @Published private(set) var note: Note?
    /// Stay awake: the mode just chosen here, shown at once while the daemon takes it (AppModel+Awake).
    @Published var awakeChoice: Awake.Mode?
    /// Backpack's one-time setup (or its removal) is under way.
    @Published var backpackBusy = false
    /// Automations: the switch just turned here, shown at once while the daemon takes it (AppModel+Automations).
    @Published var automationsPausedChoice: Bool?
    /// `/local/devices` as Settings last read it (glasses through a phone included); nil until then.
    @Published private(set) var deviceList: [Device]?
    /// The plist's Node.js is gone (removed with nvm, say): launchd can't start the service, and a
    /// restart can't either. ServiceDownCard offers Repair.
    @Published private(set) var serviceNodeGone: String?
    /// Repair is under way (Node.js, the plist, the start), with the phase it's in.
    @Published private(set) var repairing: ServiceInstaller.Progress?
    /// Claude Code's SDK in the installed service (ClaudeSDK), read with the status; nil until then.
    @Published private(set) var claudeSDK: ClaudeSDK.State?
    /// Fetch again is under way.
    @Published private(set) var fetchingSDK = false
    /// A question floating above the popover (Forget a phone, Uninstall), or the word after it.
    @Published var dialog: GlassDialog?
    /// The dialog's primary action is under way.
    @Published var dialogWorking = false

    struct Answered: Equatable { var approval: Approval; var decision: String; var at: Date }
    struct Note: Equatable { var text: String; var id = UUID() }
    /// How long an answered approval stays on screen, folded.
    static let foldSeconds: TimeInterval = 1.6

    private var loop: Task<Void, Never>?
    /// The loop's wait between rounds. Cancelling it wakes the loop early; a read already on its
    /// way is left to finish, since cancelling one would leave its answer missing (or "cancelled"
    /// on screen) until the next round.
    private var nap: Task<Void, Never>?
    /// The loop's next round reads at once, and the status with it.
    private var readNow = false, statusNow = false
    /// When the loop last read, and last read the status.
    private var lastRead = Date.distantPast, lastStatus = Date.distantPast
    /// Health reads have failed since then while the feed vouched for the daemon: they're tried
    /// again every 2 s, not at the feed's slow rhythm.
    private var failingSince: Date?
    /// Reads under way, so the feed doesn't ask for another while one is coming.
    private var reading = 0
    /// When a feed message last asked for a read (at most one every 2 s).
    private var lastNudge = Date.distantPast
    private lazy var feed = LiveFeed { [weak self] e in self?.feedEvent(e) }
    private var feedStarted = false
    /// Counts the feed's messages, so a slower read of the whole snapshot never overwrites
    /// something newer the feed brought while it was on its way.
    private var feedGeneration = 0
    /// The feed was connected when the status on hand was read, so the daemon counted it among
    /// its clients.
    private var statusCountsFeed = false
    /// Set for offscreen renders: sample data, no polling.
    private var sample = false
    var popoverVisible = false {
        // Opened: read everything at once, so what it shows is fresh. Closed: only the slower
        // rhythm from now on.
        didSet {
            if popoverVisible != oldValue { wake(read: popoverVisible, status: popoverVisible) }
            if popoverVisible && !oldValue { freshenUsage(); cameBack() }
        }
    }

    // MARK: Derived

    /// The service answers health reads, or its live feed is talking (it's the same process).
    var daemonUp: Bool { health != nil || feedLive }
    var macName: String {
        if let n = snapshot?.mac?.name, !n.isEmpty { return n }
        if let n = health?.name, !n.isEmpty { return n }
        return (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "This Mac"
    }
    var daemonVersion: String? { status?.daemonVersion ?? snapshot?.mac?.daemonVersion }

    /// Live sessions: the ones that need you, then working, then the rest, newest first. One that
    /// went on with the other agent (`switchedTo`) is left off, as on the phone: its successor shows.
    var sessions: [Session] {
        (snapshot?.sessions?.items ?? status?.sessions?.items ?? [])
            .filter { $0.isLive && $0.switchedTo == nil }
            .sorted { a, b in a.rank != b.rank ? a.rank < b.rank : (a.lastActivityAt?.date ?? .distantPast) > (b.lastActivityAt?.date ?? .distantPast) }
    }
    func session(_ id: String?) -> Session? {
        guard let id else { return nil }
        return (snapshot?.sessions?.items ?? status?.sessions?.items ?? []).first { $0.id == id }
    }
    /// Waiting approvals, oldest first, without the ones just answered here.
    var approvals: [Approval] {
        (snapshot?.approvals?.items ?? status?.approvals?.items ?? [])
            .filter { answered[$0.id] == nil }
            .sorted { ($0.createdAt?.date ?? .distantPast) < ($1.createdAt?.date ?? .distantPast) }
    }
    /// What the NEEDS YOU section shows: waiting approvals and, briefly, the ones just answered.
    var needsYouItems: [NeedsYouItem] {
        let now = Date()
        let folded = answered.values.filter { now.timeIntervalSince($0.at) < Self.foldSeconds }.map { NeedsYouItem.answered($0) }
        // What can be answered right here first, then questions and plans; oldest first within each.
        func key(_ i: NeedsYouItem) -> (Int, Date) { (i.approval.isTool ? 0 : 1, i.approval.createdAt?.date ?? .distantPast) }
        return (approvals.map { NeedsYouItem.waiting($0) } + folded).sorted { key($0) < key($1) }
    }
    /// The approval a session waits on, the oldest first (its row says what it wants).
    func approval(for s: Session) -> Approval? {
        let ids = Set(s.pendingApprovalIds ?? [])
        return approvals.first { ids.contains($0.id) } ?? approvals.first { $0.sessionId == s.id }
    }
    /// A live session needs you: it says so, or an approval of it waits here.
    func needsYou(_ s: Session) -> Bool { s.state == "needs_you" || approval(for: s) != nil }
    /// Sessions that need you (the menu bar's count and the SESSIONS seam's amber mark).
    var needsYouCount: Int {
        let waiting = Set(approvals.compactMap(\.sessionId))
        // Approvals of sessions this Mac doesn't list (yet) count one each.
        let orphans = approvals.filter { a in a.sessionId.map { id in !sessions.contains { $0.id == id } } ?? true }.count
        return sessions.filter { $0.state == "needs_you" || waiting.contains($0.id) }.count + orphans
    }
    /// The working rule, one everywhere (owner, 2026-10-18): a live session is working while Claude
    /// works in it (or is starting) or any of its agents run; needing you outranks it.
    var workingCount: Int { sessions.filter { $0.isBusy && !needsYou($0) }.count }
    /// Agents at work across live sessions, whether or not their main agent is: the menu bar's
    /// planet turns for them too (they're shown on the rows, not counted on the seam).
    var agentsCount: Int { sessions.reduce(0) { $0 + $1.agentsBusy } }
    var usage: Usage? { snapshot?.usage ?? status?.usage }
    /// Claude Code's account (nil from a daemon older than the account contract).
    var account: Account? { snapshot?.account ?? status?.account }
    /// Codex as a second agent (docs/PROTOCOL.md, "Codex"): nil when it isn't installed here. The
    /// snapshot's word (kept current by the feed) once there is one, else the status's.
    var codex: Codex? { snapshot != nil ? snapshot?.codex : status?.codex }
    /// Codex is on for Hypernova: only then do its windows (or what stops it, an update say) show,
    /// so a Claude-only Mac, or one with Codex off, looks as before.
    var codexOn: Bool { daemonUp && codex?.enabled == true }
    /// Claude Code is installed here. Any sign of it is enough: the snapshot says so, `/local/status`
    /// has its path, its hooks are in (Connect was done), a session of its own is known, or Setup's
    /// own look found it. Its absence has to be proven: the daemon says it's missing *and* this app's
    /// own look (ClaudeDetector, run once the daemon says so) found nothing either. A daemon older
    /// than Codex says nothing, and Claude Code always was there then. So a Mac with Claude Code
    /// never loses its meters or nags to one lookup that missed it.
    var claudeAvailable: Bool {
        if snapshot?.claude?.available == true || status?.claude?.path != nil || status?.hooks?.installed == true { return true }
        if (snapshot?.sessions?.items ?? status?.sessions?.items ?? []).contains(where: { !$0.isCodex }) { return true }
        if !sample, SetupModel.shared.claude.path != nil { return true }
        guard daemonSaysNoClaude else { return true }
        // Offscreen renders and self-tests take the daemon's word.
        return !(sample || claudeLook?.found == false)
    }
    /// The daemon says Claude Code isn't here (the snapshot's `claude.available`, or the status's path).
    private var daemonSaysNoClaude: Bool { snapshot?.claude?.available == false || (status?.claude != nil && status?.claude?.path == nil) }
    /// This app's own look for Claude Code, taken when the daemon says it's missing (at most every 10 minutes).
    @Published private var claudeLook: (found: Bool, at: Date)?
    private var claudeLooking = false
    /// Looks for Claude Code itself when the daemon says it's missing, so absence is proven twice.
    private func lookForClaudeIfNeeded() {
        guard !sample, daemonSaysNoClaude, !claudeLooking, claudeLook.map({ Date().timeIntervalSince($0.at) > 600 }) ?? true else { return }
        claudeLooking = true
        Task {
            let found = await Task.detached { ClaudeDetector.detect().path != nil }.value
            claudeLook = (found, Date())
            claudeLooking = false
        }
    }
    /// Which agents this Mac has and uses, and which one leads (Core/Models.swift, AgentRule): the
    /// one rule behind the usage meters' names, the nags and the tooltip.
    var agents: AgentRule { AgentRule(claude: claudeAvailable, codex: daemonUp ? codex : nil) }
    /// What only you can fix about the leading agent's account, in the tooltip's words: Claude Code
    /// signed out, without a plan or on hold; on a Mac with Codex alone, Codex signed out or too old.
    /// Nil when nothing waits on you (a limit lifts by itself).
    var accountTrouble: String? {
        guard daemonUp else { return nil }
        let r = agents
        if r.claude, let a = account, a.needsYou { return AgentWords.claudeTrouble(a.state) ?? "the account needs you" }
        if r.codexOnly, let c = codex, let t = CodexWords.trouble(c), t.waitsOnYou { return t.text }
        return nil
    }
    /// The leading agent can't reply until you act, so the menu bar's dot lights.
    var accountNeedsYou: Bool { accountTrouble != nil }
    /// Claude Code's own account needs you (Sign in, Renew): only where Claude Code is installed.
    var claudeAccountNeedsYou: Bool { daemonUp && claudeAvailable && account?.needsYou == true }
    /// The service runs without the Claude Code SDK it pins, on a Mac with Claude Code: Fetch again.
    /// With Codex alone nothing uses it, so this stays false.
    var sdkNeedsFetch: Bool { daemonUp && serviceInstalled && claudeAvailable && claudeSDK.map { !$0.current } == true }
    /// This Mac's battery when it's low and not charging (the header says so); nil otherwise, on a
    /// Mac without a battery, and while the service is down (its last word may be old by then).
    var lowBattery: Battery? {
        guard daemonUp, let b = snapshot?.mac?.battery, b.low else { return nil }
        return b
    }
    /// Phones and glasses connected right now. The daemon counts this app's own feed among its
    /// clients, so that one is left out when it was connected as the status was read.
    var clientsOnline: Int { max(0, (status?.connectedClients ?? 0) - (statusCountsFeed ? 1 : 0)) }
    /// Paired phones and glasses, most recently seen first: `/local/devices` as Settings last read it
    /// (it lists glasses that come through a phone too), else the status's.
    var devices: [Device] {
        let list = deviceList ?? status?.devices?.items ?? []
        return list.sorted { ($0.lastSeenAt?.date ?? .distantPast) > ($1.lastSeenAt?.date ?? .distantPast) }
    }
    /// Phones paired (glasses aren't phones).
    var phonesPaired: Int { (status?.devices?.items ?? []).filter { !$0.isLens }.count }
    /// What the Tailscale step would ask for next, the built-in node counted: Hypernova's own node
    /// serving is phone access as much as the Tailscale app's (the daemon's tsnet.ts).
    var tailscaleAction: TailscaleAction? { status?.tailscale.map { TailscaleStepLogic.action($0, tsnet: status?.tsnet) } }
    /// Where phones reach this Mac: the Tailscale app's serve address, or the built-in node's.
    var phoneBaseURL: String? { status?.tailscale?.baseURL ?? status?.tsnet?.url }
    /// The local network's listener is on: a phone on the same Wi‑Fi can pair without a tailnet.
    var localRoute: Bool { (status?.lan?.addresses?.isEmpty == false) }
    /// The Tailscale app's key expiry within 14 days, while the app is the one serving.
    var keyExpiry: Date? { status?.tailscale.flatMap { TailscaleStepLogic.expiringSoon($0, tsnet: status?.tsnet) } }

    /// The first setup step that isn't done, once /local/status says so (nil while it's unknown).
    /// Connecting Claude Code is asked for only where Claude Code is installed; on a Mac with Codex
    /// alone, turning Codex on is; with neither agent, installing one is.
    var setupNext: SetupModel.Step? {
        guard daemonUp, let s = status else { return nil }
        let r = agents
        // A test copy leaves Claude Code's settings and Tailscale to the real install, so it never
        // asks for these.
        if r.claude {
            if s.hooks?.installed != true, !Paths.testCopy { return .connect }
        } else if r.codexInstalled {
            if !r.codexInUse { return .codex }
        } else if !Paths.testCopy {
            return .claude
        }
        // Phone access through Tailscale (the app or the built-in node); without it, the local
        // network's route is enough to pair on the same Wi‑Fi, so that isn't nagged about.
        if !Paths.testCopy, s.tailscale != nil, !TailscaleStepLogic.isOn(tailscaleAction), !localRoute { return .tailscale }
        if (s.devices?.count ?? 0) == 0 { return .phone }
        return nil
    }

    // MARK: Polling

    func start() {
        // A login item belongs to one copy of the app; after a move, register this copy again.
        // (Never for a test copy: the login item is the owner's.)
        if !Paths.testCopy, Defaults.store.bool(forKey: Defaults.loginItemWanted), Bundle.main.bundleURL.pathExtension == "app",
           SMAppService.mainApp.status != .enabled {
            try? SMAppService.mainApp.register()
            loginItemEnabled = SMAppService.mainApp.status == .enabled
        }
        feedStarted = true
        feed.start()
        startLoop()
        // Back from the Terminal a sign-in opened: Codex's account is read again.
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.cameBack() }
        }
    }

    /// Seconds between the loop's reads, as things stand now.
    private var roundInterval: Double {
        if failingSince != nil { return 2 }
        return feedLive ? (popoverVisible ? 6 : 60) : (popoverVisible ? 1.5 : 5)
    }
    private var statusInterval: Double { popoverVisible ? 6 : feedLive ? 60 : 30 }

    /// When the loop reads next: at the round's rhythm, or sooner, once, when the live feed has
    /// been quiet for 30 s (the daemon pings every 25 s). So a daemon that hangs with its socket
    /// open shows as down in about half a minute even while the popover is closed, rather than at
    /// the minute's safety-net read.
    private var nextRead: Date {
        let due = lastRead.addingTimeInterval(roundInterval)
        guard feedLive, let quiet = feed.quietFor else { return due }
        let silent = Date().addingTimeInterval(30 - quiet)
        return lastRead < silent ? min(due, silent) : due
    }

    /// One loop for the app's life: it reads when a round is due, or at once when woken for it,
    /// and in between waits a nap that a change of rhythm can cut short.
    private func startLoop() {
        guard !sample, loop == nil else { return }
        readNow = true
        statusNow = true
        loop = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if self.readNow || Date() >= self.nextRead {
                    let withStatus = self.statusNow || Date() >= self.lastStatus.addingTimeInterval(self.statusInterval)
                    self.readNow = false
                    if withStatus { self.statusNow = false }
                    await self.refresh(status: withStatus)
                    self.lastRead = Date()
                    if withStatus { self.lastStatus = self.lastRead }
                    continue
                }
                let wait = max(0.05, self.nextRead.timeIntervalSinceNow)
                let nap = Task { _ = try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
                self.nap = nap
                await nap.value
            }
        }
    }

    /// Wakes the loop: to read at once (the popover opened, the feed dropped), or only so its next
    /// wait follows the new rhythm. A read already on its way carries on either way.
    private func wake(read: Bool, status: Bool = false) {
        if read { readNow = true }
        if status { statusNow = true }
        nap?.cancel()
    }

    // MARK: The live feed

    private func feedEvent(_ e: LiveFeed.Event) {
        guard !sample else { return }
        switch e {
        case .live(let on):
            guard on != feedLive else { return }
            feedLive = on
            // Up: the feed brings a snapshot of its own, so the next read can wait (a read under
            // way still lands). Down: find out at once whether the service went away.
            if !on { failingSince = nil }
            wake(read: !on)
            // The Lens guide catches up on what it may have missed while the feed was down.
            if on { LensGuideModel.shared.feedUp() }
        case .message(let m):
            switch m {
            case .guide, .guideShow, .stt:
                // Not the menu bar's: the Lens guide window's (App/LensGuide.swift).
                LensGuideModel.shared.heard(m)
            case .devices:
                // A phone or the glasses paired: the device list and the connection pill show it
                // now, not at the next status read.
                wake(read: true, status: true)
            default:
                feedGeneration &+= 1
                apply(m)
            }
            proofOfLife()
        }
    }

    /// While health reads fail, the feed still vouches for the daemon if it heard it in the last
    /// 30 s: the daemon pings every 25 s, and the 5 s past that are room for a ping that comes late
    /// (a busy event loop, the case this grace is for). Any longer, and a hung daemon would look up
    /// until the feed's own watchdog gave up, a minute or more later.
    private func feedVouches() -> Bool {
        guard let quiet = feed.quietFor else { return false }
        return quiet < 30
    }

    /// A message from the feed is the daemon talking. If the last reads came back empty (a failed
    /// health read, a snapshot that didn't arrive), read again now rather than at the next round.
    private func proofOfLife() {
        guard health == nil || snapshot == nil, reading == 0, Date().timeIntervalSince(lastNudge) >= 2 else { return }
        lastNudge = Date()
        wake(read: true)
    }

    private func apply(_ m: LiveFeed.Message) {
        var s: Snapshot
        switch m {
        case .snapshot(let full):
            s = full
        default:
            // A change before the first snapshot: the read proofOfLife asks for brings it.
            guard let current = snapshot else { return }
            s = current
        }
        switch m {
        case .snapshot: break
        case .session(let x):
            var items = s.sessions?.items ?? []
            if let i = items.firstIndex(where: { $0.id == x.id }) { items[i] = x } else { items.append(x) }
            s.sessions = FlexList(items)
        case .approval(let a):
            var items = s.approvals?.items ?? []
            if !items.contains(where: { $0.id == a.id }) { items.append(a) }
            s.approvals = FlexList(items)
        case .resolved(let id):
            if s.approvals?.items.contains(where: { $0.id == id }) == true {
                s.approvals = FlexList((s.approvals?.items ?? []).filter { $0.id != id })
            }
        case .usage(let u): s.usage = u
        case .account(let a): s.account = a
        case .battery(let b):
            var mac = s.mac ?? Snapshot.Mac()
            mac.battery = b
            s.mac = mac
        case .awake(let a):
            var mac = s.mac ?? Snapshot.Mac()
            mac.awake = a
            s.mac = mac
        case .providers(let p): s.providers = FlexList(p)
        case .codex(let c): s.codex = c
        case .guide, .guideShow, .stt, .devices:
            // Routed elsewhere in feedEvent; nothing in the snapshot.
            return
        }
        if s != snapshot { snapshot = s }
        pruneAnswered()
    }

    /// One round of reads. Each value is only replaced when it changed, so an unchanged daemon
    /// never redraws the popover or the icon.
    func refresh(status withStatus: Bool = true) async {
        guard !sample else { return }
        reading += 1
        defer { reading -= 1 }
        let h = await DaemonClient.shared.health()
        let installed = FileManager.default.fileExists(atPath: Paths.launchAgent.path)
        if installed != serviceInstalled { serviceInstalled = installed }
        if h == nil, feedLive {
            if feedVouches() {
                // The feed is the daemon talking, so this is a busy moment, not the service going
                // away (if it went, the feed drops too, and the read that follows clears
                // everything). Keep what's on screen and look again in 2 s (with the status, if
                // this round was to read it).
                if withStatus { statusNow = true }
                if failingSince == nil { failingSince = Date(); wake(read: false) }
                pruneAnswered()
                if !firstPollDone { firstPollDone = true }
                return
            }
            // The feed has gone quiet too: the daemon hangs with its socket open, or the
            // connection is a dead one. It's down, as without a feed (Restart shows), and the feed
            // starts over, so it's live again only once the daemon talks.
            feedLive = false
            feed.start()
        }
        if failingSince != nil { failingSince = nil }
        // The service is back: connect the feed now rather than at its next try.
        if h != nil, health == nil, firstPollDone, feedStarted, !feedLive { feed.start() }
        if h != health { health = h }
        if h != nil {
            do {
                let generation = feedGeneration
                let snap = try await DaemonClient.shared.snapshot()
                // The feed may have brought something newer while this was on its way; it wins.
                if generation == feedGeneration, snap != snapshot { snapshot = snap }
                if lastError != nil { lastError = nil }
            } catch {
                let m = error.localizedDescription
                if m != lastError { lastError = m }
            }
            if withStatus || status == nil {
                let counted = feedLive
                if let s = try? await DaemonClient.shared.status(), s != status || counted != statusCountsFeed {
                    statusCountsFeed = counted
                    status = s
                }
                // The feed came or went while the status was on its way, so its client count may
                // or may not include it: read the status again rather than guess for a minute.
                if counted != feedLive { wake(read: true, status: true) }
            }
            if withStatus {
                let (update, sdk) = await Task.detached { (ServiceInstaller.updateAvailable(), ClaudeSDK.state()) }.value
                if update != serviceUpdate { serviceUpdate = update }
                if !fetchingSDK, sdk != claudeSDK { claudeSDK = sdk }
            }
        } else {
            if snapshot != nil { snapshot = nil }
            if status != nil { status = nil }
            if lastError != nil { lastError = nil }
        }
        lookForClaudeIfNeeded()
        // Down with its Node.js gone (removed with an nvm version, say): a restart can't help.
        let gone = h == nil && installed ? await Task.detached { ServiceInstaller.missingNode() }.value : nil
        if gone != serviceNodeGone { serviceNodeGone = gone }
        pruneAnswered()
        if !firstPollDone { firstPollDone = true }
    }

    // MARK: Approvals

    /// Answers from the menu bar, at once: the card folds while the answer travels. If the daemon
    /// says it was already answered (on the phone or in the terminal), that's fine too.
    func answer(_ a: Approval, _ decision: String) {
        guard answered[a.id] == nil else { return }
        withAnimation(.hnExpo(0.4)) { answered[a.id] = Answered(approval: a, decision: decision, at: Date()) }
        Task {
            do {
                try await DaemonClient.shared.answerApproval(a.id, decision: decision)
            } catch DaemonError.server(let code, _, _) where code == 409 {
                // Answered elsewhere first.
            } catch {
                withAnimation(.hnExpo(0.4)) { answered[a.id] = nil }
                show("That answer didn’t reach \(agentName(of: a)). \(error.localizedDescription)")
                return
            }
            await refresh(status: false)
            try? await Task.sleep(nanoseconds: UInt64(Self.foldSeconds * 1_000_000_000) + 50_000_000)
            // Let the folded line go.
            withAnimation(.hnExpo(0.5)) { objectWillChange.send() }
            pruneAnswered()
        }
    }

    /// The agent an approval waits in: its session's, else the one that leads here.
    func agentName(of a: Approval) -> String {
        if let s = session(a.sessionId) { return s.isCodex ? "Codex" : "Claude Code" }
        return agents.leading == .codex ? "Codex" : "Claude Code"
    }

    /// Forgets answered approvals once they've been shown and the daemon no longer lists them.
    private func pruneAnswered() {
        guard !answered.isEmpty else { return }
        let listed = Set((snapshot?.approvals?.items ?? []).map(\.id))
        let now = Date()
        let keep = answered.filter { _, v in
            let age = now.timeIntervalSince(v.at)
            return age < Self.foldSeconds || (listed.contains(v.approval.id) && age < 30)
        }
        if keep.count != answered.count { answered = keep }
    }

    // MARK: Sessions

    /// Opens a shared session's terminal on this Mac (or brings its window forward).
    func openInTerminal(_ s: Session, then done: (() -> Void)? = nil) {
        Task {
            do {
                _ = try await DaemonClient.shared.openSession(s.id)
                done?()
            } catch {
                show("Couldn’t open its terminal. \(error.localizedDescription)")
            }
        }
    }

    /// A session stopped while you were away: tell it you're back.
    /// The providers as the daemon last said (their trouble and balance).
    var providers: [Provider] { snapshot?.providers?.items ?? [] }

    /// Turn a session's carrying on by itself after its limit on or off.
    func autoContinue(_ s: Session, on: Bool) {
        Task {
            do {
                try await DaemonClient.shared.autoContinue(s.id, on: on)
                await refresh(status: false)
            } catch {
                show("Didn’t go through. \(error.localizedDescription)")
            }
        }
    }

    /// Ask another provider again now (its balance and state; at most once in 10 s on the daemon's side).
    func checkProvider(_ p: Provider) {
        Task {
            do {
                _ = try await DaemonClient.shared.checkProvider(p.id)
                await refresh(status: false)
            } catch {
                show("Couldn’t reach \(p.name). \(error.localizedDescription)")
            }
        }
    }

    /// Carry a session on after a limit it didn't continue from by itself.
    func continueAfterLimit(_ s: Session) {
        Task {
            do {
                try await DaemonClient.shared.continueAfterLimit(s.id)
                await refresh(status: false)
            } catch {
                show("Couldn’t continue it. \(error.localizedDescription)")
            }
        }
    }

    func continueSession(_ s: Session) {
        Task {
            do {
                try await DaemonClient.shared.continueSession(s.id)
                await refresh(status: false)
            } catch {
                show("Couldn’t continue it. \(error.localizedDescription)")
            }
        }
    }

    // MARK: Account

    /// Signs Claude Code in again: the daemon opens `claude auth login` in a terminal window here,
    /// and the browser takes it from there. The account line follows the sign-in as it goes.
    func signIn() {
        guard !signingIn else { return }
        signingIn = true
        Task {
            do {
                if let a = try await DaemonClient.shared.startLogin(), var s = snapshot, s.account != a {
                    s.account = a
                    snapshot = s
                }
            } catch DaemonError.server(let code, _, _) where code == 409 {
                // Already fine again (the check ran meanwhile): the next read says so.
                await refresh(status: false)
            } catch {
                show("Couldn’t start signing in. \(error.localizedDescription)")
            }
            signingIn = false
        }
    }

    func forget(_ d: Device) {
        Task {
            do {
                try await DaemonClient.shared.removeDevice(d.id)
                await refresh()
                await loadDevices()
            } catch {
                show("Couldn’t forget \(d.displayName). \(error.localizedDescription)")
            }
        }
    }

    /// Reads `/local/devices` (phones, and glasses that come through one) for Settings' list.
    func loadDevices() async {
        guard !sample else { return }
        if let list = try? await DaemonClient.shared.devices(), list != deviceList { deviceList = list }
    }

    // MARK: Usage

    private var lastUsageAsk = Date.distantPast
    /// The popover opened: with no numbers yet, or numbers more than 5 minutes old, the daemon is
    /// asked to read them now (at most once in 5 minutes) rather than wait for Claude Code's next
    /// reply. Its answer, and the feed's, land in the snapshot.
    func freshenUsage(now: Date = Date()) {
        guard !sample, daemonUp, now.timeIntervalSince(lastUsageAsk) >= 300 else { return }
        let u = usage
        if u?.source == "stale" { return }
        let has = u?.fiveHour != nil || u?.sevenDay != nil
        let old = u?.observedAt.map { now.timeIntervalSince($0.date) > 300 } ?? true
        guard !has || old else { return }
        lastUsageAsk = now
        Task {
            if let fresh = try? await DaemonClient.shared.refreshUsage(), var s = snapshot, s.usage != fresh {
                s.usage = fresh
                snapshot = s
            }
        }
    }

    // MARK: Codex

    /// "Use Codex with Hypernova" turned here (the Agents & providers window) and not yet taken by
    /// the daemon: the switch shows it at once.
    @Published private(set) var codexBusy = false
    /// What went wrong turning Codex on or off, or opening its sign-in, for the Agents & providers
    /// window to say (the popover's note line isn't on screen there).
    @Published private(set) var codexError: String?
    /// The Terminal running `codex login` was opened: when you come back (the app active again, the
    /// popover opened), Codex's account is read again at once.
    private var codexSignInOpened = false

    /// Codex on or off for Hypernova (`POST /local/settings {codex}`): the daemon connects to Codex's
    /// own app-server, or lets go of it. Shown at once, then as the daemon has it. Nothing is
    /// installed or signed in from here.
    func setCodex(_ on: Bool) {
        guard !codexBusy else { return }
        let before = (status, snapshot)
        codexError = nil
        if var s = status, var c = s.codex { c.enabled = on; s.codex = c; status = s }
        if var s = snapshot, var c = s.codex { c.enabled = on; s.codex = c; snapshot = s }
        guard !sample else { return }
        codexBusy = true
        Task {
            do {
                try await DaemonClient.shared.setCodex(enabled: on)
            } catch {
                // Back to how it was, and said where it was turned.
                (status, snapshot) = before
                codexError = "Couldn’t turn Codex \(on ? "on" : "off"). \(error.localizedDescription)"
                show(codexError ?? "")
            }
            codexBusy = false
            await refresh()
        }
    }

    /// Codex looked at again now: its account and limits (`POST /v1/codex/refresh`, only while it's
    /// on), then the status (installed, its version).
    func checkCodex() {
        guard !sample else { return }
        Task {
            if codex?.enabled == true { _ = try? await DaemonClient.shared.refreshCodex() }
            await refresh()
            if codex?.account?.signedIn == true { codexSignInOpened = false }
        }
    }

    /// Opens a Terminal window running `codex login` (your browser takes it from there), off the main
    /// thread; Codex's account is read again when you come back.
    func openCodexSignIn() {
        guard let path = codex?.path else { return }
        codexError = nil
        codexSignInOpened = true
        Task {
            let failure: String? = await Task.detached {
                do { try CodexDetector.openSignInTerminal(codexPath: path); return nil } catch { return error.localizedDescription }
            }.value
            if let failure {
                codexSignInOpened = false
                codexError = failure
                show(failure)
            }
        }
    }

    /// Back from somewhere (the app active, the popover opened): after a Codex sign-in was opened,
    /// read its account now rather than at the daemon's next look.
    private func cameBack() {
        if codexSignInOpened { checkCodex() }
    }

    // MARK: Phone access

    /// Hypernova's own Tailscale node on or off (the daemon's `builtInTailscale` setting). Off with
    /// the Tailscale app on this Mac hands phone access back to the app; on, the node starts unless
    /// the app already serves.
    func setBuiltInTailscale(_ on: Bool, then: (() -> Void)? = nil) {
        Task {
            do {
                try await DaemonClient.shared.setBuiltInTailscale(on)
                if let s = try? await DaemonClient.shared.status(fresh: true), s != status { status = s }
                then?()
            } catch {
                show("Couldn’t change that. \(error.localizedDescription)")
            }
        }
    }

    // MARK: Files

    /// Browse outside projects (the daemon's `browseOutsideProjects`): sessions' folders in the home
    /// folder open to paired phones, read-only. Shown at once, then as the daemon has it.
    func setBrowseOutsideProjects(_ on: Bool) {
        if var s = status {
            s.files = LocalStatus.Files(browseOutsideProjects: on)
            status = s
        }
        guard !sample else { return }
        Task {
            do {
                try await DaemonClient.shared.setBrowseOutsideProjects(on)
            } catch {
                show("Couldn’t change Browse outside projects. \(error.localizedDescription)")
            }
            if let s = try? await DaemonClient.shared.status(), s != status { status = s }
        }
    }

    /// Signs the built-in node out (another account, or a fresh start): Setup shows the new sign-in.
    func signOutBuiltIn() {
        Task {
            _ = try? await DaemonClient.shared.tsnetLogout()
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            await refresh()
        }
    }

    /// Stay awake as the daemon just answered it (AppModel+Awake), ahead of the feed's own word.
    func applyAwake(_ a: Awake) {
        guard var s = snapshot else { return }
        var mac = s.mac ?? Snapshot.Mac()
        guard mac.awake != a else { return }
        mac.awake = a
        s.mac = mac
        snapshot = s
    }

    func closeDialog() {
        guard !dialogWorking else { return }
        withAnimation(.hnExpo(0.3)) { dialog = nil }
    }

    func show(_ text: String) {
        let n = Note(text: text)
        withAnimation(.hnExpo(0.3)) { note = n }
        Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            if note == n { withAnimation(.hnExpo(0.5)) { note = nil } }
        }
    }

    // MARK: Service

    /// Restarts the LaunchAgent (kickstart), or loads it if launchd forgot it.
    func restartService() {
        guard !restarting else { return }
        if let why = Paths.serviceBlocked { show(why); return }
        restarting = true
        Task {
            await Task.detached {
                if !ServiceInstaller.kickstart() {
                    try? ServiceInstaller.bootstrap()
                }
            }.value
            _ = await DaemonClient.shared.waitForHealth(seconds: 20)
            await refresh()
            restarting = false
        }
    }

    /// The service's Node.js is gone: get one (a stable one, or Hypernova's own download), write the
    /// launch agent again and start it. Shared terminals keep running in their own holders.
    func repairService() {
        guard repairing == nil else { return }
        if let why = Paths.serviceBlocked { show(why); return }
        repairing = .gettingNode(nil)
        Task {
            do {
                _ = try await SetupActions.installService { p in Task { @MainActor in AppModel.shared.repairing = p } }
            } catch {
                show("The repair didn’t finish. \(error.localizedDescription)")
            }
            repairing = nil
            await refresh()
        }
    }

    /// Fetch again: Claude Code's SDK into the running service (ClaudeSDK), said in a line when it fails.
    func fetchSDK() {
        guard !fetchingSDK else { return }
        fetchingSDK = true
        Task {
            let o = await SetupActions.fetchClaudeSDK()
            claudeSDK = await Task.detached { ClaudeSDK.state() }.value
            fetchingSDK = false
            if let why = o.problem { show("Claude Code’s SDK didn’t download. \(why)") }
            SetupModel.shared.sdk = claudeSDK
            SetupModel.shared.sdkProblem = o.problem
        }
    }

    /// Installs the newer service this copy of the app carries (Setup's "Update"). Shared
    /// terminals keep running through it, and Open at login stays as it was.
    func updateService() {
        guard !updatingService else { return }
        updatingService = true
        Task {
            do {
                _ = try await SetupActions.installService()
                loginItemEnabled = !Paths.testCopy && SMAppService.mainApp.status == .enabled
            } catch {
                show("The update didn’t finish. \(error.localizedDescription)")
            }
            await refresh()
            updatingService = false
        }
    }

    // MARK: Offscreen renders

    /// For `--render-popover` and `--snapshots`: show given data and never touch the daemon.
    func loadSample(health: Health?, snapshot: Snapshot?, status: LocalStatus?, serviceInstalled: Bool = true, serviceUpdate: Bool = false, answered: [Answered] = [],
                    nodeGone: String? = nil, dialog: GlassDialog? = nil, deviceList: [Device]? = nil) {
        sample = true
        loop?.cancel()
        self.health = health
        self.snapshot = snapshot
        self.status = status
        self.serviceInstalled = serviceInstalled
        self.serviceUpdate = serviceUpdate
        self.serviceNodeGone = nodeGone
        self.dialog = dialog
        self.deviceList = deviceList
        self.answered = Dictionary(uniqueKeysWithValues: answered.map { ($0.approval.id, $0) })
        firstPollDone = true
    }

    /// For `--snapshots` only: show the "service down" state without stopping anything.
    func simulateServiceDownForSnapshot() { loadSample(health: nil, snapshot: nil, status: nil) }

    // MARK: Login item

    func setLoginItem(_ on: Bool) {
        // The login item belongs to the owner's copy.
        guard !Paths.testCopy else { return }
        Defaults.store.set(on, forKey: Defaults.loginItemWanted)
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            NSLog("Hypernova: login item change failed: \(error.localizedDescription)")
        }
        loginItemEnabled = SMAppService.mainApp.status == .enabled
    }

    // MARK: Uninstall

    /// Asks first, in a glass card above the popover (the owner's dialog decision), then removes
    /// the integration, the LaunchAgent and (optionally) all data, and says so in the same card,
    /// with Quit.
    func askUninstall() {
        if let why = Paths.serviceBlocked { show(why); return }
        let builtIn = status?.tsnet?.mode == "builtIn"
        let r = agents
        withAnimation(.hnExpo(0.3)) {
            dialog = GlassDialog(
                eyebrow: "Uninstall", title: "Uninstall Hypernova?",
                line: AgentWords.uninstallLine(r),
                more: AgentWords.uninstallMore(r) + (builtIn ? " Hypernova's own Tailscale node stays in your tailnet until you remove it in the admin console." : ""),
                accent: .hnDanger, secondary: "Cancel", primary: "Uninstall", busy: "Uninstalling…",
                option: "Also delete Hypernova's data: paired phones, providers, the downloaded Node.js, logs",
                confirm: { [weak self] deleteData in self?.runUninstall(deleteData: deleteData) },
                cancel: { [weak self] in self?.closeDialog() })
        }
    }

    private func runUninstall(deleteData: Bool) {
        guard !dialogWorking else { return }
        dialogWorking = true
        Task {
            let notes = await uninstall(deleteData: deleteData)
            dialogWorking = false
            withAnimation(.hnExpo(0.3)) {
                dialog = GlassDialog(
                    eyebrow: "Uninstall", title: notes.isEmpty ? "Hypernova is uninstalled." : "Hypernova is mostly uninstalled.",
                    line: "You can move Hypernova.app to the Trash now.",
                    more: notes.isEmpty ? nil : notes.joined(separator: "\n\n"),
                    accent: .hnAccent, secondary: nil, primary: "Quit Hypernova",
                    confirm: { _ in NSApp.terminate(nil) })
            }
        }
    }

    func uninstall(deleteData: Bool) async -> [String] {
        let notes = await SetupActions.uninstall(deleteData: deleteData, inApp: true)
        loginItemEnabled = !Paths.testCopy && SMAppService.mainApp.status == .enabled
        await refresh()
        return notes
    }
}

/// One line in NEEDS YOU: an approval waiting, or one just answered here (folded).
enum NeedsYouItem: Identifiable, Equatable {
    case waiting(Approval)
    case answered(AppModel.Answered)
    var approval: Approval { switch self { case .waiting(let a): return a; case .answered(let x): return x.approval } }
    var id: String { approval.id }
}

enum Defaults {
    /// Where these live: a test copy keeps its own, so finishing or uninstalling it never changes
    /// what the owner's copy remembers.
    static let store: UserDefaults = Paths.testCopy ? (UserDefaults(suiteName: "com.prudnikweb.hypernova.test") ?? .standard) : .standard
    static let setupCompleted = "setupCompleted"
    static let moveOfferDeclined = "moveToApplicationsDeclined"
    static let loginItemWanted = "loginItemWanted"
    /// Setup's Voice step was answered with "Not now" (or left through Continue): the popover stops offering voice.
    static let voiceAsked = "voiceAsked"
    /// Setup's Codex step was answered with "Not now": it's done in the rail, and Setup no longer opens there.
    static let codexAsked = "codexAsked"
    /// "Use Codex only" on Setup's Claude Code step (a Mac without Claude Code): that step and Connect are skipped.
    static let claudeSkipped = "claudeSkipped"
    /// A developer's Mac: the popover keeps the testing checklist's tile (`defaults write
    /// com.prudnikweb.hypernova developer -bool true`).
    static let developer = "developer"
}
