import Foundation

// Types from docs/PROTOCOL.md. Decoding is lenient on purpose: unknown fields are ignored, and
// fields the daemon might send as a number or a string (dates, counts) are accepted either way.

/// A timestamp sent as epoch ms, epoch seconds or an ISO 8601 string.
struct FlexDate: Decodable, Equatable, Hashable {
    var date: Date
    init(_ d: Date) { date = d }
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let n = try? c.decode(Double.self) {
            date = n > 1e11 ? Date(timeIntervalSince1970: n / 1000) : Date(timeIntervalSince1970: n)
            return
        }
        let s = try c.decode(String.self)
        if let n = Double(s) { date = n > 1e11 ? Date(timeIntervalSince1970: n / 1000) : Date(timeIntervalSince1970: n); return }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { date = d; return }
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: s) { date = d; return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "Not a date: \(s)")
    }
}

/// A field that may be a list or just a count.
struct FlexList<T: Decodable & Equatable>: Decodable, Equatable {
    var items: [T]
    var count: Int
    init(_ items: [T]) { self.items = items; count = items.count }
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let n = try? c.decode(Int.self) { items = []; count = n; return }
        if let arr = try? c.decode([Lenient<T>].self) {
            items = arr.compactMap(\.value); count = arr.count; return
        }
        items = []; count = 0
    }
}

/// Decodes T if it can; one odd element never sinks the whole list.
struct Lenient<T: Decodable>: Decodable {
    var value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

struct Health: Decodable, Equatable { var ok: Bool?; var `protocol`: Int?; var name: String? }

struct APIErrorBody: Decodable {
    struct Inner: Decodable { var code: String?; var message: String?; var field: String?; var run: WFRun? }
    var error: Inner?
    /// 409 `not_asking` carries the run, so the answer can say who answered.
    var run: WFRun?
}

struct UsageWindow: Decodable, Equatable, Hashable {
    var pct: Double
    var resetsAt: FlexDate?
}

/// A model's own weekly limit ("Fable", "Sonnet"), which only Claude Code's /usage screen shows
/// (docs/PROTOCOL.md, "Per-model weeks"). No reset when the screen gave none.
struct ModelWindow: Decodable, Equatable, Hashable {
    var label: String
    var pct: Double
    var resetsAt: FlexDate?
}

struct Usage: Decodable, Equatable {
    var fiveHour: UsageWindow?
    var sevenDay: UsageWindow?
    /// Missing from an older daemon: none.
    var models: [ModelWindow]?
    /// How fast the windows are being spent; only while a session works. Missing when idle or from an older daemon.
    var rate: UsageRate?
    var source: String?
    var observedAt: FlexDate?
    /// The per-model weeks couldn't be read lately (these are the last good ones).
    var modelsStale: Bool?
    /// The usage probe's health: `ok` false after 3 looks in a row couldn't read /usage, since `since`.
    var probe: Probe?
    struct Probe: Decodable, Equatable { var ok: Bool?; var since: FlexDate? }
}

/// The spend pace in % per minute and the milliseconds until 100% at it, nil when the reset comes
/// first (docs/PROTOCOL.md, "Usage"). Drawn as the meters' faint fill (Core/UsageMeter.swift).
struct UsageRate: Decodable, Equatable {
    var fiveHour: Double?
    var sevenDay: Double?
    var fiveHourEtaMs: Double?
    var sevenDayEtaMs: Double?
    /// The same projection as a moment, counted from the last reading, so every client shows the
    /// same "limit at 14:05"; absent from an older daemon.
    var fiveHourLimitAt: FlexDate?
    var sevenDayLimitAt: FlexDate?
}

struct TailscaleStatus: Decodable, Equatable {
    var installed: Bool?
    var cliPath: String?
    var running: Bool?
    var loggedIn: Bool?
    var dnsName: String?
    var ip: String?
    var httpsEnabled: Bool?
    var serveOn: Bool?
    var url: String?
    var keyExpiry: FlexDate?

