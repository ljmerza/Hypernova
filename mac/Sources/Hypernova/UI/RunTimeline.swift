import AppKit
import SwiftUI

// A workflow run's steps as a compact timeline (docs/DESIGN.md, "Workflows", Runs): a mark on a
// hairline rail, the step's name, what came of it and how long it took after a stopwatch; the step
// at work on a faint wash with its planet turning (mint working, amber waiting for you). Then
// TOLD and each output.

extension WorkflowWords.Tone {
    var color: Color {
        switch self {
        case .ok: .hnAccent
        case .quiet: .hnMuted
        case .skipped, .pending: .hnDim
        case .failed: .hnDanger
        case .stopped: .hnAmber
        case .active: .hnAccent
        case .alert: .hnAmber
        }
    }
}

/// A 12 pt mark for a state: a check, a cross, a dash, or the planet while it works.
struct WFMark: View {
    var tone: WorkflowWords.Tone
    var size: CGFloat = 12
    var body: some View {
        Group {
            switch tone {
            case .ok: CheckIcon(size: size, color: .hnAccent)
            case .failed: CrossIcon(size: size, color: .hnDanger)
            case .stopped: CrossIcon(size: size, color: .hnAmber)
            case .active: PlanetMeter(activity: .live, size: size)
            case .alert: PlanetMeter(activity: .alert, size: size)
            case .quiet: CheckIcon(size: size, color: .hnMuted)
            case .skipped, .pending: Text("–").font(HNFont.mono(size)).foregroundStyle(Color.hnDim)
            }
        }
        .frame(width: size, height: size)
    }
}

struct RunTimeline: View {
    var run: WFRun
    var steps: [WFStep]
    var outputs: [WFOutput]
    var now: Date

    var body: some View {
        let rs = run.steps ?? []
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rs.enumerated()), id: \.element.id) { i, s in
                let def = steps.first { $0.id == s.id }
                let line = WorkflowWords.step(s, now: now, codex: def?.isCodex == true)
                let at = s.state == "running" || s.state == "needs_you"
                HStack(alignment: .top, spacing: 10) {
                    VStack(spacing: 0) {
                        WFMark(tone: line.tone).frame(height: 16)
                    }
                    .frame(width: 12)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(WorkflowWords.stepName(s, def: def)).font(def?.kind == WFStep.shell ? HNFont.mono(12) : HNFont.sans(12.5)).foregroundStyle(Color.hnInk).lineLimit(1).truncationMode(.tail)
                            Spacer(minLength: 4)
                            if let t = WorkflowWords.ran(s, now: now), s.state != "pending", s.state != "skipped" { GlyphFigure(glyph: .timer, text: WorkflowWords.duration(t)) }
                        }
                        Text(line.text).font(HNFont.sans(11.5)).foregroundStyle(line.tone.color.opacity(line.tone == .ok || line.tone == .quiet ? 0.8 : 1)).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        if let late = line.late { Text(late).font(HNFont.sans(11)).foregroundStyle(Color.hnDim) }
                    }
                    .padding(.bottom, 9)
                }
                .overlay(alignment: .topLeading) {
                    if i < rs.count - 1 || !(run.outputs ?? []).isEmpty { Rectangle().fill(Color.hnLine).frame(width: 1).padding(.top, 19).padding(.leading, 5.5) }
                }
                .padding(.horizontal, 6)
                .background(at ? RoundedRectangle(cornerRadius: 8, style: .continuous).fill((s.state == "needs_you" ? Color.hnAmber : Color.hnAccent).opacity(0.06)) : nil)
            }
            if let outs = run.outputs, !outs.isEmpty {
                Text("Told").hnLabel(.hnDim).padding(.leading, 28).padding(.bottom, 4).padding(.top, 2)
                ForEach(outs) { o in
                    let line = WorkflowWords.output(o)
                    HStack(spacing: 10) {
                        WFMark(tone: line.tone).frame(width: 12)
                        Text(WorkflowWords.outputName(outputs.first { $0.id == o.id }, kind: o.kind ?? "")).font(HNFont.sans(12.5)).foregroundStyle(Color.hnInk)
                        Text(line.text).font(HNFont.sans(11.5)).foregroundStyle(line.tone.color.opacity(0.9)).lineLimit(1)
                    }
                    .padding(.horizontal, 6).padding(.bottom, 6)
                }
            }
        }
        .padding(.leading, 6).padding(.top, 4).padding(.bottom, 4)
    }
}

