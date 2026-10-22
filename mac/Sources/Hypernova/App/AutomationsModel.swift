import AppKit
import LocalAuthentication
import SwiftUI

extension DaemonClient {
    func automationList() async throws -> WFListReply { try await request("GET", "/v1/automations", timeout: 10) }
    func automationRuns(_ id: String) async throws -> [WFRun] {
        try await request("GET", "/v1/automations/\(Self.segment(id))/runs?limit=20", timeout: 10, as: WFRunsReply.self).runs.compactMap(\.value)
    }
    func saveAutomation(id: String?, _ input: WFInput) async throws -> WFSaved {
        try await request(id == nil ? "POST" : "PUT", id.map { "/v1/automations/\(Self.segment($0))" } ?? "/v1/automations", body: input, timeout: 15)
    }
    func deleteAutomation(_ id: String) async throws { _ = try await request("DELETE", "/v1/automations/\(Self.segment(id))", as: EmptyResponse.self) }
    struct EnabledBody: Encodable { var enabled: Bool }
    func setAutomationEnabled(_ id: String, _ on: Bool) async throws {
        _ = try await request("POST", "/v1/automations/\(Self.segment(id))/enabled", body: EnabledBody(enabled: on), as: EmptyResponse.self)
    }
    func runAutomation(_ id: String) async throws -> WFRun? { try await request("POST", "/v1/automations/\(Self.segment(id))/run", timeout: 15, as: WFRunReply.self).run }
    struct RetryBody: Encodable { var fromStep: String? }
    func retryRun(_ runId: String, fromStep: String?) async throws -> WFRun? {
        try await request("POST", "/v1/automations/runs/\(Self.segment(runId))/retry", body: RetryBody(fromStep: fromStep), timeout: 15, as: WFRunReply.self).run
    }
    struct AnswerBody: Encodable { var stepId: String; var answer: String }
    /// The answer to an ask step, as the Mac (the admin token): the daemon records it as by "mac".
    func answerRun(_ runId: String, stepId: String, answer: String) async throws -> WFRun? {
        try await request("POST", "/v1/automations/runs/\(Self.segment(runId))/answer", body: AnswerBody(stepId: stepId, answer: answer), timeout: 10, as: WFRunReply.self).run
    }
    func newAutomationToken(_ id: String) async throws -> WFSaved {
        try await request("POST", "/v1/automations/\(Self.segment(id))/token", timeout: 10)
    }
    struct CheckBody: Encodable { var trigger: WFTrigger }
    func checkTrigger(_ t: WFTrigger) async throws -> [Double] {
        try await request("POST", "/v1/automations/check", body: CheckBody(trigger: t), timeout: 8, as: WFCheckReply.self).next ?? []
    }
}

/// Touch ID or the Mac's password before something that runs commands or runs without asking
/// (docs/PROTOCOL.md, "The fingerprint"): LocalAuthentication, `deviceOwnerAuthentication`.
enum OwnerCheck {
    static func reason(shell: Bool, bypass: Bool) -> String {
        shell ? "Let an automation run commands" : "Let an automation run without asking"
    }
    static func confirm(shell: Bool, bypass: Bool, name: String) async -> Bool {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else { return false }
        let why = reason(shell: shell, bypass: bypass) + (name.isEmpty ? "" : ": \(name)")
        return (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: why)) ?? false
    }
}

/// An automation being edited: the whole of an `AutomationInput`, kept apart from the daemon's copy
/// until Save.
struct WFDraft: Equatable {
    var id: String?
    var name = ""
    var enabled = true
    var onlyOnce = false
    var trigger: WFTrigger
    var steps: [WFStep]
    var outputs: [WFOutput] = []
    var budget: WFBudget?
    var template: String?
    var paid = false

    init(trigger: WFTrigger, steps: [WFStep]) { self.trigger = trigger; self.steps = steps }
    init(_ a: WFAutomation) {
        id = a.id; name = a.name; enabled = a.enabled ?? true; onlyOnce = a.onlyOnce ?? false
        trigger = a.trigger; steps = a.steps ?? []; outputs = a.outputs ?? []; budget = a.budget; template = a.template; paid = a.paid ?? false
    }
    func input(confirmShell: Bool, confirmBypass: Bool, confirmPaid: Bool) -> WFInput {
        WFInput(name: name.trimmingCharacters(in: .whitespaces), enabled: enabled, onlyOnce: onlyOnce, trigger: trigger, steps: steps, outputs: outputs, budget: budget, template: template,
                confirmShell: confirmShell ? true : nil, confirmBypass: confirmBypass ? true : nil, confirmPaid: (confirmPaid || paid) ? true : nil)
    }
}

/// The automations window's model: the list as the daemon keeps it, and every route the editor and
/// the run timeline use. Nothing here runs without `features`: an older daemon has no editor.
@MainActor
final class AutomationsModel: ObservableObject {
    static let shared = AutomationsModel()

