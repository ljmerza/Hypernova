import AppKit
import SwiftUI

/// Provider profiles for Hypernova sessions (docs/PROTOCOL.md "Providers"). Keys go to the daemon
/// and are never shown again: the list only knows whether one is saved.
@MainActor
final class ProvidersModel: ObservableObject {
    /// Offscreen renders: given providers, never the daemon.
    private var sample = false

    init() {}
    init(sample providers: [Provider]) {
        sample = true
        self.providers = providers
        loading = false
    }

    struct Draft: Equatable {
        var id: String?
        var kind: String
        var name = ""
        var baseUrl = ""
        var apiKey = ""
        var def = "", opus = "", sonnet = "", haiku = ""
        var hasKey = false
        var isNew: Bool { id == nil }
    }

    @Published var providers: [Provider] = []
    @Published var loading = true
    @Published var error: String?
    @Published var draft: Draft?
    @Published var saving = false
    @Published var formError: String?
    /// After a new key was saved: what the daemon's read of it said, on that provider's card ("Key
    /// works. $4.20 left."), until the next update or 15 s; the provider's live state wins over it.
    @Published var verdict: [String: (text: String, amber: Bool)] = [:]
    /// The key field of the editor is marked: the key was refused.
    @Published var keyRefused = false
    /// Open this provider's editor once the list has loaded (Fix key from the popover).
    var editOnLoad: String?
    /// Providers whose balance is being read now (the Check button).
    @Published var checking: Set<String> = []

    static let openRouterURL = "https://openrouter.ai/api"

    func load(checkBalances: Bool = false, keepVerdict: Bool = false) {
        guard !sample else { return }
        loading = providers.isEmpty
        Task {
            do {
                providers = try await DaemonClient.shared.providers()
                error = nil
                // A verdict lasts until the next update.
                if !keepVerdict { verdict = [:] }
                if let id = editOnLoad, let p = providers.first(where: { $0.id == id }) { editOnLoad = nil; edit(p) }
            } catch {
                self.error = error.localizedDescription
            }
            loading = false
            // What's left on each OpenRouter key, read again as the window opens (quietly).
            if checkBalances { for p in providers where p.kind == "openrouter" && p.hasKey == true { check(p, quiet: true) } }
        }
    }

    /// Read a provider's balance now (OpenRouter), then show it.
    func check(_ p: Provider, quiet: Bool = false) {
        guard !checking.contains(p.id) else { return }
        checking.insert(p.id)
        Task {
            do {
                if let fresh = try await DaemonClient.shared.checkProvider(p.id), let i = providers.firstIndex(where: { $0.id == fresh.id }) { providers[i] = fresh }
            } catch {
                if !quiet { self.error = error.localizedDescription }
            }
            checking.remove(p.id)
        }
    }

    func newOpenRouter() { formError = nil; withAnimation(.hnExpo(0.4)) { draft = Draft(kind: "openrouter", name: "OpenRouter", baseUrl: Self.openRouterURL) } }
    func newCustom() { formError = nil; withAnimation(.hnExpo(0.4)) { draft = Draft(kind: "custom") } }
    func edit(_ p: Provider) {
        formError = nil
        keyRefused = false
        checkLine = nil
        withAnimation(.hnExpo(0.4)) {
            draft = Draft(id: p.id, kind: p.kind, name: p.name, baseUrl: p.baseUrl ?? "", apiKey: "",
                          def: p.models?.default ?? "", opus: p.models?.opus ?? "", sonnet: p.models?.sonnet ?? "", haiku: p.models?.haiku ?? "",
                          hasKey: p.hasKey ?? false)
        }
    }
    func cancel() { keyRefused = false; checkLine = nil; withAnimation(.hnExpo(0.4)) { draft = nil } }