    /// The phone base URL, without a trailing slash.
    var baseURL: String? {
        guard let url, !url.isEmpty else { return nil }
        return url.hasSuffix("/") ? String(url.dropLast()) : url
    }
}

/// Tailscale built in (the daemon's tsnet.ts): which Tailscale serves the phone, and the sign-in link while one is needed.
struct TsnetStatus: Decodable, Equatable {
    struct Helper: Decodable, Equatable {
        var state: String?
        var loginUrl: String?
        var dnsName: String?
        var account: String?
        var listening: String?
        var tls: String?
        var error: String?
    }
    /// "system" (the Tailscale app's serve), "builtIn" (the daemon's own node) or "off".
    var mode: String?
    var reason: String?
    var available: Bool?
    var enabled: Bool?
    var helper: Helper?
    var url: String?
    var controlUrl: String?
}

struct LocalStatus: Decodable, Equatable {
    struct Claude: Decodable, Equatable { var path: String?; var version: String? }
    struct Hooks: Decodable, Equatable { var installed: Bool? }
    struct StatusLine: Decodable, Equatable { var installed: Bool?; var supported: Bool? }
    /// The `claude` shell function that makes terminal sessions shared (zsh only).
    struct Shell: Decodable, Equatable { var installed: Bool?; var supported: Bool? }
    var daemonVersion: String?
    var apiPort: Int?
    var claude: Claude?
    var tailscale: TailscaleStatus?
    var tsnet: TsnetStatus?
    /// Where a phone gets the app right now (the base URL's page, or the local network's while the built-in node is the only tailnet route).
    var downloadUrl: String?
    /// The daemon's own LAN download (bundled APK); false for a release URL or a public page.
    var downloadLocal: Bool?
    /// False only for the LAN download at the Mac's address (self-signed certificate: compare the checksum);
    /// true through the Mac's name under hypernova.observer (a real certificate). Nil from an older daemon.
    var downloadTrusted: Bool?
    /// With the download on the Mac's name: the tailnet's page too, for a phone that already has Tailscale.
    var downloadAnywhere: String?
    /// The Mac's name under hypernova.observer (the daemon's names.ts), for the Setup copy and diagnostics.
    struct Name: Decodable, Equatable { var host: String?; var inUse: Bool?; var reason: String?; var setting: Bool?; var terms: String?; var termsShown: Bool? }
    var name: Name?
    /// The bundled APK: its SHA-256, to compare with the download page's over the local network.
    struct Apk: Decodable, Equatable { var sha256: String?; var size: Int?; var versionName: String? }
    var apk: Apk?
    var hooks: Hooks?
    var statusLine: StatusLine?
    var shell: Shell?
    var devices: FlexList<Device>?
    var sessions: FlexList<Session>?
    var approvals: FlexList<Approval>?
    var connectedClients: Int?
    var usage: Usage?
    var projectsRoot: String?
    /// Settings → Files: Browse outside projects (the daemon's files.ts); nil from an older daemon.
    struct Files: Decodable, Equatable { var browseOutsideProjects: Bool? }
    var files: Files?
    var account: Account?
    /// Automations (AutomationModels.swift); nil from a daemon older than them.
    var automations: AutomationsStatus?
    /// The local network's listener (the daemon's lan.ts) while it's on: a phone on the same Wi‑Fi
    /// pairs and connects through it, with or without a tailnet. Nil while it's off.
    var lan: Lan?
    struct Lan: Decodable, Equatable { var port: Int?; var addresses: [String]?; var fingerprint: String? }
    /// Codex as a second agent (Codex below); nil when it isn't installed here, or from an older daemon.
    var codex: Codex?
}

/// OpenAI's Codex as a second agent (docs/PROTOCOL.md, "Codex"): installed or not, on for Hypernova
/// or not, signed in, and its own usage windows. The snapshot and `/local/status` carry it, nil when
/// Codex isn't on this Mac, so a Claude-only Mac looks exactly as before. Never fails: an odd field
/// is dropped, so a newer daemon's never sinks what it rides in.
struct Codex: Decodable, Equatable {
    /// How Codex is signed in: "chatgpt" or "apiKey" (nil when it isn't), and the plan ("plus", "pro").
    /// Nil while Codex is off for Hypernova (it reads the account only while on). Never the email.
    struct Account: Decodable, Equatable {
        var signedIn: Bool?
        var type: String?
        var planType: String?
    }
    /// Codex's own windows (a plan may have only the week, an API key none), and whether one is used up.
    struct Limits: Equatable {
        var fiveHour: UsageWindow?
        var sevenDay: UsageWindow?
        var limited = false
        var observedAt: FlexDate?
    }

