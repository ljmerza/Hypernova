import AppKit
import SwiftUI

// The workflow editor (docs/DESIGN.md, "Workflows"): the phone's short list in the Mac's window,
// WHEN, THEN, TELL, BUDGET, then RUNS. What the daemon doesn't list isn't offered.

private func pickFolder(_ then: @escaping (String) -> Void) {
    let p = NSOpenPanel()
    p.canChooseDirectories = true; p.canChooseFiles = false; p.allowsMultipleSelection = false
    p.directoryURL = URL(fileURLWithPath: Paths.home.path + "/Programming")
    if p.runModal() == .OK, let u = p.url { then(u.path) }
}

/// A field for a folder, with Choose.
private struct PathField: View {
    var label: String
    @Binding var text: String
    var placeholder = "~/Programming/project"
    var problem: String?
    var note: String?
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            HNField(label: label, text: $text, placeholder: placeholder, mono: true, hint: problem ?? note, invalid: problem != nil)
            PopoverPill(title: "Choose", kind: .outline, fill: false, height: 28) { pickFolder { text = $0 } }.padding(.top, 22)
        }
    }
}

struct WorkflowEditor: View {
    @ObservedObject var model: AutomationsModel
    @State var draft: WFDraft
    var original: WFDraft
    var close: () -> Void
    @State private var openStep: String?
    @State private var openOutput: String?
    @State private var savedOnce = false

    init(model: AutomationsModel, draft: WFDraft, close: @escaping () -> Void, startOpen: String? = nil) {
        self.model = model; _draft = State(initialValue: draft); original = draft; self.close = close
        _openStep = State(initialValue: startOpen ?? (draft.steps.count == 1 ? draft.steps.first?.id : nil))
    }

    private var dirty: Bool { draft != original }
    private var saved: WFAutomation? { model.automation(draft.id) }

    var body: some View {
        VStack(spacing: 0) {
            header
            HNScroll {
                VStack(alignment: .leading, spacing: 20) {
                    Text(WorkflowWords.sentence(asAutomation)).font(HNFont.sans(14)).foregroundStyle(Color.hnInk).fixedSize(horizontal: false, vertical: true)
                    if let e = model.error { WFNote(text: e, tone: .hnAmber) }
                    whenSection
                    thenSection
                    tellSection
                    if model.offers(Feature.budget) { budgetSection }
                    if draft.id != nil { runsSection }
                    if draft.id != nil { footer }
                }
                .padding(.horizontal, 24).padding(.top, 8).padding(.bottom, 28)
            }
        }
        .onChange(of: model.fieldErrors) { errs in revealFirstError(errs) }
        .onChange(of: model.tokenCard) { card in if card == nil && savedOnce { close() } }
        .onAppear { model.onSaved = { savedOnce = true; if model.tokenCard == nil { close() } } }
    }

    private var asAutomation: WFAutomation {
        WFAutomation(id: draft.id ?? "", name: draft.name, enabled: draft.enabled, trigger: draft.trigger, steps: draft.steps, outputs: draft.outputs, budget: draft.budget)
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            Button { leave() } label: { Text("‹").font(HNFont.sans(22)).foregroundStyle(Color.hnMuted).frame(width: 28, height: 28).contentShape(Rectangle()) }
                .buttonStyle(.plain).accessibilityLabel("Back")
            if SnapshotMode.active {
                Text(draft.name.isEmpty ? "Name it" : draft.name).font(HNFont.sans(18, .medium)).foregroundStyle(Color.hnInk).frame(maxWidth: .infinity, alignment: .leading)
            } else {
                TextField("", text: $draft.name, prompt: Text("Name it").foregroundColor(.hnGhost))
                    .textFieldStyle(.plain).font(HNFont.sans(18, .medium)).foregroundStyle(Color.hnInk)
            }
            if let id = draft.id {
                PopoverPill(title: "Run now", kind: .outline, fill: false, height: 28) { model.runNow(id) }
            }
            PopoverPill(title: "Save", kind: .primary, busy: model.saving, fill: false, height: 28) { save() }.disabled(draft.name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.leading, 20).padding(.trailing, 24).padding(.top, 40).padding(.bottom, 12)
        .overlay(alignment: .bottom) { Rectangle().fill(Color.hnLine).frame(height: 1) }
        .overlay(alignment: .bottomLeading) { if let p = model.fieldErrors["name"] { WFNote(text: p, tone: .hnDanger).padding(.leading, 56).offset(y: 16) } }
    }

    private func leave() {
        if !dirty { close(); return }
        model.dialog = GlassDialog(eyebrow: "Unsaved changes", title: "Discard changes?", line: "What you changed here isn’t saved yet.", accent: .hnDanger, secondary: "Keep editing", primary: "Discard",
                                   confirm: { _ in model.dialog = nil; close() }, cancel: { model.dialog = nil })
    }

    private func save() {
        Task { if await model.save(draft) != nil { savedOnce = true; if model.tokenCard == nil { close() } } }
    }

    private func revealFirstError(_ errs: [String: String]) {
        for k in errs.keys.sorted() {
            if let i = Self.index(k, "steps"), draft.steps.indices.contains(i) { withAnimation(.hnExpo(0.3)) { openStep = draft.steps[i].id }; return }
            if let i = Self.index(k, "outputs"), draft.outputs.indices.contains(i) { withAnimation(.hnExpo(0.3)) { openOutput = draft.outputs[i].id }; return }
        }
    }
    static func index(_ key: String, _ block: String) -> Int? {
        guard key.hasPrefix(block + "["), let close = key.firstIndex(of: "]") else { return nil }
        return Int(key[key.index(key.startIndex, offsetBy: block.count + 1)..<close])
    }