    /// The editor's Check: the address and key as they stand, checked without saving.
    @Published var checkLine: (text: String, ok: Bool)?
    @Published var checkingDraft = false
    func checkDraft() {
        guard let d = draft, !checkingDraft else { return }
        let key = d.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let input = ProviderInput(name: d.name.trimmingCharacters(in: .whitespacesAndNewlines), kind: d.kind,
                                  baseUrl: d.kind == "anthropic" ? nil : d.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines),
                                  apiKey: key.isEmpty ? nil : key, models: ProviderModels())
        checkingDraft = true
        checkLine = nil
        Task {
            do {
                if let c = try await DaemonClient.shared.checkProviderInput(input, id: d.id) {
                    checkLine = (c.text ?? (c.state == "ok" ? "It works." : "It didn’t work."), c.state == "ok")
                    keyRefused = c.state == "bad_key"
                }
            } catch DaemonError.server(let status, _, _) where status == 404 {
                checkLine = ("This version of the background service can’t check before saving.", false)
            } catch {
                checkLine = (error.localizedDescription, false)
            }
            checkingDraft = false
        }
    }

    func save() {
        guard let d = draft else { return }
        let name = d.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = d.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = d.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { formError = "Give it a name."; return }
        if d.kind != "anthropic" {
            guard let u = URL(string: base), let scheme = u.scheme, ["http", "https"].contains(scheme), u.host != nil else {
                formError = "The base URL should look like https://example.com/api."; return
            }
        }
        if d.isNew, d.kind == "openrouter", key.isEmpty { formError = "Paste your OpenRouter API key."; return }
        func t(_ s: String) -> String? { let v = s.trimmingCharacters(in: .whitespacesAndNewlines); return v.isEmpty ? nil : v }
        let input = ProviderInput(name: name, kind: d.kind, baseUrl: d.kind == "anthropic" ? nil : base, apiKey: key.isEmpty ? nil : key,
                                  models: ProviderModels(default: t(d.def), opus: t(d.opus), sonnet: t(d.sonnet), haiku: t(d.haiku)))
        saving = true
        formError = nil
        Task {
            do {
                let answer = try await DaemonClient.shared.saveProvider(d.id, input)
                let saved = answer.provider
                // The address moved to another host without a new key: the old key was dropped.
                if answer.keyDropped == true, let p = saved {
                    keyRefused = true
                    formError = "Paste the key for the new address."
                    draft?.id = p.id
                    draft?.hasKey = false
                    saving = false
                    load(keepVerdict: true)
                    return
                }
                // What the daemon's check of a new key or address said (a newer daemon), kept in
                // the editor when the key was refused or nothing answers there as an API.
                if let c = answer.check, let p = saved {
                    if c.state == "bad_key" || c.state == "not_found" {
                        keyRefused = c.state == "bad_key"
                        formError = c.text ?? (c.state == "bad_key" ? "\(p.name) refused this key. Check it and paste it again." : "Nothing answers there as an Anthropic-compatible API.")
                        draft?.id = p.id
                        draft?.hasKey = p.hasKey ?? true
                        saving = false
                        load(keepVerdict: true)
                        return
                    }
                    if let t = c.text { verdict[p.id] = (t, c.state != "ok") }
                    let id = p.id
                    Task { [weak self] in
                        try? await Task.sleep(nanoseconds: 15_000_000_000)
                        self?.verdict[id] = nil
                    }
                } else if !key.isEmpty, let p = saved {
                    if p.status?.state == "bad_key" || p.balance?.error == "bad_key" {
                        // Saved, but refused: the editor stays open with the key marked, now
                        // editing what was saved (a second Save doesn't add another).
                        keyRefused = true
                        formError = "\(p.name) refused this key. Check it and paste it again."
                        draft?.id = p.id
                        draft?.hasKey = true
                        saving = false
                        load(keepVerdict: true)
                        return
                    }
                    if let v = ProviderWords.verdict(p) { verdict[p.id] = v }
                    else if p.kind == "openrouter" { verdict[p.id] = ("Saved. Couldn’t reach \(p.name) to check the key.", true) }
                    let id = p.id
                    Task { [weak self] in
                        try? await Task.sleep(nanoseconds: 15_000_000_000)
                        self?.verdict[id] = nil
                    }
                }
                keyRefused = false
                withAnimation(.hnExpo(0.4)) { draft = nil }
                load(keepVerdict: true)
            } catch {
                formError = error.localizedDescription
            }
            saving = false
        }
    }

    /// The question before Delete, as a glass card above the window (the owner's dialog decision).
    @Published var dialog: GlassDialog?

    func delete(_ p: Provider) {
        withAnimation(.hnExpo(0.3)) {
            dialog = GlassDialog(
                eyebrow: "Delete a provider", title: "Delete \(p.name)?",
                line: "Its saved key is deleted too.",
                more: "Sessions already running on it keep going until they end.",
                accent: .hnDanger, secondary: "Keep", primary: "Delete",
                confirm: { [weak self] _ in
                    withAnimation(.hnExpo(0.3)) { self?.dialog = nil }
                    Task { @MainActor [weak self] in
                        do { try await DaemonClient.shared.deleteProvider(p.id); self?.load() } catch { self?.error = error.localizedDescription }
                    }
                },
                cancel: { [weak self] in withAnimation(.hnExpo(0.3)) { self?.dialog = nil } })
        }
    }
}