    /// Installed and new enough (at least [min]) to be used here.
    var available = false
    /// The owner uses it with Hypernova (Setup's switch, or the first Codex session from the phone).
    var enabled = false
    /// The daemon reaches Codex's app-server now.
    var connected = false
    var version: String?
    /// The version Hypernova was checked against ("0.163.0"); later ones are taken.
    var min: String?
    /// The codex binary.
    var path: String?
    /// What's wrong, in the daemon's words, when it can't be used now.
    var problem: String?
    var account: Account?
    var usage: Limits?

    init(available: Bool = false, enabled: Bool = false, connected: Bool = false, version: String? = nil, min: String? = nil, path: String? = nil,
         problem: String? = nil, account: Account? = nil, usage: Limits? = nil) {
        (self.available, self.enabled, self.connected, self.version, self.min, self.path, self.problem, self.account, self.usage) =
            (available, enabled, connected, version, min, path, problem, account, usage)
    }

    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { return }
        available = ((try? c.decodeIfPresent(Bool.self, forKey: .available)) ?? nil) ?? false
        enabled = ((try? c.decodeIfPresent(Bool.self, forKey: .enabled)) ?? nil) ?? false
        connected = ((try? c.decodeIfPresent(Bool.self, forKey: .connected)) ?? nil) ?? false
        version = try? c.decodeIfPresent(String.self, forKey: .version)
        min = try? c.decodeIfPresent(String.self, forKey: .min)
        path = try? c.decodeIfPresent(String.self, forKey: .path)
        problem = try? c.decodeIfPresent(String.self, forKey: .problem)
        account = try? c.decodeIfPresent(Account.self, forKey: .account)
        if let u = try? c.nestedContainer(keyedBy: U.self, forKey: .usage) {
            // The daemon sends a reset it doesn't know as 0: no reset, not one in 1970 (which would read as long reset).
            func window(_ k: U) -> UsageWindow? {
                guard var w = try? u.decodeIfPresent(UsageWindow.self, forKey: k) else { return nil }
                if let r = w.resetsAt, r.date.timeIntervalSince1970 <= 0 { w.resetsAt = nil }
                return w
            }
            usage = Limits(fiveHour: window(.fiveHour), sevenDay: window(.sevenDay),
                           limited: ((try? u.decodeIfPresent(Bool.self, forKey: .limited)) ?? nil) ?? false,
                           observedAt: try? u.decodeIfPresent(FlexDate.self, forKey: .observedAt))
        }
    }
    private enum K: String, CodingKey { case available, enabled, connected, version, min, path, problem, account, usage }
    private enum U: String, CodingKey { case fiveHour, sevenDay, limited, observedAt }

    /// Installed, but older than Hypernova needs.
    var tooOld: Bool { path != nil && !available }
    /// On for Hypernova and usable: its sessions wear their tag, its meters show, automations can run on it.
    var on: Bool { enabled && available }
}

/// Claude Code on this Mac as the snapshot says it (docs/PROTOCOL.md, `Snapshot.claude`): nil from a
/// daemon older than Codex, where Claude Code always was there. Never fails: an odd field reads as
/// not said.
struct ClaudeAgent: Decodable, Equatable {
    var available: Bool?
    var version: String?

    init(available: Bool? = nil, version: String? = nil) { (self.available, self.version) = (available, version) }

    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { return }
        available = try? c.decodeIfPresent(Bool.self, forKey: .available)
        version = try? c.decodeIfPresent(String.self, forKey: .version)
    }
    private enum K: String, CodingKey { case available, version }
}