    @Published var automations: [WFAutomation] = []
    @Published var features: [String]?
    @Published var paused = false
    @Published var loaded = false
    @Published var providers: [Provider] = []
    @Published var runs: [String: [WFRun]] = [:]
    @Published var error: String?
    @Published var saving = false
    /// Field path to its words, from the daemon's `field`: `steps[2].command`.
    @Published var fieldErrors: [String: String] = [:]
    /// The token of a webhook trigger, shown once after the save that made it.
    @Published var tokenCard: TokenCard?
    @Published var dialog: GlassDialog?
    /// The next three due times of the cron on screen, or its refusal.
    @Published var cronNext: [Double] = []
    @Published var cronProblem: String?
    @Published var cronChecking = false
    private var cronTask: Task<Void, Never>?
    private var sample = false

    struct TokenCard: Equatable { var automationId: String; var name: String; var token: String; var url: String; var command: String }

    func offers(_ f: String) -> Bool { features?.contains(f) == true }
    func automation(_ id: String?) -> WFAutomation? { automations.first { $0.id == id } }

    func load() {
        guard !sample else { return }
        Task {
            do {
                let r = try await DaemonClient.shared.automationList()
                automations = r.automations.compactMap(\.value)
                features = r.features
                paused = r.paused ?? false
                error = nil
            } catch { self.error = error.localizedDescription }
            loaded = true
            if providers.isEmpty { providers = (try? await DaemonClient.shared.providers()) ?? [] }
        }
    }

    func loadRuns(_ id: String) {
        guard !sample else { return }
        Task { if let r = try? await DaemonClient.shared.automationRuns(id) { runs[id] = r } }
    }

    /// A start step's provider for a new step: the built-in one.
    var defaultProviderId: String { providers.first { $0.builtIn == true }?.id ?? providers.first?.id ?? "" }
    func providerName(_ id: String?) -> String? {
        if id == WFStep.codexProvider { return "Codex" }
        return id.flatMap { i in providers.first { $0.id == i }?.name }
    }

    // MARK: Codex

    /// Codex is on for Hypernova and new enough (the menu bar's snapshot): a start step can run on it.
    /// Read when the editor draws, as it changes rarely; a render sets its own.
    var codexOn: Bool { sampleCodexOn ?? (AppModel.shared.codexOn && AppModel.shared.codex?.available == true) }
    private var sampleCodexOn: Bool?
    /// Codex's models for a Codex step's picker, read once when one is first shown (the daemon keeps
    /// them for an hour); empty until then, or while Codex can't be reached.
    @Published var codexModels: [CodexModel] = []
    private var codexModelsAsked = false
    func loadCodexModels() {
        guard !sample, !codexModelsAsked else { return }
        codexModelsAsked = true
        Task {
            if let list = try? await DaemonClient.shared.codexModels(), !list.isEmpty { codexModels = list } else { codexModelsAsked = false }
        }
    }
    /// A Codex model's name for its picker; the id itself for one Codex no longer lists, nil for its default.
    func codexModelName(_ value: String?) -> String? {
        guard let v = value?.nonEmpty else { return nil }
        return codexModels.first { $0.value == v }?.title ?? v
    }

    func newDraft() -> WFDraft {
        var d = WFDraft(trigger: .make("cron"), steps: [WFStep.make(WFStep.shell, project: Self.defaultProject)])
        d.name = ""
        return d
    }
    static var defaultProject: String { "" }

    // MARK: Saving

    /// Saves [draft]: Touch ID or the password first for a shell step or No asking. Returns the saved
    /// automation, or nil with `fieldErrors` set (or `error`).
    @discardableResult
    func save(_ draft: WFDraft, confirmPaid: Bool = false) async -> WFAutomation? {
        guard !saving else { return nil }
        fieldErrors = [:]; error = nil
        let probe = draft.input(confirmShell: false, confirmBypass: false, confirmPaid: confirmPaid)
        let need = probe.needsOwner
        if need.shell || need.bypass {
            guard await OwnerCheck.confirm(shell: need.shell, bypass: need.bypass, name: draft.name) else {
                error = "Not saved: it needs Touch ID or your password."
                return nil
            }
        }
        saving = true
        defer { saving = false }
        do {
            let saved = try await DaemonClient.shared.saveAutomation(id: draft.id, draft.input(confirmShell: need.shell, confirmBypass: need.bypass, confirmPaid: confirmPaid))
            guard let a = saved.automation else { load(); return nil }
            if let i = automations.firstIndex(where: { $0.id == a.id }) { automations[i] = a } else { automations.append(a) }
            if let t = saved.token {
                tokenCard = TokenCard(automationId: a.id, name: a.name, token: t, url: a.trigger.url ?? "", command: a.trigger.command ?? "")
            }
            return a
        } catch DaemonError.detail(_, let code, let message, let field, _) {
            if code == "confirm_paid" {
                dialog = GlassDialog(eyebrow: "Costs money", title: "Let it run on a paid provider?", line: "Each run is billed by that provider, not by your plan.", primary: "Let it", confirm: { [weak self] _ in
                    guard let self else { return }
                    self.dialog = nil
                    Task { if await self.save(draft, confirmPaid: true) != nil { self.onSaved?() } }
                }, cancel: { [weak self] in self?.dialog = nil })
            } else {
                fieldErrors[field ?? ""] = WorkflowWords.issue(code ?? "", message)
            }
        } catch DaemonError.server(_, let code, let message) {
            if code == "confirm_paid" {
                dialog = GlassDialog(eyebrow: "Costs money", title: "Let it run on a paid provider?", line: "Each run is billed by that provider, not by your plan.", primary: "Let it", confirm: { [weak self] _ in
                    guard let self else { return }
                    self.dialog = nil
                    Task { if await self.save(draft, confirmPaid: true) != nil { self.onSaved?() } }
                }, cancel: { [weak self] in self?.dialog = nil })
            } else { error = WorkflowWords.issue(code ?? "", message) }
        } catch { self.error = error.localizedDescription }
        return nil
    }
    /// What the editor does after a save that came through the paid-provider dialog.
    var onSaved: (() -> Void)?