/// The Agents & providers window: the agents first (Claude Code and Codex, what's installed, how
/// each is signed in, and Codex's switch), then Claude Code's providers (the list and its editor).
struct ProvidersView: View {
    @StateObject private var m: ProvidersModel
    @ObservedObject private var app: AppModel
    @MainActor init(model: ProvidersModel? = nil, app: AppModel? = nil) {
        _m = StateObject(wrappedValue: model ?? ProvidersModel())
        _app = ObservedObject(wrappedValue: app ?? .shared)
    }

    var body: some View {
        CosmosBackdrop(light: 0.85) {
            DialogHost(dialog: m.dialog) { content }
        }
        .onAppear {
            m.load(checkBalances: true)
            // What's installed and how it's signed in, read now rather than at the next round
            // (Codex's account too: POST /v1/codex/refresh while it's on).
            app.checkCodex()
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: 46)
                Text(m.draft == nil ? "Agents & providers." : (m.draft!.isNew ? (m.draft!.kind == "openrouter" ? "Add OpenRouter." : "Add a provider.") : "Edit \(m.draft!.name)."))
                    .font(HNFont.sans(28)).tracking(-0.4).foregroundStyle(Color.hnInk)
                Text(m.draft == nil
                     ? "Hypernova works with Claude Code and OpenAI’s Codex. Claude Code’s sessions can also run on other model providers: each is a set of Claude Code settings, its key kept on this Mac and never shown again."
                     : m.draft!.kind == "openrouter"
                        ? "Use models from OpenRouter in Claude Code. Your key is stored on this Mac and only sent to OpenRouter."
                        : "Any Anthropic-compatible endpoint works: LiteLLM, a local gateway, or another vendor's Anthropic-compatible API.")
                    .font(HNFont.body).lineSpacing(3).foregroundStyle(Color.hnMuted)
                    .frame(maxWidth: 560, alignment: .leading).fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)

                Group {
                    if let error = m.error, m.providers.isEmpty {
                        down(error)
                    } else if m.draft != nil {
                        ProviderEditor(m: m)
                    } else {
                        list
                    }
                }
                .padding(.top, 22)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            .padding(.horizontal, 40)
            .padding(.bottom, 28)
    }

    private func down(_ error: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            GlassCard {
                VStack(alignment: .leading, spacing: 8) {
                    Seam(label: "Unavailable", activity: .alert)
                    Text(error).font(HNFont.heading).foregroundStyle(Color.hnInk).fixedSize(horizontal: false, vertical: true)
                    Text("Agents and providers live in the background service. Start it from Setup, then try again.").font(HNFont.small).foregroundStyle(Color.hnMuted)
                }
            }
            HStack(spacing: 10) {
                CompactPill(title: "Try again", kind: .ghost) { m.load() }
                CompactPill(title: "Open setup", kind: .outline) { WindowManager.shared.showSetup(startAt: .service) }
            }
        }
    }

    private var list: some View {
        let claude = app.agents.claude
        return VStack(alignment: .leading, spacing: 0) {
            HNScroll {
                VStack(alignment: .leading, spacing: 10) {
                    SectionSeam(label: "Agents")
                    AgentsSection(app: app)
                    SectionSeam(label: "Claude Code providers", trail: claude ? nil : "for Claude Code").padding(.top, 16)
                    if !claude {
                        // Providers are Claude Code settings: nothing to run them in without it.
                        Text("Providers are for Claude Code’s sessions, and Claude Code isn’t on this Mac. Install it in Setup to use them.")
                            .font(HNFont.small).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                    } else {
                        if m.loading {
                            HStack(spacing: 10) { PlanetMeter(activity: .live); Text("Loading providers…").font(HNFont.small).foregroundStyle(Color.hnMuted) }
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        ForEach(m.providers) { p in ProviderCard(p: p, m: m) }
                    }
                    if let e = m.error { ErrorText(text: e).frame(maxWidth: .infinity, alignment: .leading) }
                }
                .padding(.bottom, 8)
            }
            if claude {
                HStack(spacing: 10) {
                    Spacer()
                    ActionPill(title: "Add custom", kind: .outline) { m.newCustom() }
                    ActionPill(title: "Add OpenRouter") { m.newOpenRouter() }
                }
                .padding(.top, 14)
            }
        }
    }
}