/// Which agents this Mac has and uses (docs/PROTOCOL.md, "Codex", "Which agent leads"): the one rule
/// behind the popover, the menu bar, Setup and the Agents & providers window. Claude Code leads while
/// it's installed (a daemon too old to say counts as installed, as it always was); without it, Codex
/// does, once it can be used here. A Mac with Claude Code and no Codex in use looks as it always did.
struct AgentRule: Equatable {
    enum Agent: Equatable { case claude, codex }

    /// Claude Code is installed here.
    var claude: Bool
    /// Codex is installed and new enough to be used.
    var codexAvailable: Bool
    /// Codex is installed, at any version (an old one asks for `codex update`).
    var codexInstalled: Bool
    /// Codex is on for Hypernova (Setup's switch, the Agents window's, or a first Codex session).
    var codexInUse: Bool

    init(claude: Bool, codex: Codex?) {
        self.claude = claude
        codexAvailable = codex?.available == true
        codexInstalled = codex != nil && (codex?.available == true || codex?.path != nil)
        codexInUse = codex?.enabled == true
    }

    /// The agent whose usage leads (the sun, the widget's star, the orrery), and where a new session
    /// with no agent named goes.
    var leading: Agent? { claude ? .claude : codexAvailable ? .codex : nil }
    /// Claude Code and Codex side by side: each one's meters and trouble wear its name.
    var both: Bool { claude && codexInUse }
    /// Codex alone: no Claude Code here, Codex on. Codex is then shown as Claude Code is elsewhere:
    /// its meters without a chip, what only you can fix as the account's card.
    var codexOnly: Bool { !claude && codexInUse }
    /// No agent to work with yet: neither Claude Code nor a Codex.
    var none: Bool { !claude && !codexInstalled }
    /// Who your phone reaches through Hypernova, in a sentence's words; "your agents" before either is here.
    var names: String {
        if both { return "Claude Code and Codex" }
        if claude { return "Claude Code" }
        if codexInstalled || codexInUse { return "Codex" }
        return "your agents"
    }
}

/// One of Codex's models for a picker (`GET /v1/codex/models`).
struct CodexModel: Decodable, Equatable, Identifiable, Hashable {
    var value: String
    var name: String?
    var description: String?
    var efforts: [String]?
    var defaultEffort: String?
    var isDefault: Bool?
    var id: String { value }
    var title: String { name?.nonEmpty ?? value }
}

struct Device: Decodable, Identifiable, Equatable, Hashable {
    var id: String
    var name: String?
    var model: String?
    var appVersion: String?
    var createdAt: FlexDate?
    var lastSeenAt: FlexDate?
    /// Which app paired: "lens" for Hypernova Lens on Even glasses, none for the Android app.
    var client: String?
    /// Glasses that come through a phone (the daemon's glasses.ts): on such an entry of
    /// `/local/devices`, the phone they come through.
    var via: String?
    /// On a phone: the glasses that connect through it, and when they were last seen.
    var glasses: GlassesLink?
    struct GlassesLink: Decodable, Equatable, Hashable { var since: FlexDate?; var seenAt: FlexDate?; var removedAt: FlexDate? }
    /// The phone has glasses that connect through it (not forgotten).
    var hasGlasses: Bool { glasses != nil && glasses?.removedAt == nil }
    var displayName: String {
        if client == "lens" { return "Hypernova Lens" }
        return name?.isEmpty == false ? name! : (model ?? "Phone")
    }
}

/// `GET /local/devices` may answer `{devices: [...]}` or a bare array.
struct DeviceList: Decodable {
    var devices: [Device]
    init(from decoder: Decoder) throws {
        if let arr = try? [Device](from: decoder) { devices = arr; return }
        struct Wrapped: Decodable { var devices: [Device]? }
        devices = (try? Wrapped(from: decoder))?.devices ?? []
    }
}

struct PairStart: Decodable {
    var code: String?
    var link: String
    var expiresAt: FlexDate?
    var url: String?
}