    /// The words for a block (`steps[2]`), whatever field of it the daemon pointed at.
    private func problem(_ block: String) -> String? {
        model.fieldErrors.filter { $0.key == block || $0.key.hasPrefix(block + ".") || $0.key.hasPrefix(block + "[") }.sorted { $0.key < $1.key }.first?.value
    }
    private func problem(exact key: String) -> String? { model.fieldErrors[key] }

    // MARK: WHEN

    private static let triggerTitles: [(kind: String, feature: String, title: String)] = [
        ("cron", Feature.triggerCron, "On a cron schedule"), ("webhook", Feature.triggerWebhook, "A script or webhook fires it"),
        ("file", Feature.triggerFile, "Files change"), ("github", Feature.triggerGithub, "Something on GitHub"), ("hook", Feature.triggerHook, "A Claude Code hook"),
    ]

    private var whenSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            Seam(label: "When")
            VStack(alignment: .leading, spacing: 12) {
                let t = draft.trigger
                let title = Self.triggerTitles.first { $0.kind == t.kind }?.title ?? WorkflowWords.triggerPhrase(t)
                WFMenu(title: "When", value: title) {
                    ForEach(Self.triggerTitles.filter { model.offers($0.feature) }, id: \.kind) { o in
                        Button(o.title) { if draft.trigger.kind != o.kind { draft.trigger = .make(o.kind); if o.kind == "cron" { model.checkCron(draft.trigger) } } }
                    }
                }
                if !t.isEditable { WFNote(text: "This one is edited on your phone. It is saved as it is.") }
                switch t.kind {
                case "webhook": WebhookForm(model: model, trigger: t, saved: saved)
                case "cron": CronForm(model: model, trigger: $draft.trigger, problem: problem("trigger"))
                case "file": FileForm(trigger: $draft.trigger, problem: problem("trigger"), globProblem: problem(exact: "trigger.glob"))
                case "github": GithubForm(trigger: $draft.trigger, problem: problem("trigger"))
                case "hook": HookForm(trigger: $draft.trigger, problem: problem("trigger"))
                default: EmptyView()
                }
            }
            .padding(14).background(blockBackground(problem("trigger") != nil))
        }
    }

    func blockBackground(_ bad: Bool = false) -> some View {
        RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.hnBg800.opacity(0.45))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(bad ? Color.hnAmber.opacity(0.5) : Color.hnInk.opacity(0.08), lineWidth: 1))
    }

    // MARK: THEN

    private var kinds: [String] {
        [WFStep.start, WFStep.continue] + (model.offers(Feature.ask) ? [WFStep.ask] : []) + (model.offers(Feature.shell) ? [WFStep.shell] : []) + [WFStep.notify, WFStep.say]
    }
    private var maxSteps: Int { model.offers(Feature.steps) ? 5 : 1 }

    private var thenSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            Seam(label: "Then", trail: draft.steps.count > 1 ? WorkflowWords.stepsTag(draft.steps.count) : nil)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(draft.steps.enumerated()), id: \.element.id) { i, s in
                    StepCard(model: model, draft: $draft, index: i, isOpen: openStep == s.id || draft.steps.count == 1, bad: problem("steps[\(i)]"), fields: model.fieldErrors, kinds: kinds,
                             toggle: { withAnimation(.hnExpo(0.3)) { openStep = openStep == s.id ? nil : s.id } })
                    if i < draft.steps.count - 1 { Rectangle().fill(Color.hnLine).frame(width: 1, height: 10).padding(.leading, 25) }
                }
            }
            if let p = problem(exact: "steps") { WFNote(text: p, tone: .hnAmber) }
            if draft.steps.count < maxSteps {
                WFAddMenu(title: "Add a step", items: kinds) { k in
                    var s = WFStep.make(k, project: draft.steps.compactMap(\.projectPath).last ?? "", providerId: model.defaultProviderId, after: draft.steps)
                    if draft.steps.isEmpty { s.if = nil }
                    withAnimation(.hnExpo(0.3)) { draft.steps.append(s); openStep = s.id }
                }
            }
        }
    }

    // MARK: TELL

    private var tellSection: some View {
        VStack(alignment: .leading, spacing: 9) {
            Seam(label: "Tell")
            if draft.outputs.isEmpty { WFNote(text: "Nothing is sent when it’s done; its runs still show here.") }
            ForEach(Array(draft.outputs.enumerated()), id: \.element.id) { i, o in
                OutputCard(model: model, draft: $draft, index: i, isOpen: openOutput == o.id, fields: model.fieldErrors, bad: problem("outputs[\(i)]"),
                           toggle: { withAnimation(.hnExpo(0.3)) { openOutput = openOutput == o.id ? nil : o.id } })
            }
            if let p = problem(exact: "outputs") { WFNote(text: p, tone: .hnAmber) }
            if draft.outputs.count < 2 {
                let kinds = [WFOutput.notify, WFOutput.say] + (model.offers(Feature.outputWebhook) ? [WFOutput.webhook] : []) + (model.offers(Feature.outputFile) ? [WFOutput.file] : [])
                WFAddMenu(title: "Add a place to tell", items: kinds, name: { WorkflowWords.outputTitle($0) }) { k in
                    let o = WFOutput.make(k)
                    withAnimation(.hnExpo(0.3)) { draft.outputs.append(o); openOutput = o.id }
                }
            }
        }
    }

    // MARK: BUDGET

    private var budgetSection: some View {
        let on = draft.budget != nil
        let minutes = draft.budget?.minutes, pct = draft.budget?.windowPct
        let minOptions = Self.withValue([nil, 15, 30, 60, 120], minutes), pctOptions = Self.withValue([nil, 10, 25, 50], pct)
        return VStack(alignment: .leading, spacing: 9) {
            Seam(label: "Budget")
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Limit each run").font(HNFont.sans(13)).foregroundStyle(Color.hnInk)
                        WFNote(text: "It stops when it works too long, or spends too much of your 5-hour window.")
                    }
                    Spacer(minLength: 8)
                    HNSwitch(isOn: on, label: "Limit each run") { v in draft.budget = v ? WFBudget(minutes: 30, windowPct: nil) : nil }
                }
                if on {
                    WFLabel("Time at work")
                    WFSegment(options: minOptions.map { $0.map(Self.minutesWord) ?? "Off" }, selected: minOptions.firstIndex(of: minutes)) { i in setBudget(minutes: minOptions[i], pct: pct) }
                    WFLabel("Of the 5-hour window")
                    WFSegment(options: pctOptions.map { $0.map { "\($0)%" } ?? "Off" }, selected: pctOptions.firstIndex(of: pct)) { i in setBudget(minutes: minutes, pct: pctOptions[i]) }
                    if let p = problem("budget") { WFNote(text: p, tone: .hnAmber) }
                }
            }
            .padding(14).background(blockBackground(problem("budget") != nil))
        }
    }
    private func setBudget(minutes: Int?, pct: Int?) { draft.budget = (minutes == nil && pct == nil) ? WFBudget(minutes: 30, windowPct: nil) : WFBudget(minutes: minutes, windowPct: pct) }
    static func withValue(_ base: [Int?], _ v: Int?) -> [Int?] { guard let v, !base.contains(v) else { return base }; return (base + [v]).sorted { ($0 ?? 0) < ($1 ?? 0) } }
    static func minutesWord(_ m: Int) -> String { m % 60 == 0 && m >= 60 ? "\(m / 60)h" : "\(m)m" }

    // MARK: RUNS

    private var runsSection: some View {
        let list = model.runs[draft.id ?? ""] ?? []
        return VStack(alignment: .leading, spacing: 4) {
            Seam(label: "Runs", trail: saved.map { ($0.runCount ?? 0) > 0 ? "\($0.runCount ?? 0) runs" : "none yet" })
            if list.isEmpty { WFNote(text: "None yet.") }
            ForEach(Array(list.prefix(8).enumerated()), id: \.element.id) { i, r in
                RunRow(model: model, run: r, automation: saved, draft: draft, startOpen: i == 0)
            }
        }
        .onAppear { if let id = draft.id { model.loadRuns(id) } }
    }

    private var footer: some View {
        HStack(spacing: 8) {
            Spacer()
            PopoverPill(title: "Delete", kind: .danger, fill: false) {
                model.dialog = GlassDialog(eyebrow: "Delete", title: "Delete “\(draft.name)”?", line: "Its runs go too. The sessions it started stay.", accent: .hnDanger, secondary: "Keep", primary: "Delete",
                                           confirm: { _ in model.dialog = nil; Task { if let id = draft.id, await model.delete(id) { close() } } }, cancel: { model.dialog = nil })
            }
        }
    }
}