/// A section of the window: its label in tracked caps, a hairline, and a word on the right. Quieter
/// than a card's seam (no planet), so the cards keep theirs.
private struct SectionSeam: View {
    var label: String
    var trail: String? = nil
    var body: some View {
        HStack(spacing: 10) {
            Text(label).hnLabel(.hnDim).lineLimit(1).layoutPriority(2)
            Rectangle().fill(Color.hnLine).frame(height: 1)
            if let trail { Text(trail).font(HNFont.label).tracking(0.9).foregroundStyle(Color.hnGhost).lineLimit(1).layoutPriority(1) }
        }
        .padding(.top, 2)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// AGENTS: Claude Code and Codex, each in a glass card: what's installed (and, for Codex, whether
/// it's new enough), how it's signed in, and for Codex the switch that lets Hypernova use it. What's
/// missing is said with the line to run, never installed from here (Claude Code installs in Setup,
/// with Anthropic's installer).
private struct AgentsSection: View {
    @ObservedObject var app: AppModel

    var body: some View {
        if app.firstPollDone && !app.daemonUp {
            Text("The background service isn’t answering, so Hypernova can’t say which agents are here.")
                .font(HNFont.small).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
        } else {
            VStack(spacing: 10) {
                ClaudeAgentCard(app: app)
                CodexAgentCard(app: app)
            }
        }
    }
}

private struct ClaudeAgentCard: View {
    @ObservedObject var app: AppModel

    var body: some View {
        let installed = app.agents.claude
        let version = app.status?.claude?.version ?? app.snapshot?.claude?.version ?? app.snapshot?.mac?.claudeVersion
        GlassCard(needsYou: installed && app.claudeAccountNeedsYou) {
            VStack(alignment: .leading, spacing: 10) {
                Seam(label: "Claude Code", trail: installed ? (app.claudeAccountNeedsYou ? "needs you" : nil) : "not installed",
                     activity: installed && app.sessions.contains { !$0.isCodex && $0.isBusy } ? .live : .rest)
                AgentRow(key: "Version") {
                    if installed {
                        HStack(spacing: 6) {
                            CheckIcon(size: 11)
                            Text(version ?? "installed").font(HNFont.monoS).foregroundStyle(Color.hnInk)
                        }
                    } else {
                        StatusChip(text: "Not installed", color: .hnDim)
                    }
                }
                if installed {
                    AgentRow(key: "Account") { account }
                    if let p = app.status?.claude?.path { InfoLine(key: "Path", value: Paths.tilde(p)) }
                    Text("Its sessions can run on the providers below.").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
                } else {
                    HStack(alignment: .center, spacing: 12) {
                        Text("Hypernova installs it with Anthropic’s official installer, in Setup. Codex works without it.")
                            .font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 8)
                        CompactPill(title: "Open setup", kind: .outline) { WindowManager.shared.showSetup(startAt: .claude) }
                    }
                }
            }
        }
    }

    /// How Claude Code is signed in: fine (with the plan), at a limit, or what only you can fix.
    @ViewBuilder private var account: some View {
        let a = app.account
        switch a?.state {
        case "ok"?, "limited"?, "no_credit"?:
            HStack(spacing: 8) {
                CheckIcon(size: 11)
                Text(a?.plan?.nonEmpty.map { "Signed in, Claude \($0.prefix(1).uppercased() + $0.dropFirst())" } ?? "Signed in").font(HNFont.small).foregroundStyle(Color.hnInk).lineLimit(1)
                if a?.state == "limited", let line = a.flatMap({ AccountLine.content($0, now: Date(), signingIn: false) }) {
                    Text(line.title).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(1)
                }
            }
        case "logged_out"?:
            HStack(spacing: 10) {
                StatusChip(text: "Signed out", color: .hnAmber)
                CompactPill(title: app.signingIn ? "Signing in…" : "Sign in", kind: .outline) { app.signIn() }
                    .help("Opens claude auth login in a terminal window and your browser")
            }
        case "no_subscription"?:
            StatusChip(text: "No Claude plan", color: .hnAmber)
        case "on_hold"?:
            StatusChip(text: "On hold", color: .hnAmber)
        default:
            Text("Not known yet").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
        }
    }
}

private struct CodexAgentCard: View {
    @ObservedObject var app: AppModel

