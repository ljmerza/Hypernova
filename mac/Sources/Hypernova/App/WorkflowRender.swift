import AppKit
import SwiftUI

/// Renders of the workflow editor and run timeline on sample data (called by `--render-popover`):
/// protocol-shaped JSON through the app's own decoders, never the daemon.
@MainActor
struct WorkflowRender {
    let dir: URL
    let now: Date
    private func ms(_ s: Double) -> Int { Int((now.timeIntervalSince1970 + s) * 1000) }

    static let features = #"["steps","ask","shell","budget","trigger.webhook","trigger.cron","trigger.file","trigger.github","trigger.hook","output.webhook","output.file"]"#

    func automation() -> WFAutomation {
        let json = """
        {"id":"au_ship","name":"Ship when CI is green","enabled":true,"onlyOnce":false,
         "trigger":{"kind":"webhook","last4":"9f3c","createdAt":\(ms(-3 * 3600)),"url":"http://127.0.0.1:47322/local/automations/au_ship/fire","command":"\\"/Users/you/Library/Application Support/Hypernova/bin/hypernova\\" automation fire au_ship"},
         "steps":[
           {"id":"st_build","kind":"shell","if":null,"projectPath":"/Users/you/Programming/hypernova","command":"npm run build","timeoutSec":60},
           {"id":"st_notes","kind":"start","if":null,"projectPath":"/Users/you/Programming/hypernova","prompt":"Write release notes for what CI just built: {payload}","providerId":"anthropic","model":null,"permissionMode":"dontAsk","allowedTools":[]},
           {"id":"st_say","kind":"say","if":{"on":"not_quiet"},"text":"Release notes are ready."}],
         "outputs":[{"id":"ou_slack","kind":"webhook","preset":"slack","host":"hooks.slack.com","last4":"Xq7Z","text":null},
                    {"id":"ou_file","kind":"file","path":"RELEASES.md","text":null}],
         "budget":{"minutes":30,"windowPct":null},"template":null,"nextAt":null,"runCount":2,"paid":false,"problem":null}
        """
        return decode(json)
    }

    func runs() -> [WFRun] {
        let failed: WFRun = decode("""
        {"id":"ru_2","automationId":"au_ship","name":"Ship when CI is green","state":"failed","reason":"error","startedAt":\(ms(-9000)),"endedAt":\(ms(-8821)),"result":"API Error: 529 overloaded_error","retryOf":null,
         "fired":{"by":"webhook","at":\(ms(-9000)),"detail":null},
         "steps":[
           {"id":"st_build","kind":"shell","state":"done","startedAt":\(ms(-9000)),"endedAt":\(ms(-8959)),"result":"✓ built in 41.2s","exit":0,"quiet":false},
           {"id":"st_notes","kind":"start","state":"failed","reason":"error","startedAt":\(ms(-8959)),"endedAt":\(ms(-8821)),"result":"API Error: 529 overloaded_error"},
           {"id":"st_say","kind":"say","state":"skipped","reason":"failed_before"}],
         "outputs":[{"id":"ou_slack","kind":"webhook","state":"skipped"},{"id":"ou_file","kind":"file","state":"skipped"}]}
        """)
        let done: WFRun = decode("""
        {"id":"ru_1","automationId":"au_ship","name":"Ship when CI is green","state":"done","startedAt":\(ms(-90000)),"endedAt":\(ms(-89760)),"result":"v0.42: Statistics, the lens guide and a calmer sky.","retryOf":null,
         "fired":{"by":"webhook","at":\(ms(-90000)),"detail":null},
         "steps":[
           {"id":"st_build","kind":"shell","state":"done","startedAt":\(ms(-90000)),"endedAt":\(ms(-89960)),"result":"✓ built in 40.1s","exit":0},
           {"id":"st_notes","kind":"start","state":"done","startedAt":\(ms(-89960)),"endedAt":\(ms(-89780)),"result":"v0.42: Statistics, the lens guide and a calmer sky."},
           {"id":"st_say","kind":"say","state":"done","startedAt":\(ms(-89780)),"endedAt":\(ms(-89779))}],
         "outputs":[{"id":"ou_slack","kind":"webhook","state":"sent"},{"id":"ou_file","kind":"file","state":"failed","error":"No such folder."}]}
        """)
        return [failed, done]
    }