extension WorkflowWords {
    static func outputTitle(_ kind: String) -> String {
        switch kind { case "notify": return "Notify me"; case "say": return "Read aloud on my glasses"; case "webhook": return "Post to a webhook"; default: return "Add to a file" }
    }
}

/// `+ Add a step`: an outline pill that opens the kinds.
struct WFAddMenu: View {
    var title: String
    var items: [String]
    var name: (String) -> String = { WorkflowWords.kindTitle($0) }
    var pick: (String) -> Void
    var body: some View {
        let label = HStack(spacing: 7) {
            Text("+").font(HNFont.sans(14)).foregroundStyle(Color.hnAccent)
            Text(title).font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnInk)
        }
        .padding(.horizontal, 16).frame(height: 32)
        .background(Capsule().fill(Color.hnBg800.opacity(0.3)))
        .overlay(Capsule().strokeBorder(Color.hnInk.opacity(0.22), lineWidth: 1))
        if SnapshotMode.active { label } else {
            Menu { ForEach(items, id: \.self) { k in Button(name(k)) { pick(k) } } } label: { label }.menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
        }
    }
}

// MARK: - Steps

private struct StepCard: View {
    @ObservedObject var model: AutomationsModel
    @Binding var draft: WFDraft
    var index: Int
    var isOpen: Bool
    var bad: String?
    var fields: [String: String]
    var kinds: [String]
    var toggle: () -> Void

    private var step: WFStep { draft.steps[index] }
    private func set(_ f: (inout WFStep) -> Void) { f(&draft.steps[index]) }
    private func field(_ k: String) -> String? { fields["steps[\(index)].\(k)"] }

