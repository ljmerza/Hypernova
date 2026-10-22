import Foundation

// Workflows as the Mac reads and writes them (docs/PROTOCOL.md, "Automations", "Workflows"): an
// automation with a trigger, 1 to 5 steps (each with at most one guard), 0 to 2 outputs and a
// budget. Every shape is flat with optional fields so a field a newer daemon adds is ignored and
// one it leaves out never sinks the list. They are written back as they were read, and `encode`
// sends only what each kind has (a nullable field as an explicit null).

/// What a daemon can do of workflows (`Automations.features`). An editor offers only these.
enum Feature {
    static let steps = "steps", ask = "ask", shell = "shell", budget = "budget"
    /// `{step:<id>}` in a text and `HYPERNOVA_STEP_<id>` for a command; the session trigger's `after`.
    static let stepOutput = "step.output", triggerAfter = "trigger.after"
    static let triggerWebhook = "trigger.webhook", triggerCron = "trigger.cron", triggerFile = "trigger.file"
    static let triggerGithub = "trigger.github", triggerHook = "trigger.hook"
    static let outputWebhook = "output.webhook", outputFile = "output.file"
}

struct WFGuard: Codable, Equatable {
    var on: String
    var text: String?
    static let ok = "ok", failed = "failed", quiet = "quiet", notQuiet = "not_quiet", contains = "contains", answer = "answer"
}

struct WFStep: Codable, Equatable, Identifiable {
    var id: String
    var kind: String
    var `if`: WFGuard?
    // start
    var projectPath: String?
    var prompt: String?
    var providerId: String?
    var model: String?
    var permissionMode: String?
    var allowedTools: [String]?
    // continue
    var target: String?
    // ask
    var question: String?
    var answers: [String]?
    var `default`: String?
    var timeoutMin: Int?
    // shell
    var command: String?
    var timeoutSec: Int?
    // notify, say
    var text: String?
    var glasses: Bool?

    static let start = "start", `continue` = "continue", ask = "ask", shell = "shell", notify = "notify", say = "say"
    static let known: Set<String> = [start, `continue`, ask, shell, notify, say]
    var isKnown: Bool { Self.known.contains(kind) }

    /// A start step's providerId when Codex runs it (docs/PROTOCOL.md, "Automations"): the agent, not
    /// one of Claude Code's providers. Its modes are Codex's sandboxes, it takes no allow rules, and its
    /// model is one of Codex's (null: Codex's default).
    static let codexProvider = "codex"
    static let codexModes = ["read-only", "workspace-write"]
    var isCodex: Bool { kind == Self.start && providerId == Self.codexProvider }
    /// A start step moved to Codex or back to Claude Code ([claude]: the provider it goes back to):
    /// each agent's own modes, no allow rules (Codex takes none), and its default model.
    mutating func runOn(codex: Bool, claude: String) {
        providerId = codex ? Self.codexProvider : claude
        permissionMode = codex ? Self.codexModes[0] : "dontAsk"
        allowedTools = []
        model = nil
    }

    static func newId() -> String { "st_" + String((0..<8).map { _ in "0123456789abcdef".randomElement()! }) }

    static func make(_ kind: String, project: String = "", providerId: String? = nil, after steps: [WFStep] = []) -> WFStep {
        var s = WFStep(id: newId(), kind: kind)
        switch kind {
        case start:
            s.projectPath = project; s.prompt = ""; s.providerId = providerId ?? ""; s.allowedTools = []
            // Codex starts read only; Claude Code with only what's allowed.
            s.permissionMode = providerId == codexProvider ? codexModes[0] : "dontAsk"
        case `continue`: s.target = "trigger"; s.prompt = ""
        case ask: s.question = ""; s.answers = ["Approve", "Skip"]; s.default = "Skip"; s.timeoutMin = 10
        case shell: s.projectPath = project; s.command = ""; s.timeoutSec = 60
        default: s.text = ""; s.glasses = false
        }
        // After a question, a new step starts on its first answer.
        if !steps.isEmpty, let q = steps.last(where: { $0.kind == ask }), steps.last?.kind == ask, let a = q.answers?.first { s.if = WFGuard(on: WFGuard.answer, text: a) }
        return s
    }