    func delete(_ id: String) async -> Bool {
        do { try await DaemonClient.shared.deleteAutomation(id); automations.removeAll { $0.id == id }; return true } catch { self.error = error.localizedDescription; return false }
    }

    func setEnabled(_ id: String, _ on: Bool) {
        if let i = automations.firstIndex(where: { $0.id == id }) { automations[i].enabled = on }
        Task {
            do { try await DaemonClient.shared.setAutomationEnabled(id, on) } catch { self.error = error.localizedDescription; load() }
        }
    }

    func runNow(_ id: String) {
        Task {
            do { _ = try await DaemonClient.shared.runAutomation(id); load(); loadRuns(id) }
            catch DaemonError.server(_, _, let message) { self.error = message }
            catch { self.error = error.localizedDescription }
        }
    }

    func retry(_ run: WFRun, fromStep: String?) {
        Task {
            do { _ = try await DaemonClient.shared.retryRun(run.id, fromStep: fromStep); load(); if let a = run.automationId { loadRuns(a) } }
            catch DaemonError.detail(_, let code, let message, _, _) { self.error = WorkflowWords.issue(code ?? "", message) }
            catch DaemonError.server(_, let code, let message) { self.error = code == "active" ? "It is still running." : message }
            catch { self.error = error.localizedDescription }
        }
    }

    func newToken(_ a: WFAutomation) {
        Task {
            do {
                let r = try await DaemonClient.shared.newAutomationToken(a.id)
                if let au = r.automation, let i = automations.firstIndex(where: { $0.id == au.id }) { automations[i] = au }
                if let t = r.token { tokenCard = TokenCard(automationId: a.id, name: a.name, token: t, url: r.automation?.trigger.url ?? a.trigger.url ?? "", command: r.automation?.trigger.command ?? a.trigger.command ?? "") }
            } catch { self.error = error.localizedDescription }
        }
    }

    /// The cron on screen, checked on the Mac as it's typed (after a short pause).
    func checkCron(_ t: WFTrigger) {
        cronTask?.cancel()
        guard !sample else { return }
        cronChecking = true
        cronTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            do {
                cronNext = try await DaemonClient.shared.checkTrigger(t)
                cronProblem = nil
            } catch DaemonError.detail(_, let code, let message, _, _) {
                cronNext = []; cronProblem = WorkflowWords.issue(code ?? "", message)
            } catch DaemonError.server(_, let code, let message) {
                cronNext = []; cronProblem = WorkflowWords.issue(code ?? "", message)
            } catch { cronNext = []; cronProblem = nil }
            cronChecking = false
        }
    }

    // MARK: Samples (renders)

    func loadSample(_ list: [WFAutomation], features: [String], runs: [String: [WFRun]] = [:], providers: [Provider] = [], codex: [CodexModel]? = nil) {
        sample = true
        automations = list; self.features = features; self.runs = runs; self.providers = providers; loaded = true
        sampleCodexOn = codex != nil
        codexModels = codex ?? []
    }
}

@MainActor
extension AppModel {
    /// Answers an ask step from the popover, as "mac". The first answer counts: one already given
    /// elsewhere is said calmly.
    func answerAsk(_ item: WFAskItem, _ answer: String) {
        Task {
            do {
                _ = try await DaemonClient.shared.answerRun(item.runId, stepId: item.stepId, answer: answer)
            } catch DaemonError.detail(_, let code, let message, _, let run) {
                show(code == "not_asking" ? WorkflowWords.notAsking(run) : WorkflowWords.issue(code ?? "", message))
            } catch DaemonError.server(_, let code, let message) {
                show(code == "not_asking" ? WorkflowWords.notAsking(nil) : message)
            } catch { show("Couldn’t send your answer. \(error.localizedDescription)") }
            await refresh()
        }
    }

    /// Asks waiting for an answer, oldest first.
    var automationAsks: [WFAskItem] { (automations?.asks ?? []).sorted { $0.until < $1.until } }
}