    var body: some View {
        let s = step
        VStack(alignment: .leading, spacing: 0) {
            Button(action: toggle) {
                HStack(alignment: .top, spacing: 12) {
                    Text("\(index + 1)").font(HNFont.mono(11)).foregroundStyle(isOpen ? Color.hnAccent : Color.hnMuted)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Color.hnBg800.opacity(0.8)))
                        .overlay(Circle().strokeBorder(isOpen ? Color.hnAccent.opacity(0.55) : Color.hnInk.opacity(0.12), lineWidth: 1))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(WorkflowWords.stepTitle(s)).font(HNFont.sans(13.5, .semibold)).foregroundStyle(Color.hnInk)
                        if !isOpen, let d = WorkflowWords.detail(s) {
                            Text(d).font(s.kind == WFStep.shell ? HNFont.monoM : HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(1).truncationMode(.tail)
                        }
                        if index > 0, !isOpen, let g = s.if {
                            HStack(spacing: 5) { Text("└").font(HNFont.mono(11)).foregroundStyle(Color.hnAccentDim); Text(WorkflowWords.guardText(g)).font(HNFont.sans(11.5)).foregroundStyle(Color.hnAccentDim) }
                        }
                        if let bad, !isOpen { WFNote(text: bad, tone: .hnAmber) }
                    }
                    Spacer(minLength: 6)
                    Text(isOpen ? "⌃" : "›").font(HNFont.sans(13)).foregroundStyle(Color.hnDim).rotationEffect(.degrees(isOpen ? 0 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Step \(index + 1): \(WorkflowWords.stepTitle(s))")
            if isOpen {
                VStack(alignment: .leading, spacing: 12) {
                    if index > 0 { conditionRow }
                    form
                    if let bad { WFNote(text: bad, tone: .hnAmber) }
                    Rectangle().fill(Color.hnLine).frame(height: 1)
                    foot
                }
                .padding(.top, 12)
            }
        }
        .padding(13)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.hnBg800.opacity(0.5))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(bad != nil ? Color.hnAmber.opacity(0.5) : Color.hnInk.opacity(isOpen ? 0.14 : 0.08), lineWidth: 1)))
    }

    private var earlierAsk: WFStep? { draft.steps.prefix(index).last { $0.kind == WFStep.ask } }

    @ViewBuilder private var conditionRow: some View {
        let s = step
        WFMenu(title: "Condition", value: WorkflowWords.guardText(s.if)) {
            ForEach(Array(WorkflowWords.guardChoices(answers: earlierAsk?.answers ?? []).enumerated()), id: \.offset) { _, g in
                Button(WorkflowWords.guardText(g)) { set { $0.if = g } }
            }
        }
        if s.if?.on == WFGuard.contains {
            HNField(label: "Words to look for", text: Binding(get: { step.if?.text ?? "" }, set: { v in set { $0.if = WFGuard(on: WFGuard.contains, text: v) } }),
                    placeholder: "failed", hint: fields["steps[\(index)].if.text"] ?? "Plain words, any case, anywhere in its whole reply or output; not a pattern.", invalid: fields["steps[\(index)].if.text"] != nil)
        } else if let p = fields["steps[\(index)].if"] { WFNote(text: p, tone: .hnDanger) }
    }

    @ViewBuilder private var form: some View {
        let s = step
        let trigger = draft.trigger
        switch s.kind {
        case WFStep.start:
            PathField(label: "Project", text: bind(\.projectPath), problem: field("projectPath"))
            WFPromptField(label: "Prompt", text: bind(\.prompt), placeholder: "Summarise what changed since yesterday.", problem: field("prompt"),
                          tokens: WorkflowWords.placeholders(for: s.permissionMode == "bypassPermissions" ? .bypassPrompt : .prompt, steps: draft.steps, index: index, trigger: trigger, stepOutput: model.offers(Feature.stepOutput)), steps: draft.steps)
            // Claude Code or Codex: only while Codex is on, or for a step Codex already runs (so it can go back).
            if model.codexOn || s.isCodex {
                WFLabel("Agent")
                WFSegment(options: ["Claude Code", "Codex"], selected: s.isCodex ? 1 : 0) { i in
                    if (i == 1) != s.isCodex { set { $0.runOn(codex: i == 1, claude: model.defaultProviderId) } }
                }
            }
            if s.isCodex {
                codexForm
            } else {
                WFMenu(title: "Permissions", value: Self.modes.first { $0.0 == s.permissionMode }?.1 ?? "Allowed only") {
                    ForEach(Self.modes, id: \.0) { m in Button(m.1) { set { $0.permissionMode = m.0; if m.0 == "auto" || m.0 == "bypassPermissions" { $0.allowedTools = [] } } } }
                }
                if s.permissionMode == "bypassPermissions" { WFNote(text: "Nothing waits for you. Saving it asks for Touch ID or your password, every time.", tone: .hnAmber) }
                if model.providers.count > 1 {
                    WFMenu(title: "Runs on", value: model.providerName(s.providerId) ?? "Claude Code") {
                        ForEach(model.providers) { p in Button(p.name) { set { $0.providerId = p.id; $0.model = nil } } }
                    }
                }
            }
        case WFStep.continue:
            WFMenu(title: "Session", value: targetName(s.target ?? "trigger")) {
                Button("The session that fired it") { set { $0.target = "trigger" } }
                Button("Every session at work") { set { $0.target = "working" } }
                Button("Every session a limit stopped") { set { $0.target = "limited" } }
                ForEach(Array(draft.steps.prefix(index).enumerated()).filter { $0.element.kind == WFStep.start || $0.element.kind == WFStep.continue }, id: \.element.id) { i, e in
                    Button("The session from step \(i + 1)") { set { $0.target = "step:\(e.id)" } }
                }
            }
            if let p = field("target") { WFNote(text: p, tone: .hnDanger) }
            WFPromptField(label: "Prompt", text: bind(\.prompt), placeholder: "Carry on.", problem: field("prompt"), tokens: WorkflowWords.placeholders(for: .prompt, steps: draft.steps, index: index, trigger: trigger, stepOutput: model.offers(Feature.stepOutput)), steps: draft.steps)
        case WFStep.ask: askForm
        case WFStep.shell:
            PathField(label: "Project", text: bind(\.projectPath), problem: field("projectPath"))
            WFPromptField(label: "Command", text: bind(\.command), placeholder: "npm test", mono: true, problem: field("command"),
                          tokens: WorkflowWords.placeholders(for: .command, steps: draft.steps, index: index, trigger: trigger, stepOutput: model.offers(Feature.stepOutput)), steps: draft.steps)
            WFLabel("Stops after")
            let secs = StepCard.secondsOptions(s.timeoutSec ?? 60)
            WFSegment(options: secs.map(Self.secondsWord), selected: secs.firstIndex(of: s.timeoutSec ?? 60)) { i in set { $0.timeoutSec = secs[i] } }
            WFNote(text: "Runs with /bin/sh in the project folder, without a terminal. Words from other sessions or a webhook reach it only through its environment.")
            WFNote(text: "Saving it asks for Touch ID or your password, every time.", tone: .hnAmber)
        case WFStep.say:
            WFPromptField(label: "Text", text: bind(\.text), placeholder: "Release notes are ready.", problem: field("text"), tokens: WorkflowWords.placeholders(for: .text, steps: draft.steps, index: index, trigger: trigger, stepOutput: model.offers(Feature.stepOutput)), steps: draft.steps)
        default:
            WFPromptField(label: "Text", text: bind(\.text), placeholder: "Tests still failing: {prev}", problem: field("text"), tokens: WorkflowWords.placeholders(for: .text, steps: draft.steps, index: index, trigger: trigger, stepOutput: model.offers(Feature.stepOutput)), steps: draft.steps)
            HStack { Text("Also on my glasses").font(HNFont.sans(12.5)).foregroundStyle(Color.hnMuted); Spacer(); HNSwitch(isOn: s.glasses == true, label: "Also on my glasses") { v in set { $0.glasses = v } } }
        }
    }

    static let modes: [(String, String)] = [("dontAsk", "Allowed only"), ("default", "Ask me"), ("acceptEdits", "Edits on its own"), ("auto", "Auto"), ("bypassPermissions", "No asking")]

    /// A Codex start step: its sandbox (it asks nobody, so what it may do is set here) and its model.
    @ViewBuilder private var codexForm: some View {
        let s = step
        let edits = s.permissionMode == WFStep.codexModes[1]
        WFLabel("Permissions")
        WFSegment(options: ["Read only", "Can edit"], selected: edits ? 1 : 0) { i in set { $0.permissionMode = WFStep.codexModes[i]; $0.allowedTools = [] } }
        // The phone's words (strings_codex.xml, cx_au_*).
        WFNote(text: edits ? "Edits files and runs commands in its folder, nothing outside it." : "Reads the folder and runs commands that change nothing.")
        WFNote(text: "In an automation Codex asks nobody: what its mode doesn’t allow, it doesn’t do.")
        WFMenu(title: "Model", value: model.codexModelName(s.model) ?? "Codex’s default") {
            Button("Codex’s default") { set { $0.model = nil } }
            ForEach(model.codexModels) { m in Button(m.title) { set { $0.model = m.value } } }
        }
        .onAppear { model.loadCodexModels() }
    }

    static func secondsOptions(_ cur: Int) -> [Int] { let b = [30, 60, 300, 600]; return b.contains(cur) ? b : (b + [cur]).sorted() }
    static func secondsWord(_ s: Int) -> String { s >= 60 && s % 60 == 0 ? "\(s / 60)m" : "\(s)s" }

    private func targetName(_ t: String) -> String {
        switch t {
        case "trigger": return "The session that fired it"
        case "working": return "Every session at work"
        case "limited": return "Every session a limit stopped"
        default:
            if t.hasPrefix("step:"), let i = draft.steps.firstIndex(where: { "step:\($0.id)" == t }) { return "The session from step \(i + 1)" }
            return "A session that’s gone"
        }
    }

    private func bind(_ kp: WritableKeyPath<WFStep, String?>) -> Binding<String> {
        Binding(get: { draft.steps[index][keyPath: kp] ?? "" }, set: { draft.steps[index][keyPath: kp] = $0 })
    }

    @State private var newAnswer = ""
    @ViewBuilder private var askForm: some View {
        let s = step
        let answers = s.answers ?? []
        WFPromptField(label: "Question", text: bind(\.question), placeholder: "Post this review?", problem: field("question"), tokens: WorkflowWords.placeholders(for: .text, steps: draft.steps, index: index, trigger: draft.trigger, stepOutput: model.offers(Feature.stepOutput)), steps: draft.steps)
        WFLabel("Answers")
        WFFlow {
            ForEach(answers, id: \.self) { a in
                WFChip(text: a, remove: answers.count > 2 ? { set { $0.answers?.removeAll { $0 == a }; if $0.default == a { $0.default = $0.answers?.last } } } : nil)
            }
        }
        if answers.count < 3 {
            HStack(spacing: 8) {
                HNField(label: "", text: $newAnswer, placeholder: "Another answer")
                PopoverPill(title: "Add", kind: .outline, fill: false, height: 28) {
                    let t = newAnswer.trimmingCharacters(in: .whitespaces)
                    if !t.isEmpty { set { $0.answers = (answers + [t]) }; newAnswer = "" }
                }.padding(.top, 2)
            }
        }
        if let p = field("answers") { WFNote(text: p, tone: .hnDanger) }
        WFLabel("If nobody answers")
        WFSegment(options: answers, selected: answers.firstIndex(of: s.default ?? "")) { i in set { $0.default = answers[i] } }
        WFLabel("It waits")
        let waits = [5, 10, 30, 60].contains(s.timeoutMin ?? 10) ? [5, 10, 30, 60] : ([5, 10, 30, 60] + [s.timeoutMin ?? 10]).sorted()
        WFSegment(options: waits.map { "\($0) min" }, selected: waits.firstIndex(of: s.timeoutMin ?? 10)) { i in set { $0.timeoutMin = waits[i] } }
        WFNote(text: "Your phone, glasses and Mac ask; the first answer counts. The run keeps its slot while it waits.")
    }

    private var foot: some View {
        HStack(spacing: 12) {
            if SnapshotMode.active { Text("Change").font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnAccent) } else {
                Menu {
                    ForEach(kinds, id: \.self) { k in
                        Button(WorkflowWords.kindTitle(k)) {
                            var n = WFStep.make(k, project: step.projectPath ?? "", providerId: step.providerId ?? model.defaultProviderId)
                            n.id = step.id; n.if = step.if
                            set { $0 = n }
                        }
                    }
                } label: { Text("Change").font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnAccent) }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            }
            Spacer()
            Button { move(-1) } label: { Text("↑").font(HNFont.sans(14)).frame(width: 28, height: 28).contentShape(Rectangle()) }.buttonStyle(.plain).foregroundStyle(index == 0 ? Color.hnGhost : Color.hnMuted).disabled(index == 0).accessibilityLabel("Move up")
            Button { move(1) } label: { Text("↓").font(HNFont.sans(14)).frame(width: 28, height: 28).contentShape(Rectangle()) }.buttonStyle(.plain).foregroundStyle(index == draft.steps.count - 1 ? Color.hnGhost : Color.hnMuted).disabled(index == draft.steps.count - 1).accessibilityLabel("Move down")
            if draft.steps.count > 1 {
                Button { withAnimation(.hnExpo(0.3)) { _ = draft.steps.remove(at: index); draft.steps[0].if = nil } } label: { Text("Remove").font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnDanger) }.buttonStyle(.plain)
            }
        }
    }

    private func move(_ d: Int) {
        let j = index + d
        guard draft.steps.indices.contains(j) else { return }
        withAnimation(.hnExpo(0.3)) { draft.steps.swapAt(index, j); draft.steps[0].if = nil }
    }
}