/// One run in the editor's RUNS: its time, how long it took and what came of it, Retry on one that
/// ended badly, and its steps under it (the newest open).
struct RunRow: View {
    @ObservedObject var model: AutomationsModel
    var run: WFRun
    var automation: WFAutomation?
    var draft: WFDraft
    var startOpen: Bool
    @State private var open: Bool?

    private var isOpen: Bool { open ?? startOpen }
    private var ended: Bool { !run.active }
    private var retryable: Bool { ["failed", "skipped", "stopped"].contains(run.state) }

    var body: some View {
        TimelineView(.periodic(from: .now, by: run.active ? 1 : 60)) { tl in
            let line = WorkflowWords.run(run, now: tl.date, codex: WorkflowWords.startsOnCodex(automation?.steps ?? draft.steps))
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top, spacing: 10) {
                    WFMark(tone: line.tone).padding(.top, 2)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 10) {
                            Text(Self.when(run)).font(HNFont.mono(11)).foregroundStyle(Color.hnDim)
                            if let t = Self.took(run, tl.date) { GlyphFigure(glyph: .timer, text: t) }
                        }
                        Text(line.text).font(HNFont.sans(12)).foregroundStyle(line.tone.color).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        if run.retryOf != nil { Text("A retry").font(HNFont.sans(11)).foregroundStyle(Color.hnDim) }
                    }
                    Spacer(minLength: 6)
                    if run.active {
                        if run.state != "needs_you" { EmptyView() }
                    } else if retryable {
                        PopoverPill(title: "Retry", kind: .outline, fill: false, height: 26) { retry() }
                    }
                }
                if isOpen, !(run.steps ?? []).isEmpty {
                    RunTimeline(run: run, steps: automation?.steps ?? draft.steps, outputs: automation?.outputs ?? draft.outputs, now: tl.date).padding(.leading, 12)
                }
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
            .onTapGesture { if !(run.steps ?? []).isEmpty { withAnimation(.hnExpo(0.3)) { open = !isOpen } } }
            .overlay(alignment: .top) { Rectangle().fill(Color.hnLine.opacity(0.7)).frame(height: 1) }
        }
    }

    /// Retry on a run that failed at a later step asks first: from the start, or from that step.
    private func retry() {
        if let i = run.failedStepIndex, i > 0, let s = run.steps?[i], (automation?.steps ?? []).contains(where: { $0.id == s.id }) {
            model.dialog = GlassDialog(eyebrow: "Retry", title: "Retry from step \(i + 1)?", line: "The steps before it are kept, with what they said. Or run it all again.", accent: .hnAccent,
                                       secondary: "From the start", primary: "From step \(i + 1)",
                                       confirm: { _ in model.dialog = nil; model.retry(run, fromStep: s.id) }, cancel: { model.dialog = nil },
                                       secondaryAction: { model.dialog = nil; model.retry(run, fromStep: nil) })
        } else { model.retry(run, fromStep: nil) }
    }

    static func when(_ r: WFRun) -> String {
        guard let t = r.startedAt ?? r.fired?.at else { return "" }
        let d = Date(timeIntervalSince1970: t / 1000)
        let f = DateFormatter()
        if Calendar.current.isDateInToday(d) { f.setLocalizedDateFormatFromTemplate("j:mm"); return "Today " + f.string(from: d) }
        f.setLocalizedDateFormatFromTemplate("EEE j:mm"); return f.string(from: d)
    }
    static func took(_ r: WFRun, _ now: Date) -> String? {
        guard let a = r.startedAt else { return nil }
        let end = r.endedAt ?? now.timeIntervalSince1970 * 1000
        return WorkflowWords.duration(max(0, end - a) / 1000)
    }
}
