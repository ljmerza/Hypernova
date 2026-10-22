import AppKit
import SwiftUI

// Automations in the popover (docs/specs/spec-automations.md §7): the one quiet line under SESSIONS,
// the AUTO tag on a session an automation started, and Settings' switch. The words are in
// AutomationModels.swift, so a test can read them.

/// "Morning brief  ◷ 9:00", "Morning brief  ⏱ 3m" (NEEDS YOU in amber while its run waits on you),
/// or "Automations paused" with Resume. The planet turns while a run works; a click on a running
/// one opens its terminal, when it has one here.
struct AutomationLine: View {
    @EnvironmentObject private var model: AppModel
    var dismiss: () -> Void
    @State private var hover = false

    var body: some View {
        // The running time moves with the clock: every 15 s is plenty for "3m".
        TimelineView(.periodic(from: .now, by: 15)) { tl in
            if let line = model.automationLine(now: tl.date) {
                row(line)
            }
        }
    }

    private func row(_ line: AutomationWords.Line) -> some View {
        let running = line.kind == .running
        let opens = running && model.runningAutomationOpens
        let tone: Color = line.needsYou ? Color.hnAmber.opacity(0.9) : running ? Color.hnAccent.opacity(0.75) : line.kind == .paused ? Color.hnAmber.opacity(0.85) : Color.hnMuted
        return HStack(spacing: 10) {
            PlanetMeter(activity: line.needsYou ? .alert : running ? .live : .rest, size: 12).opacity(line.kind == .paused ? 0.55 : 1)
            Text(line.text).font(HNFont.sans(12)).foregroundStyle(tone).lineLimit(1).truncationMode(.tail)
            if let f = line.figure { GlyphFigure(glyph: f.glyph, text: f.text, color: running ? Color.hnAccent.opacity(0.7) : .hnDim) }
            if let t = line.tag { Text(t).font(HNFont.mono(10)).foregroundStyle(Color.hnDim) }
            Spacer(minLength: 6)
            if let a = line.action {
                Button(a) { model.setAutomationsPaused(false) }.buttonStyle(LinkStyle()).fixedSize()
            } else if opens && hover {
                Text("Open").font(HNFont.label).tracking(1.2).textCase(.uppercase).foregroundStyle(Color.hnAccent)
            } else if line.needsYou {
                Text("Needs you").font(HNFont.label).tracking(1.2).textCase(.uppercase).foregroundStyle(Color.hnAmber)
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.hnInk.opacity(hover && opens ? 0.055 : 0)))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture { if opens { model.openRunningAutomation(then: dismiss) } }
        .animation(.hnExpo(0.15), value: hover)
        .help(line.help)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(line.said)
        .accessibilityAddTraits(opens ? .isButton : [])
        .accessibilityAction { if opens { model.openRunningAutomation(then: dismiss) } }
    }
}

/// The small tag after a session's project: an automation started it.
struct AutoTag: View {
    var name: String?

    var body: some View {
        Text("Auto").font(HNFont.label).tracking(1.1).textCase(.uppercase)
            .foregroundStyle(Color.hnAccentDim)
            .lineLimit(1).fixedSize()
            .help(name.map { "Started by the automation “\($0)”" } ?? "Started by an automation")
            .accessibilityLabel("started by an automation")
    }
}

/// Settings' part, in the phone's words (its Automations tab): "3 on, next 9:00" or "paused", and
/// Pause all or Resume.
struct AutomationsSettings: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let a = model.automations {
            VStack(alignment: .leading, spacing: 9) {
                Seam(label: "Automations", trail: AutomationWords.trail(a, now: Date()), activity: a.paused ? .alert : a.running.isEmpty ? .rest : .live)
                HStack(spacing: 10) {
                    Text(caption(a)).font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 6)
                    if a.workflows {
                        PopoverPill(title: "Edit", kind: .outline, fill: false, height: 26) { WindowManager.shared.showAutomations() }
                            .help("Make and edit automations and workflows here, as on your phone")
                    }
                    PopoverPill(title: a.paused ? "Resume" : "Pause all", kind: a.paused ? .primary : .outline, fill: false, height: 26) { model.setAutomationsPaused(!a.paused) }
                        .help(a.paused ? "Automations start by themselves again" : "Nothing starts by itself until you resume. Runs already working carry on.")
                        .fixedSize()
                }
            }
        }
    }

    private func caption(_ a: AutomationsState) -> String {
        if a.count == 0 { return a.workflows ? "None yet. You make them here or on your phone." : "None yet. You make them on your phone." }
        return a.paused ? "Nothing starts by itself until you resume. Runs already working carry on." : (a.workflows ? "You make and edit them here or on your phone." : "You make and edit them on your phone.")
    }
}

/// A small capsule switch in the popover's own look: mint when on.
struct HNSwitch: View {
    var isOn: Bool
    var label: String
    var change: (Bool) -> Void

    var body: some View {
        Button { change(!isOn) } label: {
            Capsule()
                .fill(isOn ? Color.hnAccent.opacity(0.85) : Color.hnBg700.opacity(0.8))
                .overlay(Capsule().strokeBorder(Color.hnInk.opacity(isOn ? 0.0 : 0.14), lineWidth: 1))
                .frame(width: 30, height: 18)
                .overlay(alignment: isOn ? .trailing : .leading) {
                    Circle().fill(isOn ? Color.hnBg : Color.hnMuted).frame(width: 12, height: 12).padding(.horizontal, 3)
                }
                .animation(.hnExpo(0.2), value: isOn)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? "on" : "off")
        .accessibilityAddTraits(.isToggle)
    }
}