/// A labelled text area with its placeholder chips.
private struct WFPromptField: View {
    var label: String
    @Binding var text: String
    var placeholder = ""
    var mono = false
    var problem: String?
    var tokens: [String]
    /// The workflow's steps: an earlier step's chip reads "Step 2’s result".
    var steps: [WFStep] = []
    @StateObject private var cursor = WFCursor()
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            WFLabel(label)
            WFTextArea(text: $text, placeholder: placeholder, mono: mono, minHeight: mono ? 52 : 64, invalid: problem != nil, cursor: cursor)
            if let problem { WFNote(text: problem, tone: .hnDanger) }
            WFPlaceholders(tokens: tokens, cursor: cursor, steps: steps)
        }
    }
}

// MARK: - Outputs

private struct OutputCard: View {
    @ObservedObject var model: AutomationsModel
    @Binding var draft: WFDraft
    var index: Int
    var isOpen: Bool
    var fields: [String: String]
    var bad: String?
    var toggle: () -> Void
    @StateObject private var cursor = WFCursor()
    @State private var replacing = false

    private var o: WFOutput { draft.outputs[index] }
    private func set(_ f: (inout WFOutput) -> Void) { f(&draft.outputs[index]) }
    private func field(_ k: String) -> String? { fields["outputs[\(index)].\(k)"] }
    private func bind(_ kp: WritableKeyPath<WFOutput, String?>) -> Binding<String> { Binding(get: { draft.outputs[index][keyPath: kp] ?? "" }, set: { draft.outputs[index][keyPath: kp] = $0 }) }