    enum K: String, CodingKey {
        case id, kind, `if`, projectPath, prompt, providerId, model, permissionMode, allowedTools, target, question, answers, `default`, timeoutMin, command, timeoutSec, text, glasses
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(id, forKey: .id); try c.encode(kind, forKey: .kind)
        if let g = `if` { try c.encode(g, forKey: .if) } else { try c.encodeNil(forKey: .if) }
        switch kind {
        case Self.start:
            try c.encodeIfPresent(projectPath, forKey: .projectPath); try c.encodeIfPresent(prompt, forKey: .prompt)
            try c.encodeIfPresent(providerId, forKey: .providerId)
            if let m = model { try c.encode(m, forKey: .model) } else { try c.encodeNil(forKey: .model) }
            try c.encodeIfPresent(permissionMode, forKey: .permissionMode); try c.encodeIfPresent(allowedTools, forKey: .allowedTools)
        case Self.continue:
            try c.encodeIfPresent(target, forKey: .target); try c.encodeIfPresent(prompt, forKey: .prompt)
            if let p = providerId { try c.encode(p, forKey: .providerId) } else { try c.encodeNil(forKey: .providerId) }
        case Self.ask:
            try c.encodeIfPresent(question, forKey: .question); try c.encodeIfPresent(answers, forKey: .answers)
            try c.encodeIfPresent(`default`, forKey: .default); try c.encodeIfPresent(timeoutMin, forKey: .timeoutMin)
        case Self.shell:
            try c.encodeIfPresent(projectPath, forKey: .projectPath); try c.encodeIfPresent(command, forKey: .command); try c.encodeIfPresent(timeoutSec, forKey: .timeoutSec)
        default:
            try c.encodeIfPresent(text, forKey: .text); try c.encodeIfPresent(glasses, forKey: .glasses)
        }
    }
}

/// A time trigger's `at` is "HH:MM"; a once's is epoch ms.
enum WFAt: Codable, Equatable {
    case clock(String), moment(Double)
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let s = try? c.decode(String.self) { self = .clock(s) } else { self = .moment(try c.decode(Double.self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self { case .clock(let s): try c.encode(s); case .moment(let d): try c.encode(Int64(d)) }
    }
}

struct WFTrigger: Codable, Equatable {
    var kind: String
    var at: WFAt?
    var days: [Int]?
    var hours: Int?
    var from: String?
    var to: String?
    var window: String?
    var afterLimit: Bool?
    var event: String?
    var projectPath: String?
    var below: Int?
    // new
    var expr: String?
    var path: String?
    var glob: String?
    var repoPath: String?
    var events: [String]?
    var every: Int?
    var tool: String?
    // webhook (view only)
    var last4: String?
    var createdAt: Double?
    var url: String?
    var command: String?

    /// Kinds this app edits (the new ones); another is kept as it came and shown in a line.
    static let editable: Set<String> = ["webhook", "cron", "file", "github", "hook"]
    var isEditable: Bool { Self.editable.contains(kind) }

    static func make(_ kind: String) -> WFTrigger {
        var t = WFTrigger(kind: kind)
        switch kind {
        case "cron": t.expr = "0 9 * * 1-5"
        case "file": t.path = ""; t.event = "created"
        case "github": t.repoPath = ""; t.events = ["pr_opened"]; t.every = 10
        case "hook": t.event = "Stop"; t.from = "mine"
        default: break
        }
        return t
    }

    enum K: String, CodingKey { case kind, at, days, hours, from, to, window, afterLimit, event, projectPath, below, expr, path, glob, repoPath, events, every, tool, last4, createdAt, url, command }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(kind, forKey: .kind)
        func nullable(_ v: String?, _ k: K) throws { if let v { try c.encode(v, forKey: k) } else { try c.encodeNil(forKey: k) } }
        switch kind {
        case "time": try c.encodeIfPresent(at, forKey: .at); try c.encodeIfPresent(days, forKey: .days)
        case "once": try c.encodeIfPresent(at, forKey: .at)
        case "every": try c.encodeIfPresent(hours, forKey: .hours); try c.encodeIfPresent(from, forKey: .from); try nullable(to, .to); try c.encodeIfPresent(days, forKey: .days)
        case "reset": try c.encodeIfPresent(window, forKey: .window); try c.encodeIfPresent(afterLimit, forKey: .afterLimit); try nullable(from, .from); try nullable(to, .to)
        case "limit": try c.encodeIfPresent(window, forKey: .window)
        case "session": try c.encodeIfPresent(event, forKey: .event); try nullable(projectPath, .projectPath); try c.encodeIfPresent(from, forKey: .from)
        case "power": try c.encodeIfPresent(event, forKey: .event); try c.encodeIfPresent(below, forKey: .below)
        case "cron": try c.encodeIfPresent(expr, forKey: .expr)
        case "file": try c.encodeIfPresent(path, forKey: .path); try nullable(glob, .glob); try c.encodeIfPresent(event, forKey: .event)
        case "github": try c.encodeIfPresent(repoPath, forKey: .repoPath); try c.encodeIfPresent(events, forKey: .events); try c.encodeIfPresent(every, forKey: .every)
        case "hook": try c.encodeIfPresent(event, forKey: .event); try nullable(tool, .tool); try nullable(projectPath, .projectPath); try c.encodeIfPresent(from, forKey: .from)
        default: break
        }
    }
}

struct WFOutput: Codable, Equatable, Identifiable {
    var id: String
    var kind: String
    var text: String?
    var glasses: Bool?
    var preset: String?
    var host: String?
    var last4: String?
    var path: String?
    // typed once, never sent back
    var url: String?
    var botToken: String?
    var chatId: String?