struct Session: Decodable, Identifiable, Equatable, Hashable {
    var id: String
    var title: String?
    var cwd: String?
    var projectName: String?
    var origin: String?
    var state: String?
    /// The provider it runs on ("anthropic" for the subscription).
    var providerId: String?
    var model: String?
    var permissionMode: String?
    var createdAt: FlexDate?
    var lastActivityAt: FlexDate?
    var activity: String?
    /// Claude's last reply, as the daemon keeps it: an idle row shows its first line.
    var lastText: String?
    /// Prompts waiting for its turn to end.
    var queued: Int?
    var pendingApprovalIds: [String]?
    /// Auto-denied after 10 minutes without an answer: Continue tells it you're back.
    var stoppedForAway: Bool?
    /// The shared terminal it runs in, with how many Mac terminal windows and phones have it open.
    var term: TermLink?
    struct TermLink: Decodable, Equatable, Hashable { var id: String; var mac: Int?; var phone: Int? }
    /// Agents its main agent started that run now: they work on after the main agent goes idle.
    var agentsRunning: Int?
    /// Why Claude can't reply in it now (a limit, a sign-out…); nil from older daemons.
    var blocked: Blocked?
    /// Set when an automation started it: its row wears an AUTO tag.
    var automation: AutomationRef?
    /// "codex" for an OpenAI Codex session (docs/PROTOCOL.md, "Codex"); absent for Claude Code's.
    var agent: String?
    /// The conversation switched agents (docs/PROTOCOL.md, "Switching agents"): the session it came
    /// from, and the one it went on in. A session with `switchedTo` is left off the list, as on the phone.
    var switchedFrom: String?
    var switchedTo: String?

    /// docs/PROTOCOL.md, Session.blocked. Never fails: an odd value reads as one without a kind
    /// (which the popover ignores), so a newer daemon's never sinks the session it rides in.
    struct Blocked: Decodable, Equatable, Hashable {
        /// "limit", "model", "no_credit", "logged_out", "no_subscription", "on_hold" or "provider".
        var kind: String?
        /// Claude Code's own line.
        var text: String?
        var resetsAt: FlexDate?
        /// Claude Code carries on by itself then.
        var autoResumeAt: FlexDate?
        /// Whether it carries on by itself after its limit, or waits for Continue (ProviderModels.swift).
        var resume: Resume?

        init(from decoder: Decoder) throws {
            guard let c = try? decoder.container(keyedBy: K.self) else { return }
            kind = try? c.decodeIfPresent(String.self, forKey: .kind)
            text = try? c.decodeIfPresent(String.self, forKey: .text)
            resetsAt = try? c.decodeIfPresent(FlexDate.self, forKey: .resetsAt)
            autoResumeAt = try? c.decodeIfPresent(FlexDate.self, forKey: .autoResumeAt)
            resume = try? c.decodeIfPresent(Resume.self, forKey: .resume)
        }
        private enum K: String, CodingKey { case kind, text, resetsAt, autoResumeAt, resume }

        /// A limit, which lifts by itself (the rest wait on you, or on the provider). A model's
        /// block lifts only when it has a time: one with none is a model the plan can't use.
        var isLimit: Bool { kind == "limit" || (kind == "model" && (resetsAt != nil || autoResumeAt != nil)) }
    }

    var displayTitle: String {
        if let t = title, !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return t }
        return projectName ?? "Untitled session"
    }
    var rank: Int {
        switch state {
        case "needs_you": return 0
        case "working", "starting": return 1
        case "error": return 2
        // Its agents at work: with the working ones, though its main agent is idle.
        case "idle": return agentsBusy > 0 ? 1 : 3
        default: return 4
        }
    }
    var isWorking: Bool { state == "working" || state == "starting" }
    var isLive: Bool { state != "ended" }
    /// Agents of a live session at work now (none once it has ended).
    var agentsBusy: Int { isLive ? max(agentsRunning ?? 0, 0) : 0 }
    /// It works, or its agents do: the main agent idle while they run isn't done.
    var isBusy: Bool { isWorking || agentsBusy > 0 }
    /// A phone is looking at its live terminal right now.
    var openOnPhone: Bool { (term?.phone ?? 0) > 0 }
    /// An OpenAI Codex session: the daemon opens a Codex terminal for it even without one (`open`).
    var isCodex: Bool { agent == "codex" }
}