    var body: some View {
        let o = o
        VStack(alignment: .leading, spacing: 0) {
            Button(action: toggle) {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(WorkflowWords.outputName(o, kind: o.kind)).font(HNFont.sans(13.5, .semibold)).foregroundStyle(Color.hnInk)
                        HStack(spacing: 8) {
                            if o.kind == WFOutput.webhook, let h = o.host, !h.isEmpty {
                                Text(h).font(HNFont.monoM).foregroundStyle(Color.hnMuted)
                                if let l = o.last4, !l.isEmpty { WFChip(text: "••••\(l)") }
                            } else {
                                Text(line(o)).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).lineLimit(1)
                            }
                        }
                        if let bad, !isOpen { WFNote(text: bad, tone: .hnAmber) }
                    }
                    Spacer(minLength: 6)
                    Text(isOpen ? "⌃" : "›").font(HNFont.sans(13)).foregroundStyle(Color.hnDim)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            if isOpen {
                VStack(alignment: .leading, spacing: 12) {
                    form
                    if let bad { WFNote(text: bad, tone: .hnAmber) }
                    Rectangle().fill(Color.hnLine).frame(height: 1)
                    HStack { Spacer(); Button { withAnimation(.hnExpo(0.3)) { _ = draft.outputs.remove(at: index) } } label: { Text("Remove").font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnDanger) }.buttonStyle(.plain) }
                }.padding(.top, 12)
            }
        }
        .padding(13)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.hnBg800.opacity(0.5))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(bad != nil ? Color.hnAmber.opacity(0.5) : Color.hnInk.opacity(isOpen ? 0.14 : 0.08), lineWidth: 1)))
    }

    private func line(_ o: WFOutput) -> String {
        switch o.kind {
        case WFOutput.file: return (o.path ?? "").isEmpty ? "Each run adds one line" : o.path!
        case WFOutput.webhook: return "Not set up yet"
        default: return (o.text ?? "").isEmpty ? "The run’s result" : WorkflowWords.cut(o.text ?? "", 70)
        }
    }

    @ViewBuilder private var form: some View {
        let o = o
        let tokens = WorkflowWords.placeholders(for: .text, steps: draft.steps, index: draft.steps.count, trigger: draft.trigger, stepOutput: model.offers(Feature.stepOutput))
        switch o.kind {
        case WFOutput.webhook: webhookForm(o)
        case WFOutput.file:
            HNField(label: "File", text: bind(\.path), placeholder: "notes/briefs.md", mono: true, hint: field("path") ?? "Each run adds one line: the date, its name and what it said.", invalid: field("path") != nil)
        default:
            VStack(alignment: .leading, spacing: 7) {
                WFLabel("Text")
                WFTextArea(text: bind(\.text), placeholder: "Leave it empty to send the run’s result", minHeight: 52, invalid: field("text") != nil, cursor: cursor)
                if let p = field("text") { WFNote(text: p, tone: .hnDanger) }
                WFPlaceholders(tokens: tokens, cursor: cursor, steps: draft.steps)
            }
            if o.kind == WFOutput.notify {
                HStack { Text("Also on my glasses").font(HNFont.sans(12.5)).foregroundStyle(Color.hnMuted); Spacer(); HNSwitch(isOn: o.glasses == true, label: "Also on my glasses") { v in set { $0.glasses = v } } }
            }
        }
    }

    static let presets = ["ntfy", "slack", "discord", "telegram", "post"]
    @ViewBuilder private func webhookForm(_ o: WFOutput) -> some View {
        let kept = !(o.last4 ?? "").isEmpty && !replacing
        WFLabel("Service")
        WFSegment(options: ["ntfy", "Slack", "Discord", "Telegram", "POST"], selected: Self.presets.firstIndex(of: o.preset ?? "ntfy")) { i in
            set { $0.preset = Self.presets[i]; $0.url = nil; $0.botToken = nil; $0.chatId = nil }; replacing = true
        }
        if kept {
            HStack(spacing: 8) {
                Text(o.host ?? "").font(HNFont.monoM).foregroundStyle(Color.hnMuted)
                WFChip(text: "••••\(o.last4 ?? "")")
                Spacer()
                PopoverPill(title: "Replace", kind: .outline, fill: false, height: 26) { replacing = true }
            }
        } else if o.preset == "telegram" {
            HNField(label: "Bot token", text: bind(\.botToken), placeholder: "123456789:AA…", mono: true, secure: true, hint: field("botToken") ?? "A bot from @BotFather, and the chat it writes to: a number or @channel.", invalid: field("botToken") != nil)
            HNField(label: "Chat", text: bind(\.chatId), placeholder: "-1001234567890 or @channel", mono: true, hint: field("chatId"), invalid: field("chatId") != nil)
            WFNote(text: "Kept on your Mac only, never shown again.")
        } else {
            let hints = ["ntfy": "https://ntfy.sh/your-topic", "slack": "https://hooks.slack.com/services/…", "discord": "https://discord.com/api/webhooks/…"]
            let notes = ["ntfy": "The topic’s address, on ntfy.sh or your own server.", "slack": "An incoming webhook from your Slack app.", "discord": "A channel’s webhook, from its Integrations settings."]
            HNField(label: "Address", text: bind(\.url), placeholder: hints[o.preset ?? ""] ?? "https://example.com/hook", mono: true,
                    hint: field("url") ?? (notes[o.preset ?? ""] ?? "It gets JSON: the automation, the run and the text."), invalid: field("url") != nil)
            WFNote(text: "Kept on your Mac only, never shown again.")
        }
        VStack(alignment: .leading, spacing: 7) {
            WFLabel("Text")
            WFTextArea(text: bind(\.text), placeholder: "Leave it empty to send the run’s result", minHeight: 48, cursor: cursor)
        }
    }
}