    static let notify = "notify", say = "say", webhook = "webhook", file = "file"
    static func newId() -> String { "ou_" + String((0..<8).map { _ in "0123456789abcdef".randomElement()! }) }
    static func make(_ kind: String) -> WFOutput {
        var o = WFOutput(id: newId(), kind: kind)
        switch kind {
        case notify: o.glasses = false
        case webhook: o.preset = "ntfy"
        case file: o.path = ""
        default: break
        }
        return o
    }
    var isKnown: Bool { [Self.notify, Self.say, Self.webhook, Self.file].contains(kind) }

    enum K: String, CodingKey { case id, kind, text, glasses, preset, host, last4, path, url, botToken, chatId }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(id, forKey: .id); try c.encode(kind, forKey: .kind)
        if let t = text, !t.isEmpty { try c.encode(t, forKey: .text) } else { try c.encodeNil(forKey: .text) }
        switch kind {
        case Self.notify: try c.encodeIfPresent(glasses, forKey: .glasses)
        case Self.webhook:
            try c.encodeIfPresent(preset, forKey: .preset)
            if let u = url, !u.isEmpty { try c.encode(u, forKey: .url) }
            if let b = botToken, !b.isEmpty { try c.encode(b, forKey: .botToken) }
            if let h = chatId, !h.isEmpty { try c.encode(h, forKey: .chatId) }
        case Self.file: try c.encodeIfPresent(path, forKey: .path)
        default: break
        }
    }
}

struct WFBudget: Codable, Equatable {
    var minutes: Int?
    var windowPct: Int?
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        if let m = minutes { try c.encode(m, forKey: .minutes) } else { try c.encodeNil(forKey: .minutes) }
        if let w = windowPct { try c.encode(w, forKey: .windowPct) } else { try c.encodeNil(forKey: .windowPct) }
    }
    enum CodingKeys: String, CodingKey { case minutes, windowPct }
}

// MARK: Runs

struct WFAnswer: Decodable, Equatable { var text: String; var by: String?; var at: Double? }

struct WFRunStep: Decodable, Equatable, Identifiable {
    var id: String
    var kind: String?
    var state: String
    var reason: String?
    var startedAt: Double?
    var endedAt: Double?
    var result: String?
    var quiet: Bool?
    var sessionIds: [String]?
    var exit: Int?
    var answer: WFAnswer?
    var reused: Bool?
}

struct WFRunAsk: Decodable, Equatable {
    var stepId: String
    var question: String
    var answers: [String]
    var `default`: String
    var until: Double
}