/// Any JSON value: an approval's tool input is whatever the tool takes.
enum JSONValue: Decodable, Equatable, Hashable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else { self = .null }
    }

    subscript(key: String) -> JSONValue? { if case .object(let o) = self { return o[key] } else { return nil } }
    var string: String? { if case .string(let s) = self { return s } else { return nil } }
}

struct Question: Decodable, Equatable, Hashable {
    struct Option: Decodable, Equatable, Hashable { var label: String?; var description: String? }
    var question: String?
    var header: String?
    var multiSelect: Bool?
    var options: [Option]?
}

/// A request waiting for an answer (docs/PROTOCOL.md "Approvals"): a tool call, an
/// AskUserQuestion, or a plan.
struct Approval: Decodable, Identifiable, Equatable, Hashable {
    var id: String
    var sessionId: String?
    var createdAt: FlexDate?
    var expiresAt: FlexDate?
    var kind: String?
    var toolName: String?
    var summary: String?
    var input: JSONValue?
    var reason: String?
    var canAlways: Bool?
    var canSession: Bool?
    var questions: [Question]?
    var plan: String?

    var isTool: Bool { kind == nil || kind == "tool" }
    var tool: String {
        if let t = toolName, !t.isEmpty { return t }
        let s = summary ?? ""
        if let i = s.firstIndex(of: ":") { return String(s[..<i]) }
        return "Tool"
    }
    /// What it wants to run or touch: the command, else the summary without its "Tool: " prefix.
    var detail: String {
        if let c = input?["command"]?.string, !c.isEmpty { return c }
        let s = (summary ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix(tool + ": ") { return String(s.dropFirst(tool.count + 2)) }
        if let p = input?["file_path"]?.string ?? input?["url"]?.string ?? input?["pattern"]?.string ?? input?["query"]?.string { return p }
        return s.isEmpty ? tool : s
    }
    /// One line for a question or a plan.
    var headline: String { headline(codex: false) }
    /// One line for a question or a plan; [codex]: its session is Codex's, so a question without
    /// words of its own is Codex's.
    func headline(codex: Bool) -> String {
        if kind == "question" {
            let qs = (questions ?? []).compactMap { $0.question?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            if let first = qs.first { return qs.count > 1 ? "\(first) (+\(qs.count - 1) more)" : first }
        }
        if kind == "plan", let plan {
            let line = plan.split(separator: "\n").map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "#*-> ").union(.whitespaces)) }.first { !$0.isEmpty }
            if let line { return line }
        }
        return summary ?? (kind == "plan" ? "A plan is ready" : codex ? "Codex has a question" : "Claude Code has a question")
    }
    /// What an "all this session" answer covers ("web pages", "web searches", "file reads").
    var sessionScope: String {
        switch tool {
        case "WebFetch": return "web pages"
        case "WebSearch": return "web searches"
        default: return "file reads"
        }
    }
}

struct Snapshot: Decodable, Equatable {
    struct Mac: Decodable, Equatable {
        var id: String?
        var name: String?
        var daemonVersion: String?
        var claudeVersion: String?
        /// This Mac's battery; nil on a Mac without one (a Mac mini, an iMac, a Mac Studio or Pro),
        /// from a daemon older than the battery contract, or when it can't be read.
        var battery: Battery?
        /// Stay awake (docs/PROTOCOL.md); nil from a daemon older than it.
        var awake: Awake?
    }
    var mac: Mac?
    var usage: Usage?
    var sessions: FlexList<Session>?
    var approvals: FlexList<Approval>?
    /// Missing from daemons older than the account contract.
    var account: Account?
    /// The providers, for other providers' state and balance (ProviderModels.swift).
    var providers: FlexList<Provider>?
    /// Automations (AutomationModels.swift); nil from a daemon older than them.
    var automations: AutomationsSnapshot?
    /// Codex as a second agent; nil when it isn't installed here, or from an older daemon.
    var codex: Codex?
    /// Claude Code installed here or not; nil from a daemon older than Codex (it always was then).
    var claude: ClaudeAgent?
}

