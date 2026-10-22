import AppKit
import SwiftUI

// Stay awake in the popover (docs/PROTOCOL.md, "Stay awake"): a compact section on the main page
// (the mode, and a line when there's something to say: Backpack's hours, a guard counting down),
// the offer of Backpack's one-time setup, and its part of Settings.

/// "STAY AWAKE  awake with the lid closed", then While working / Backpack / Off. A Mac without a battery
/// has no Backpack. Shown only when the daemon reports Stay awake.
struct StayAwakeSection: View {
    @EnvironmentObject private var model: AppModel
    var dismiss: () -> Void
    /// Backpack was chosen before its setup: the offer shows under the picker.
    @State private var offer = StayAwakeSection.sampleOffer
    /// `--render-popover` only: draw the offer open.
    nonisolated(unsafe) static var sampleOffer = false

    var body: some View {
        if let a = model.awake {
            // A countdown's words move with the clock (every 15 s is plenty for "2 minutes").
            TimelineView(.periodic(from: .now, by: 15)) { tl in
                VStack(alignment: .leading, spacing: 10) {
                    Seam(label: "Stay awake", trail: AwakeWords.trail(a, now: tl.date), activity: a.sleepingSoon || a.letGo || a.reason == "setup" ? .alert : .rest)
                    AwakeModePicker(modes: a.laptop ? Awake.Mode.allCases : [.working, .off], selection: offer ? .backpack : (model.awakeChoice ?? a.mode)) { m in
                        if model.chooseAwake(m) {
                            withAnimation(.hnExpo(0.3)) { offer = false }
                        } else {
                            withAnimation(.hnExpo(0.35)) { offer = true }
                        }
                    }
                    if offer {
                        BackpackOffer(busy: model.backpackBusy, minBattery: a.minBattery,
                                      enable: { model.enableBackpack(dismiss: dismiss) },
                                      later: { withAnimation(.hnExpo(0.3)) { offer = false } })
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    } else if let ending = AwakeWords.ending(a, now: tl.date) {
                        // Backpack's last ten minutes: say so, with "+2 h" right there.
                        HStack(spacing: 10) {
                            AwakeLine(text: ending, warn: true)
                            PopoverPill(title: "+2 h", kind: .primary, fill: false, height: 26) { model.extendBackpack() }
                        }
                        .transition(.opacity)
                    } else if let line = AwakeWords.line(a, battery: model.snapshot?.mac?.battery, now: tl.date) {
                        AwakeLine(text: line.text, warn: line.warn)
                            .transition(.opacity)
                    }
                }
            }
            .animation(.hnExpo(0.35), value: a)
        }
    }
}

/// The three modes as one quiet capsule; the chosen one as mint-tinted glass (glass everywhere).
struct AwakeModePicker: View {
    var modes: [Awake.Mode]
    var selection: Awake.Mode
    var choose: (Awake.Mode) -> Void
    @State private var hover: Awake.Mode?

    var body: some View {
        HStack(spacing: 2) {
            ForEach(modes, id: \.self) { m in
                let on = m == selection
                Button { choose(m) } label: {
                    Text(AwakeWords.title(m))
                        .font(HNFont.sans(11.5, on ? .semibold : .medium))
                        .foregroundStyle(on ? Color.hnAccentBright : hover == m ? Color.hnInk : Color.hnMuted)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity).frame(height: 24)
                        .background {
                            if on {
                                Capsule().fill(LinearGradient(colors: [Color.hnAccent.opacity(0.2), Color.hnAccent.opacity(0.1)], startPoint: .top, endPoint: .bottom))
                                    .overlay(Capsule().strokeBorder(Color.hnAccent.opacity(0.38), lineWidth: 1))
                                    .overlay(alignment: .top) {
                                        LinearGradient(colors: [.clear, Color.hnAccentBright.opacity(0.35), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1).padding(.horizontal, 10)
                                    }
                            } else {
                                Capsule().fill(Color.hnInk.opacity(hover == m ? 0.06 : 0))
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .onHover { hover = $0 ? m : (hover == m ? nil : hover) }
                .help(AwakeWords.help(m))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Capsule().fill(LinearGradient(colors: [Color.hnBg800.opacity(0.62), Color.hnBg900.opacity(0.5)], startPoint: .top, endPoint: .bottom)))
        .overlay(Capsule().strokeBorder(Color.hnInk.opacity(0.09), lineWidth: 1))
        .animation(.hnExpo(0.25), value: selection)
        .animation(.hnExpo(0.15), value: hover)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Stay awake")
    }
}

/// One line under the picker: Backpack's hours (quiet), or the Mac about to sleep and why (amber).
struct AwakeLine: View {
    var text: String
    var warn: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 7) {
            Image(systemName: warn ? "moon.zzz" : "moon.stars")
                .font(.system(size: 10.5, weight: .regular))
                .foregroundStyle(warn ? Color.hnAmber.opacity(0.85) : Color.hnDim)
                .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 3.5 }
            Text(text).font(HNFont.sans(11.5, warn ? .semibold : .regular))
                .foregroundStyle(warn ? Color.hnAmber.opacity(0.9) : Color.hnDim)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// What Backpack does and the one thing it needs, with the button that does it.
struct BackpackOffer: View {
    var busy: Bool
    var minBattery: Int
    var enable: () -> Void
    var later: () -> Void

    var body: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("Keep working with the lid closed.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                Text("Backpack mode keeps this Mac awake in your bag, on battery, for up to 8 hours, and lets it sleep at \(minBattery)% battery or if it runs hot. Your phone and glasses hear 2 minutes before.")
                    .font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                Text("macOS keeps a closed Mac awake only with your password, once: Hypernova adds a rule that lets it turn sleep off and back on, and nothing else.")
                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    PopoverPill(title: busy ? "Setting up…" : "Enable Backpack mode", kind: .primary, busy: busy, fill: false, action: enable)
                    PopoverPill(title: "Not now", kind: .ghost, fill: false, action: later)
                }
                .padding(.top, 2)
            }
        }
    }
}

/// Settings' part: Backpack's permission (set up or remove) and the battery level it sleeps at.
/// Only on a MacBook; a desktop has neither.
struct StayAwakeSettings: View {
    @EnvironmentObject private var model: AppModel
    var dismiss: () -> Void