    var body: some View {
        let c = app.codex
        let trouble = c.flatMap { $0.enabled ? CodexWords.trouble($0) : nil }
        GlassCard(needsYou: trouble?.waitsOnYou == true) {
            VStack(alignment: .leading, spacing: 10) {
                Seam(label: "Codex", trail: trail(c), activity: c?.enabled == true && c?.connected == true ? .live : .rest)
                if let c { found(c) } else { missing }
                // Turning it on or off didn't go through, or its sign-in didn't open: said here.
                if let e = app.codexError { ErrorText(text: e) }
                actions(c)
            }
        }
    }

    /// Signed out: Sign in (a Terminal window running `codex login`) and Check again; not installed or
    /// too old: Check again, once the line above has been run.
    @ViewBuilder private func actions(_ c: Codex?) -> some View {
        let signedOut = c.map { $0.enabled && !$0.tooOld && $0.account?.signedIn == false } ?? false
        if c == nil || signedOut || c?.tooOld == true {
            HStack(spacing: 10) {
                Spacer()
                if signedOut, c?.path != nil {
                    CompactPill(title: "Sign in", kind: .outline) { app.openCodexSignIn() }
                        .help("Opens a Terminal window running codex login, and your browser. Hypernova reads the account again when you come back.")
                }
                CompactPill(title: "Check again", kind: .ghost) { app.checkCodex() }
                    .help(c == nil ? "Look for Codex again" : signedOut ? "Read Codex's account again" : "Look at Codex's version again")
            }
        }
    }

    private func trail(_ c: Codex?) -> String? {
        guard let c else { return "not installed" }
        if c.tooOld { return "update needed" }
        if !c.enabled { return "off" }
        if c.problem != nil { return "can’t reach" }
        if c.account?.signedIn == false { return "signed out" }
        return c.connected ? "on" : "connecting"
    }

    /// Not on this Mac: how to get it, in a terminal, quietly.
    private var missing: some View {
        VStack(alignment: .leading, spacing: 10) {
            AgentRow(key: "Version") { StatusChip(text: "Not installed", color: .hnDim) }
            Text("Hypernova doesn’t install Codex. To use it, run one of these in a terminal, then sign in; it shows here by itself.")
                .font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
            ShellLine(key: "Install", command: CodexDetector.installNPM)
            ShellLine(key: "or", command: CodexDetector.installBrew)
            ShellLine(key: "Sign in", command: "codex login")
        }
    }

    @ViewBuilder private func found(_ c: Codex) -> some View {
        AgentRow(key: "Version") {
            if c.tooOld {
                StatusChip(text: "Too old", color: .hnAmber)
                Text("\(c.version.map { $0 + ", " } ?? "")Hypernova needs \(c.min ?? "a newer one") or later").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(1)
            } else {
                HStack(spacing: 6) {
                    CheckIcon(size: 11)
                    Text(c.version ?? "unknown").font(HNFont.monoS).foregroundStyle(Color.hnInk)
                }
            }
        }
        if c.tooOld { ShellLine(key: "Update", command: "codex update") }
        AgentRow(key: "Account") {
            if !c.enabled {
                Text("Read once it’s on").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
            } else {
                switch c.account?.signedIn {
                case true?:
                    HStack(spacing: 6) {
                        CheckIcon(size: 11)
                        Text(CodexWords.account(c.account) ?? "Signed in").font(HNFont.small).foregroundStyle(Color.hnInk).lineLimit(1)
                        if CodexWords.paysPerUse(c) {
                            Text("costs money per turn").font(HNFont.sans(11)).foregroundStyle(Color.hnAmber.opacity(0.85)).lineLimit(1)
                                .help("On an API key Codex has no plan windows: each turn is billed to the key.")
                        }
                    }
                case false?:
                    StatusChip(text: "Signed out", color: .hnAmber)
                case nil:
                    HStack(spacing: 8) { PlanetMeter(activity: .live); Text("Checking…").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted) }
                }
            }
        }
        if c.enabled, !c.tooOld, c.account?.signedIn == false { ShellLine(key: "Sign in", command: "codex login") }
        if c.enabled, !c.tooOld, let p = c.problem?.nonEmpty {
            Text(CodexWords.trouble(c).map { "\($0.text). \(p)" } ?? p).font(HNFont.sans(12)).foregroundStyle(Color.hnAmber).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }
        if let p = c.path { InfoLine(key: "Path", value: Paths.tilde(p)) }
        Rectangle().fill(Color.hnLine).frame(height: 1).padding(.vertical, 2)
        HStack(alignment: .top, spacing: 12) {
            HNSwitch(isOn: c.enabled, label: "Use Codex with Hypernova") { app.setCodex($0) }
                .padding(.top, 1)
                // Too old: it can be turned off, never on.
                .disabled(app.codexBusy || (c.tooOld && !c.enabled))
                .opacity(c.tooOld && !c.enabled ? 0.4 : 1)
            VStack(alignment: .leading, spacing: 3) {
                Text("Use Codex with Hypernova").font(HNFont.sans(13)).foregroundStyle(Color.hnInk)
                Text(CodexWords.switchLine(claude: app.agents.claude, shell: app.status?.shell?.installed == true))
                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }
}

/// A row of an agent's card: its key in tracked caps, then what's there.
private struct AgentRow<Value: View>: View {
    var key: String
    @ViewBuilder var value: Value
    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(key).hnLabel(.hnDim).frame(width: 74, alignment: .leading)
            HStack(spacing: 8) { value }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 20)
        // One element for VoiceOver: "Version, 0.163.0".
        .accessibilityElement(children: .combine)
    }
}