extension Snapshot.Mac {
    /// Every field on its own, so an odd one (a newer daemon's battery, say) never sinks the
    /// snapshot it rides in. Written here, apart from the type, so its memberwise init stays.
    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { return }
        id = try? c.decodeIfPresent(String.self, forKey: .id)
        name = try? c.decodeIfPresent(String.self, forKey: .name)
        daemonVersion = try? c.decodeIfPresent(String.self, forKey: .daemonVersion)
        claudeVersion = try? c.decodeIfPresent(String.self, forKey: .claudeVersion)
        battery = try? c.decodeIfPresent(Battery.self, forKey: .battery)
        awake = try? c.decodeIfPresent(Awake.self, forKey: .awake)
    }
    private enum K: String, CodingKey { case id, name, daemonVersion, claudeVersion, battery, awake }
}

/// The Mac's own battery (docs/PROTOCOL.md, `Snapshot.mac.battery` and `{t:"battery"}`): the
/// daemon reads `pmset -g batt` once a minute and says when the percentage moves by a point or the
/// power changes. Sessions run on this Mac, so they stop with it if it runs flat.
struct Battery: Decodable, Equatable {
    /// 0–100.
    var percent: Double
    /// nil when the daemon didn't say: then it's never taken as running down.
    var charging: Bool?
    var onAC: Bool?
    /// What macOS estimates is left; nil while it's still working that out (or on AC).
    var minutesLeft: Double?

    init(percent: Double, charging: Bool?, onAC: Bool?, minutesLeft: Double? = nil) {
        (self.percent, self.charging, self.onAC, self.minutesLeft) = (min(max(percent, 0), 100), charging, onAC, minutesLeft)
    }

    /// Without a percentage there's nothing to say, so the whole value fails (and reads as nil);
    /// the other fields are dropped one by one when odd.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        let p = try c.decode(Double.self, forKey: .percent)
        guard p.isFinite else { throw DecodingError.dataCorruptedError(forKey: .percent, in: c, debugDescription: "Not a percentage") }
        percent = min(max(p, 0), 100)
        charging = try? c.decodeIfPresent(Bool.self, forKey: .charging)
        onAC = try? c.decodeIfPresent(Bool.self, forKey: .onAC)
        minutesLeft = (try? c.decodeIfPresent(Double.self, forKey: .minutesLeft)).flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
    }
    private enum K: String, CodingKey { case percent, charging, onAC, minutesLeft }

    /// The percentage as macOS shows it.
    var shown: Int { Int(percent.rounded()) }
    /// Worth a word in the popover: at or under 20% and not charging, so it can run flat and take
    /// the sessions with it. Charging, or a charging state the daemon didn't give, says nothing.
    var low: Bool { shown <= 20 && charging == false }
}

/// The Lens guide's place (docs/PROTOCOL.md, "The Lens guide"), shared by the Mac's "Hypernova
/// Lens" window and the Lens phone page: a step taken on either moves the other.
struct GuideState: Codable, Equatable {
    var step: String
    var seen: Bool
    var done: Bool
}

/// Voice through Soniox on this Mac (docs/PROTOCOL.md, "Speech to text"). The snapshot and the
/// feed carry the first three; `GET /v1/stt` adds the key's last four characters (never the key).
struct SttState: Decodable, Equatable {
    var provider: String?
    var ready: Bool
    var error: String?
    var last4: String?
}

/// Claude Code's account on this Mac (docs/PROTOCOL.md, "Account"): fine, at a limit, or stopped
/// by something only you can fix. Every field is optional and an odd one is dropped, so a newer
/// daemon's account never sinks the snapshot it rides in.
struct Account: Decodable, Equatable {
    struct Limit: Decodable, Equatable {
        /// "five_hour", "seven_day", "model" or "credits".
        var window: String?
        /// Claude Code's own words: "session limit", "weekly limit", "Opus limit".
        var label: String?
        var resetsAt: FlexDate?
        /// Claude Code carries on by itself at this time.
        var autoResumeAt: FlexDate?
        var usingCredits: Bool?
    }
    /// A sign-in under way (`claude auth login` in a holder the daemon runs).
    struct Login: Decodable, Equatable {
        /// "starting", "waiting_code", "finishing" or "failed".
        var state: String?
        var url: String?
        /// "phone" or "mac": where it was started.
        var `where`: String?
        var error: String?
    }