    var body: some View {
        if let a = model.awake, a.laptop {
            let ready = a.backpackReady || BackpackSetup.installed
            VStack(alignment: .leading, spacing: 9) {
                Seam(label: "Stay awake", trail: AwakeWords.title(model.awakeChoice ?? a.mode).lowercased())
                HStack(spacing: 10) {
                    Text("Backpack mode").font(HNFont.small).foregroundStyle(Color.hnInk)
                    Spacer(minLength: 6)
                    if model.backpackBusy {
                        PlanetMeter(activity: .live, size: 10)
                    } else if ready {
                        Text("set up").font(HNFont.mono(10)).foregroundStyle(Color.hnAccentDim)
                        Button("Remove") { model.removeBackpack(dismiss: dismiss) }
                            .buttonStyle(LinkStyle(color: .hnMuted))
                            .help("Removes the rule that lets Hypernova turn sleep off. Asks for your password.")
                    } else {
                        Button("Set up…") { model.enableBackpack(dismiss: dismiss) }
                            .buttonStyle(LinkStyle())
                            .help("Asks for your password once, to let Hypernova keep this Mac awake with the lid closed.")
                    }
                }
                .frame(height: 22)
                HStack(spacing: 10) {
                    Text("Let it sleep at").font(HNFont.small).foregroundStyle(Color.hnInk)
                    Spacer(minLength: 6)
                    BatteryStepper(value: a.minBattery) { model.setMinBattery($0) }
                        .help("Below this, on its own battery, Hypernova stops keeping the Mac awake (2 minutes' warning first).")
                }
                .frame(height: 22)
                Text("While working keeps it awake while sessions, agents or automations run, or a phone or the glasses are connected. Backpack also keeps it awake with the lid closed, for up to 8 hours.")
                    .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// "−  15%  +": the battery level, 5% a step, from 5% to 50%.
private struct BatteryStepper: View {
    var value: Int
    var change: (Int) -> Void

    var body: some View {
        HStack(spacing: 2) {
            step("minus", to: value - 5, enabled: value > 5)
            Text("\(value)%").font(HNFont.mono(10.5)).foregroundStyle(Color.hnInk).frame(minWidth: 34)
            step("plus", to: value + 5, enabled: value < 50)
        }
        .padding(.horizontal, 2).frame(height: 22)
        .background(Capsule().fill(Color.hnBg800.opacity(0.5)))
        .overlay(Capsule().strokeBorder(Color.hnInk.opacity(0.09), lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Let it sleep at \(value) percent battery")
        .accessibilityAdjustableAction { d in
            switch d {
            case .increment: if value < 50 { change(value + 5) }
            case .decrement: if value > 5 { change(value - 5) }
            @unknown default: break
            }
        }
    }

    private func step(_ symbol: String, to v: Int, enabled: Bool) -> some View {
        Button { change(min(50, max(5, v))) } label: {
            Image(systemName: symbol).font(.system(size: 8.5, weight: .semibold))
                .foregroundStyle(enabled ? Color.hnMuted : Color.hnGhost)
                .frame(width: 20, height: 18).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