private struct ProviderCard: View {
    var p: Provider
    @ObservedObject var m: ProvidersModel

    var body: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 10) {
                Seam(label: kindLabel, trail: p.builtIn == true ? "your Claude Code login" : nil)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(p.name).font(HNFont.heading).foregroundStyle(Color.hnInk)
                    if p.builtIn != true {
                        StatusChip(text: p.hasKey == true ? "Key saved" : "No key", color: p.hasKey == true ? .hnMuted : (p.kind == "openrouter" ? .hnAmber : .hnDim))
                    }
                    Spacer()
                    if p.builtIn != true {
                        HStack(spacing: 8) {
                            CompactPill(title: "Edit", kind: .outline) { m.edit(p) }
                            CompactPill(title: "Delete", kind: .danger) { m.delete(p) }
                        }
                    }
                }
                if let u = p.baseUrl, !u.isEmpty {
                    Text(u).font(HNFont.monoS).foregroundStyle(Color.hnDim).lineLimit(1).truncationMode(.middle)
                }
                // Its trouble as the phone says it, or what's left on it after a banknote; the
                // one thing to do about it on the right.
                if p.builtIn != true, state != nil || figure != nil || p.kind == "openrouter" {
                    HStack(spacing: 10) {
                        if let t = state {
                            Text(t.text).font(HNFont.small).foregroundStyle(t.amber ? Color.hnAmber : Color.hnMuted).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        }
                        if let f = figure { GlyphFigure(glyph: .note, text: f, color: .hnMuted, size: 12) }
                        Spacer()
                        if p.status?.state == "bad_key" {
                            CompactPill(title: "Fix key", kind: .outline) { m.edit(p) }
                        } else if p.kind == "openrouter" && p.status?.state == "no_credit" {
                            CompactPill(title: "Add credits", kind: .outline) { NSWorkspace.shared.open(URL(string: "https://openrouter.ai/settings/credits")!) }
                        }
                        if ["down", "limited"].contains(p.status?.state ?? "") || (p.kind == "openrouter" && p.hasKey == true) {
                            CompactPill(title: m.checking.contains(p.id) ? "Checking…" : p.status == nil ? "Check" : "Try again", kind: .ghost) { m.check(p) }
                        }
                    }
                }
                let models = modelPairs
                if !models.isEmpty {
                    HStack(spacing: 14) {
                        ForEach(models, id: \.0) { k, v in
                            HStack(spacing: 6) {
                                Text(k).hnLabel(.hnDim)
                                TagChip(text: ModelNames.chip(v)).help(v)
                            }
                        }
                    }
                } else if p.builtIn == true {
                    Text("Sessions use your normal Claude Code sign-in and models.").font(HNFont.small).foregroundStyle(Color.hnDim)
                }
            }
        }
    }

    /// Its live state ("OpenRouter is rate limited until 14:05"), else the verdict on a key just
    /// saved ("Key works. $4.20 left."), for 15 s or until the next update.
    private var state: (text: String, amber: Bool)? {
        if let st = p.status { return (ProviderWords.sentence(p.name, st, now: Date()), st.needsYou || st.state == "limited" || st.state == "down") }
        return m.verdict[p.id]
    }

    /// What's left on it, beside a banknote: not while a verdict already says it.
    private var figure: String? {
        if p.status == nil, m.verdict[p.id] != nil { return nil }
        return ProviderWords.figure(p)
    }

    private var kindLabel: String {
        switch p.kind {
        case "anthropic": return "Built in"
        case "openrouter": return "OpenRouter"
        case "custom": return "Custom"
        default: return p.kind
        }
    }

    private var modelPairs: [(String, String)] {
        guard let ms = p.models else { return [] }
        return [("Default", ms.default), ("Opus", ms.opus), ("Sonnet", ms.sonnet), ("Haiku", ms.haiku)].compactMap { k, v in v.flatMap { $0.isEmpty ? nil : (k, $0) } }
    }
}