    /// "ok", "limited", "no_credit", "logged_out", "no_subscription", "on_hold" or "unknown".
    var state: String
    var plan: String?
    var limit: Limit?
    var reason: String?
    var text: String?
    var since: FlexDate?
    var login: Login?

    init(state: String, plan: String? = nil, limit: Limit? = nil, reason: String? = nil, text: String? = nil, since: FlexDate? = nil, login: Login? = nil) {
        (self.state, self.plan, self.limit, self.reason, self.text, self.since, self.login) = (state, plan, limit, reason, text, since, login)
    }

    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { state = "unknown"; return }
        state = (try? c.decode(String.self, forKey: .state)) ?? "unknown"
        plan = try? c.decodeIfPresent(String.self, forKey: .plan)
        limit = try? c.decodeIfPresent(Limit.self, forKey: .limit)
        reason = try? c.decodeIfPresent(String.self, forKey: .reason)
        text = try? c.decodeIfPresent(String.self, forKey: .text)
        since = try? c.decodeIfPresent(FlexDate.self, forKey: .since)
        login = try? c.decodeIfPresent(Login.self, forKey: .login)
    }
    private enum K: String, CodingKey { case state, plan, limit, reason, text, since, login }

    /// Nothing heals it by itself, so it counts as needing you (the menu bar's dot): signed out,
    /// no plan, or an account on hold. A limit or spent credits come back at their reset.
    var needsYou: Bool { ["logged_out", "no_subscription", "on_hold"].contains(state) }
}

/// `POST /v1/account/login` answers `{account}`.
struct AccountEnvelope: Decodable { var account: Account? }

struct ProviderModels: Codable, Equatable {
    var `default`: String?
    var opus: String?
    var sonnet: String?
    var haiku: String?
}

struct Provider: Decodable, Identifiable, Equatable {
    var id: String
    var name: String
    var kind: String
    var baseUrl: String?
    var hasKey: Bool?
    var models: ProviderModels?
    var builtIn: Bool?
    /// Its trouble lately (out of credits, a refused key, rate limited, not answering); nil when fine.
    var status: ProviderStatus?
    /// What's left on it, where its API says (OpenRouter).
    var balance: ProviderBalance?
}

struct ProviderList: Decodable { var providers: [Provider] }
/// `POST`/`PUT /v1/providers` answer the provider, the check of a new key or address (8 s at most),
/// and whether the saved key was dropped (the address moved to another host without a new key).
struct ProviderEnvelope: Decodable { var provider: Provider?; var check: ProviderCheck?; var keyDropped: Bool? }
/// A key and an address checked (docs/PROTOCOL.md, "Providers"): "ok", "bad_key", "no_credit",
/// "limited", "not_found" or "unreachable", with the daemon's words for it.
struct ProviderCheck: Decodable, Equatable {
    var state: String?
    var text: String?
    var models: [String]?
}
struct ProviderCheckEnvelope: Decodable { var check: ProviderCheck? }

struct ProviderInput: Encodable {
    var name: String
    var kind: String
    var baseUrl: String?
    var apiKey: String?
    var models: ProviderModels

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(name, forKey: .name)
        try c.encode(kind, forKey: .kind)
        if let baseUrl { try c.encode(baseUrl, forKey: .baseUrl) }
        // Omitted means "keep the saved key" (PROTOCOL.md).
        if let apiKey, !apiKey.isEmpty { try c.encode(apiKey, forKey: .apiKey) }
        var m = c.nestedContainer(keyedBy: M.self, forKey: .models)
        func put(_ v: String?, _ k: M) throws { if let v, !v.isEmpty { try m.encode(v, forKey: k) } }
        try put(models.default, .default); try put(models.opus, .opus); try put(models.sonnet, .sonnet); try put(models.haiku, .haiku)
    }
    private enum K: String, CodingKey { case name, kind, baseUrl, apiKey, models }
    private enum M: String, CodingKey { case `default`, opus, sonnet, haiku }
}