// MARK: - Triggers

private struct WebhookForm: View {
    @ObservedObject var model: AutomationsModel
    var trigger: WFTrigger
    var saved: WFAutomation?
    @State private var copied = false
    var body: some View {
        if let a = saved, a.trigger.kind == "webhook", let last4 = a.trigger.last4 {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    WFLabel("Token"); WFChip(text: "••••\(last4)")
                    if let c = a.trigger.createdAt { Text("made \(Self.when(c))").font(HNFont.mono(11)).foregroundStyle(Color.hnDim) }
                }
                if let u = a.trigger.url { Text(u).font(HNFont.monoM).foregroundStyle(Color.hnMuted).textSelection(.enabled).fixedSize(horizontal: false, vertical: true) }
                HStack(spacing: 8) {
                    PopoverPill(title: copied ? "Copied" : "Copy the command", kind: .outline, fill: false, height: 28) {
                        Self.copy(a.trigger.command ?? ""); copied = true
                    }
                    PopoverPill(title: "New token", kind: .ghost, fill: false, height: 28) {
                        model.dialog = GlassDialog(eyebrow: "New token", title: "Make a new token?", line: "The old one stops working at once: scripts that use it need the new one.", accent: .hnAmber, secondary: "Cancel", primary: "Make a new one",
                                                   confirm: { _ in model.dialog = nil; model.newToken(a) }, cancel: { model.dialog = nil })
                    }
                }
            }
        } else {
            WFNote(text: "Save it to get its address and a token, shown once.")
        }
    }
    static func when(_ ms: Double) -> String {
        let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEE j:mm"); return f.string(from: Date(timeIntervalSince1970: ms / 1000))
    }
    static func copy(_ s: String, sensitive: Bool = false) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(s, forType: .string)
        if sensitive { pb.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) }
    }
}