struct WFRunOutput: Decodable, Equatable, Identifiable {
    var id: String
    var kind: String?
    var state: String
    var error: String?
    var at: Double?
}

struct WFFired: Decodable, Equatable { var by: String?; var at: Double?; var sessionId: String?; var detail: String? }

struct WFRun: Decodable, Equatable, Identifiable {
    var id: String
    var automationId: String?
    var name: String?
    var fired: WFFired?
    var state: String
    var reason: String?
    var until: Double?
    var startedAt: Double?
    var endedAt: Double?
    var result: String?
    var quiet: Bool?
    var sessionIds: [String]?
    var steps: [WFRunStep]?
    var step: Int?
    var ask: WFRunAsk?
    var outputs: [WFRunOutput]?
    var retryOf: String?
    var retryFrom: String?

    var active: Bool { ["queued", "waiting", "running", "needs_you"].contains(state) }
    /// The first step that failed or stopped: where Retry can start.
    var failedStepIndex: Int? { steps?.firstIndex { $0.state == "failed" || $0.state == "stopped" } }
}

struct WFAutomation: Decodable, Equatable, Identifiable {
    var id: String
    var name: String
    var enabled: Bool?
    var onlyOnce: Bool?
    var trigger: WFTrigger
    var steps: [WFStep]?
    var outputs: [WFOutput]?
    var budget: WFBudget?
    var template: String?
    var nextAt: Double?
    var lastRun: WFRun?
    var runCount: Int?
    var paid: Bool?
    var problem: String?

    /// Everything in it is something this app can show and save back.
    var editable: Bool {
        guard let steps, !steps.isEmpty else { return false }
        return steps.allSatisfy(\.isKnown) && (outputs ?? []).allSatisfy(\.isKnown)
    }
}

/// What a save sends (`AutomationInput`).
struct WFInput: Encodable {
    var name: String
    var enabled: Bool
    var onlyOnce: Bool
    var trigger: WFTrigger
    var steps: [WFStep]
    var outputs: [WFOutput]
    var budget: WFBudget?
    var template: String?
    var confirmShell: Bool?
    var confirmBypass: Bool?
    var confirmPaid: Bool?

    enum K: String, CodingKey { case name, enabled, onlyOnce, trigger, steps, outputs, budget, template, confirmShell, confirmBypass, confirmPaid }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(name, forKey: .name); try c.encode(enabled, forKey: .enabled); try c.encode(onlyOnce, forKey: .onlyOnce)
        try c.encode(trigger, forKey: .trigger); try c.encode(steps, forKey: .steps); try c.encode(outputs, forKey: .outputs)
        if let budget { try c.encode(budget, forKey: .budget) } else { try c.encodeNil(forKey: .budget) }
        if let template { try c.encode(template, forKey: .template) }
        try c.encodeIfPresent(confirmShell, forKey: .confirmShell)
        try c.encodeIfPresent(confirmBypass, forKey: .confirmBypass)
        try c.encodeIfPresent(confirmPaid, forKey: .confirmPaid)
    }

    /// A save needs Touch ID or the password: a shell step, or a start step with no asking.
    var needsOwner: (shell: Bool, bypass: Bool) {
        (steps.contains { $0.kind == WFStep.shell }, steps.contains { $0.kind == WFStep.start && $0.permissionMode == "bypassPermissions" })
    }
}

struct WFListReply: Decodable {
    var automations: [Lenient<WFAutomation>]
    var paused: Bool?
    var features: [String]?
}
struct WFSaved: Decodable { var automation: WFAutomation?; var token: String? }
struct WFRunReply: Decodable { var run: WFRun? }
struct WFRunsReply: Decodable { var runs: [Lenient<WFRun>]; var total: Int?; var skipped: Int? }
struct WFCheckReply: Decodable { var next: [Double]? }

/// A run waiting at an ask step: one attention item.
struct WFAskItem: Equatable, Identifiable, Hashable {
    var runId: String
    var automationId: String?
    var name: String
    var stepId: String
    var question: String
    var answers: [String]
    var `default`: String
    var until: Date
    var id: String { runId }
}