private struct ProviderEditor: View {
    @ObservedObject var m: ProvidersModel

    /// A field of the draft. Cancel and Save clear the draft while the editor is still fading out,
    /// and a field read then must get "" rather than unwrap nothing (which crashes SwiftUI).
    private func field(_ key: WritableKeyPath<ProvidersModel.Draft, String>) -> Binding<String> {
        Binding(get: { m.draft?[keyPath: key] ?? "" }, set: { m.draft?[keyPath: key] = $0 })
    }

    var body: some View {
        if let d = m.draft {
            let isOR = d.kind == "openrouter"
            VStack(alignment: .leading, spacing: 0) {
                HNScroll {
                    GlassCard {
                        VStack(alignment: .leading, spacing: 16) {
                            Seam(label: isOR ? "OpenRouter" : "Custom", trail: d.isNew ? "new" : "editing")
                            HStack(alignment: .top, spacing: 14) {
                                HNField(label: "Name", text: field(\.name), placeholder: isOR ? "OpenRouter" : "My gateway")
                                HNField(label: "Base URL", text: field(\.baseUrl), placeholder: isOR ? ProvidersModel.openRouterURL : "https://gateway.example.com/anthropic", mono: true)
                            }
                            HNField(label: "API key", text: field(\.apiKey),
                                    placeholder: d.hasKey ? "Saved. Leave empty to keep it" : (isOR ? "sk-or-v1-…" : "Optional for local gateways. Your Claude login is never sent."),
                                    mono: true, secure: true,
                                    hint: m.keyRefused ? "Refused. Paste the key again, or a new one." : isOR ? "Create one at openrouter.ai/keys." : nil,
                                    invalid: m.keyRefused)
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(alignment: .top, spacing: 14) {
                                    HNField(label: "Default model", text: field(\.def), placeholder: isOR ? "openai/gpt-5" : "model-name", mono: true)
                                    HNField(label: "Opus", text: field(\.opus), placeholder: isOR ? "openai/gpt-5" : "model-for-opus", mono: true)
                                }
                                HStack(alignment: .top, spacing: 14) {
                                    HNField(label: "Sonnet", text: field(\.sonnet), placeholder: isOR ? "openai/gpt-5-mini" : "model-for-sonnet", mono: true)
                                    HNField(label: "Haiku", text: field(\.haiku), placeholder: isOR ? "openai/gpt-5-nano" : "model-for-haiku", mono: true)
                                }
                                Text("Claude Code asks for Opus, Sonnet or Haiku by tier; these map each tier to a model here. Leave one empty to keep Claude Code's own choice.")
                                    .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .padding(.bottom, 6)
                }
                if let e = m.formError { ErrorText(text: e).padding(.top, 8) }
                if let c = m.checkLine {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        if c.ok { CheckIcon(size: 11) } else { CrossIcon(size: 10, color: .hnAmber) }
                        Text(c.text).font(HNFont.small).foregroundStyle(c.ok ? Color.hnMuted : Color.hnAmber).fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, 8)
                }
                HStack(spacing: 10) {
                    CompactPill(title: "Cancel", kind: .ghost) { m.cancel() }
                    CompactPill(title: m.checkingDraft ? "Checking…" : "Check", kind: .outline) { m.checkDraft() }
                        .help("Check the address and the key without saving them. Free: nothing reaches a model.")
                    Spacer()
                    ActionPill(title: m.saving ? "Saving…" : (d.isNew ? "Add provider" : "Save changes"), busy: m.saving) { m.save() }
                }
                .padding(.top, 14)
            }
        }
    }
}