private struct CronForm: View {
    @ObservedObject var model: AutomationsModel
    @Binding var trigger: WFTrigger
    var problem: String?
    var body: some View {
        let bad = problem ?? model.cronProblem
        VStack(alignment: .leading, spacing: 8) {
            HNField(label: "Schedule", text: Binding(get: { trigger.expr ?? "" }, set: { trigger.expr = $0; model.checkCron(trigger) }), placeholder: "*/15 9-18 * * 1-5", mono: true,
                    hint: bad ?? "Five fields on the Mac’s clock: minute, hour, day, month, weekday.", invalid: bad != nil)
            if bad == nil, !model.cronNext.isEmpty {
                HStack(spacing: 6) {
                    WFLabel("Next")
                    ForEach(model.cronNext.prefix(3), id: \.self) { t in WFChip(text: AutomationWords.clock(Date(timeIntervalSince1970: t / 1000), Date())) }
                }
            } else if model.cronChecking { WFNote(text: "Checking…") }
        }
        .onAppear { model.checkCron(trigger) }
    }
}

private struct FileForm: View {
    @Binding var trigger: WFTrigger
    var problem: String?
    var globProblem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            PathField(label: "Folder", text: Binding(get: { trigger.path ?? "" }, set: { trigger.path = $0 }), problem: problem)
            HNField(label: "Files", text: Binding(get: { trigger.glob ?? "" }, set: { trigger.glob = $0.isEmpty ? nil : $0 }), placeholder: "Any file, or *.md, docs/**", mono: true, hint: globProblem, invalid: globProblem != nil)
            WFLabel("When a file is")
            let ev = ["created", "changed", "deleted"]
            WFSegment(options: ["Added", "Changed", "Deleted"], selected: ev.firstIndex(of: trigger.event ?? "created")) { trigger.event = ev[$0] }
            WFNote(text: "*.md matches a file’s name in any folder. Not .git, node_modules or secret files, nor what its own runs change; it waits for 10 quiet seconds.")
        }
    }
}

private struct GithubForm: View {
    @Binding var trigger: WFTrigger
    var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            PathField(label: "Repository", text: Binding(get: { trigger.repoPath ?? "" }, set: { trigger.repoPath = $0 }), problem: problem)
            WFLabel("When")
            ForEach([("pr_opened", "A pull request opens"), ("review_comment", "Someone reviews my pull request"), ("checks_failed", "Checks fail on my pull request")], id: \.0) { e in
                let on = trigger.events?.contains(e.0) == true
                HStack { Text(e.1).font(HNFont.sans(12.5)).foregroundStyle(Color.hnMuted); Spacer(); HNSwitch(isOn: on, label: e.1) { v in
                    var ev = trigger.events ?? []
                    if v { if !ev.contains(e.0) { ev.append(e.0) } } else if ev.count > 1 { ev.removeAll { $0 == e.0 } }
                    trigger.events = ev
                } }
            }
            WFLabel("Looks every")
            let every = [5, 10, 15]
            WFSegment(options: ["5 min", "10 min", "15 min"], selected: every.firstIndex(of: trigger.every ?? 10)) { trigger.every = every[$0] }
            WFNote(text: "Uses gh on your Mac, and fires only when something changed.")
        }
    }
}

private struct HookForm: View {
    @Binding var trigger: WFTrigger
    var problem: String?
    static let events: [(String, String)] = [("Stop", "A turn ends"), ("StopFailure", "A turn ends on an API error"), ("PostToolUseFailure", "A tool call fails"), ("PreCompact", "Its context is about to compact"), ("SessionEnd", "A session ends")]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            WFMenu(title: "Event", value: Self.events.first { $0.0 == trigger.event }?.1 ?? "A turn ends") {
                ForEach(Self.events, id: \.0) { e in Button(e.1) { trigger.event = e.0; if e.0 != "PostToolUseFailure" { trigger.tool = nil } } }
            }
            if trigger.event == "PostToolUseFailure" {
                HNField(label: "Tool", text: Binding(get: { trigger.tool ?? "" }, set: { trigger.tool = $0.isEmpty ? nil : $0 }), placeholder: "Any tool, or Bash", mono: true)
            }
            let who = ["mine", "automated", "any"]
            WFSegment(options: ["My sessions", "Automated", "Any"], selected: who.firstIndex(of: trigger.from ?? "mine")) { trigger.from = who[$0] }
            if let problem { WFNote(text: problem, tone: .hnDanger) }
            WFNote(text: "From Claude Code’s hooks, at most once a minute per session. For sessions without a terminal, a turn’s end and a failed tool result stand in.")
        }
    }
}