    func asking() -> WFRun {
        decode("""
        {"id":"ru_3","automationId":"au_rev","name":"Review before merge","state":"needs_you","startedAt":\(ms(-240)),
         "steps":[
           {"id":"st_rev","kind":"start","state":"done","startedAt":\(ms(-240)),"endedAt":\(ms(-60)),"result":"Looks good: one nit on parser.ts line 88."},
           {"id":"st_ask","kind":"ask","state":"needs_you","startedAt":\(ms(-60))},
           {"id":"st_post","kind":"shell","state":"pending"}],
         "ask":{"stepId":"st_ask","question":"Post this review?","answers":["Approve","Skip"],"default":"Skip","until":\(ms(540))},
         "outputs":[]}
        """)
    }

    func run() -> Int {
        var failed = 0
        let model = AutomationsModel()
        let a = automation()
        let providers: [Provider] = decode(#"[{"id":"anthropic","name":"Claude Code","kind":"anthropic","builtIn":true}]"#)
        model.loadSample([a], features: [], runs: ["au_ship": runs()], providers: providers)
        model.features = (try? JSONDecoder().decode([String].self, from: Data(Self.features.utf8))) ?? []

        // The editor, its steps one open: the shell step, with the first run's timeline under RUNS.
        let size = CGSize(width: 680, height: 2300)
        func editor(_ draft: WFDraft, open: String? = nil, dialog: GlassDialog? = nil) -> some View {
            model.dialog = dialog
            return DialogHost(dialog: dialog) { ZStack { Color.hnBg; WorkflowEditor(model: model, draft: draft, close: {}, startOpen: open) } }.frame(width: size.width, height: size.height)
        }
        if !write("workflow-1-editor-three-steps", editor(WFDraft(a)), size) { failed += 1 }
        if !write("workflow-2-editor-shell-open", editor(WFDraft(a), open: "st_build"), CGSize(width: 680, height: 2500)) { failed += 1 }
        if !write("workflow-3-editor-start-open", editor(WFDraft(a), open: "st_notes"), CGSize(width: 680, height: 2500)) { failed += 1 }

        // A cron trigger with its next three times, and a question step.
        var cron = WFDraft(a)
        cron.trigger = WFTrigger(kind: "cron", expr: "*/15 9-18 * * 1-5")
        var q = WFStep.make(WFStep.ask); q.id = "st_ask"; q.question = "Post this release note?"
        cron.steps.insert(q, at: 1); cron.steps[2].if = WFGuard(on: WFGuard.answer, text: "Approve")
        model.cronNext = [1.0, 2.0, 3.0].enumerated().map { Double(ms(Double($0.offset + 1) * 900)) }
        if !write("workflow-4-editor-cron-ask", editor(cron, open: "st_ask"), CGSize(width: 680, height: 1900)) { failed += 1 }

        // The retry question, the token card, the list.
        let retryDialog = GlassDialog(eyebrow: "Retry", title: "Retry from step 2?", line: "The steps before it are kept, with what they said. Or run it all again.", accent: .hnAccent, secondary: "From the start", primary: "From step 2")
        if !write("workflow-5-retry-from-step", editor(WFDraft(a), dialog: retryDialog), CGSize(width: 680, height: 900)) { failed += 1 }
        model.dialog = nil

        let ask = asking()
        let timeline = VStack(alignment: .leading, spacing: 0) {
            ForEach(runs() + [ask]) { r in RunRow(model: model, run: r, automation: a, draft: WFDraft(a), startOpen: true) }
        }
        .padding(24).frame(width: 640)
        if !write("workflow-6-run-timelines", ZStack { Color.hnBg; timeline }.frame(width: 640, height: 900), CGSize(width: 640, height: 900)) { failed += 1 }

        let token = TokenCardView(card: .init(automationId: "au_ship", name: a.name, token: "hnf_5b0c1e7a93d24f68a1c07e3b2d9f9f3c",
                                              url: "http://127.0.0.1:47322/local/automations/au_ship/fire", command: a.trigger.command ?? "")) {}
        if !write("workflow-7-token-card", ZStack { Color.hnBg; token }.frame(width: 680, height: 620), CGSize(width: 680, height: 620)) { failed += 1 }

        var second = a; second.id = "au_rev"; second.name = "Review before merge"
        second.trigger = WFTrigger(kind: "github", repoPath: "/Users/you/Programming/hypernova", events: ["pr_opened"], every: 10)
        second.steps = [WFStep.make(WFStep.start, project: "/Users/you/Programming/hypernova", providerId: "anthropic")]; second.steps?[0].prompt = "Review the pull request that just opened: {payload}."
        second.outputs = []; second.lastRun = asking()
        let list = AutomationsWindow(model: { let m = AutomationsModel(); m.loadSample([a, second], features: [], runs: [:], providers: providers); m.features = model.features; return m }())
        if !write("workflow-8-list", list, CGSize(width: 680, height: 560)) { failed += 1 }

        // Codex on (docs/PROTOCOL.md, "Automations"): the start step's agent picker, then the same step
        // on Codex with its sandbox (Can edit) and one of Codex's models.
        let models: [CodexModel] = decode(#"[{"value":"gpt-5.3-codex","name":"GPT-5.3 Codex","description":"Tuned for code","efforts":["low","medium","high"],"defaultEffort":"medium","isDefault":true},{"value":"gpt-5.3","name":"GPT-5.3","description":null,"efforts":["low","medium","high"],"defaultEffort":"medium","isDefault":false}]"#)
        let withCodex = AutomationsModel()
        withCodex.loadSample([a], features: [], runs: ["au_ship": runs()], providers: providers, codex: models)
        withCodex.features = model.features
        func codexEditor(_ draft: WFDraft) -> some View {
            DialogHost(dialog: nil) { ZStack { Color.hnBg; WorkflowEditor(model: withCodex, draft: draft, close: {}, startOpen: "st_notes") } }.frame(width: size.width, height: 2500)
        }
        if !write("workflow-9-editor-claude-step-codex-on", codexEditor(WFDraft(a)), CGSize(width: 680, height: 2500)) { failed += 1 }
        var onCodex = WFDraft(a)
        if let i = onCodex.steps.firstIndex(where: { $0.id == "st_notes" }) {
            onCodex.steps[i].providerId = WFStep.codexProvider; onCodex.steps[i].permissionMode = WFStep.codexModes[1]; onCodex.steps[i].model = "gpt-5.3-codex"
        }
        if !write("workflow-10-editor-codex-step", codexEditor(onCodex), CGSize(width: 680, height: 2500)) { failed += 1 }
        return failed
    }

    private func write<V: View>(_ name: String, _ view: V, _ size: CGSize) -> Bool {
        let r = ImageRenderer(content: view.environment(\.colorScheme, .dark).frame(width: size.width, height: size.height))
        r.scale = 1.5
        guard let cg = r.cgImage, let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) else { print("render failed: \(name)"); return false }
        do { try png.write(to: dir.appendingPathComponent(name + ".png")) } catch { print("write failed: \(name)"); return false }
        print("wrote \(dir.appendingPathComponent(name + ".png").path)")
        return true
    }

    private func decode<T: Decodable>(_ json: String) -> T {
        do { return try JSONDecoder().decode(T.self, from: Data(json.utf8)) } catch { fatalError("workflow sample didn't decode as \(T.self): \(error)\n\(json)") }
    }
}
