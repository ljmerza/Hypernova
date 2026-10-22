import AppKit
import SwiftUI

/// The Automations window: today's cards with their step counts, and the editor for one. Only for
/// a daemon that lists `features`; an older one keeps the line and the pause switch.
struct AutomationsWindow: View {
    @ObservedObject var model: AutomationsModel
    @State private var editing: WFDraft?
    @State private var editorKey = UUID()

    var body: some View {
        DialogHost(dialog: model.dialog) {
            Backdrop {
              ZStack {
                if let d = editing {
                    WorkflowEditor(model: model, draft: d, close: { withAnimation(.hnExpo(0.3)) { editing = nil }; model.load() }).id(editorKey)
                        .transition(.opacity)
                } else {
                    list.transition(.opacity)
                }
                if let t = model.tokenCard { TokenCardView(card: t) { model.tokenCard = nil }.transition(.opacity) }
              }
            }
        }
        .animation(.hnExpo(0.3), value: model.tokenCard)
        .onAppear { model.load() }
        .frame(minWidth: 560, minHeight: 520)
    }

    private var list: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Automations").font(HNFont.title).foregroundStyle(Color.hnInk)
                Spacer()
                if model.offers(Feature.steps) || model.features != nil {
                    PopoverPill(title: "New automation", kind: .primary, fill: false, height: 30) { editorKey = UUID(); editing = model.newDraft() }
                }
            }
            .padding(.horizontal, 24).padding(.top, 40).padding(.bottom, 14)
            HNScroll {
                VStack(spacing: 10) {
                    if let e = model.error { WFNote(text: e, tone: .hnAmber).frame(maxWidth: .infinity, alignment: .leading) }
                    if model.automations.isEmpty && model.loaded { WFNote(text: "None yet. Make one here, or on your phone.").frame(maxWidth: .infinity, alignment: .leading) }
                    ForEach(model.automations) { a in
                        Button { editorKey = UUID(); editing = WFDraft(a) } label: { AutomationCard(automation: a, toggle: { model.setEnabled(a.id, $0) }) }
                            .buttonStyle(.plain).disabled(!a.editable)
                    }
                }
                .padding(.horizontal, 24).padding(.bottom, 24)
            }
        }
    }
}

/// An automation's card: its name and switch, a sentence, and today's status with the step count.
struct AutomationCard: View {
    var automation: WFAutomation
    var toggle: (Bool) -> Void

    var body: some View {
        let a = automation
        GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Text(a.name).font(HNFont.heading).foregroundStyle(a.enabled == false ? Color.hnMuted : Color.hnInk).lineLimit(1)
                    Spacer(minLength: 6)
                    HNSwitch(isOn: a.enabled != false, label: "\(a.name) on") { toggle($0) }
                }
                Text(a.editable ? WorkflowWords.sentence(a) : "This one uses something this app doesn’t know yet. Edit it on your phone.")
                    .font(HNFont.sans(12.5)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    if let r = a.lastRun {
                        let line = WorkflowWords.run(r, now: Date(), codex: WorkflowWords.startsOnCodex(a.steps))
                        WFMark(tone: line.tone)
                        Text(line.text).font(HNFont.sans(11.5)).foregroundStyle(line.tone.color).lineLimit(1)
                    } else if let n = a.nextAt {
                        GlyphFigure(glyph: .clock, text: AutomationWords.clock(Date(timeIntervalSince1970: n / 1000), Date()), color: .hnMuted)
                    } else { Text(a.enabled == false ? "Off" : "Ready").font(HNFont.sans(11.5)).foregroundStyle(Color.hnDim) }
                    Spacer(minLength: 6)
                    if let n = a.steps?.count, n > 1 { StepsChip(n: n) }
                }
            }
        }
    }
}

/// The small tag chip with the step count (`3 STEPS`) at the end of a card's last line.
struct StepsChip: View {
    var n: Int
    var body: some View {
        Text(WorkflowWords.stepsTag(n)).font(HNFont.label).tracking(1.2).textCase(.uppercase).foregroundStyle(Color.hnAccentDim)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(Color.hnAccent.opacity(0.06)))
            .overlay(Capsule().strokeBorder(Color.hnAccentDim.opacity(0.45), lineWidth: 1))
    }
}

/// The token card (shown once, after the save that made it): SHOWN ONCE in amber, "Its token", the
/// token, the address, a curl line and the command for a script on the Mac, each with a small Copy.
struct TokenCardView: View {
    var card: AutomationsModel.TokenCard
    var done: () -> Void

    private var curl: String { "curl -X POST -H \"Authorization: Bearer \(card.token)\" -d 'hello' \(card.url)" }
    private var cli: String { card.command }

    var body: some View {
        ZStack(alignment: .top) {
            Color.hnBg.opacity(0.4).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 10) {
                Text("Shown once").hnLabel(Color.hnAmber.opacity(0.9))
                Text("Its token").font(HNFont.heading).foregroundStyle(Color.hnInk)
                Text("Copy it now: your Mac can’t show it again.").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted)
                value("Token", card.token, sensitive: true)
                value("Address", card.url)
                value("With curl", curl, sensitive: true)
                if !cli.isEmpty { value("From a script on the Mac", cli) }
                WFNote(text: "The address answers on the Mac itself, for its git hooks, cron and CI.")
                HStack(spacing: 8) {
                    PopoverPill(title: "Copy all", kind: .ghost) { WebhookCopy.copy([card.token, card.url, curl, cli].filter { !$0.isEmpty }.joined(separator: "\n"), sensitive: true) }
                    PopoverPill(title: "Done", kind: .primary, action: done).keyboardShortcut(.defaultAction)
                }.padding(.top, 4)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial)
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(LinearGradient(colors: [Color.hnBg700.opacity(0.78), Color.hnBg900.opacity(0.9)], startPoint: .top, endPoint: .bottom))))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color.hnInk.opacity(0.12), lineWidth: 1))
            .overlay(alignment: .top) { LinearGradient(colors: [.clear, Color.hnAmber.opacity(0.7), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1).padding(.horizontal, 18) }
            .shadow(color: .black.opacity(0.35), radius: 22, y: 10)
            .frame(maxWidth: 520).padding(.horizontal, 20).padding(.top, 70)
        }
    }

    private func value(_ label: String, _ text: String, sensitive: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack { WFLabel(label); Spacer(); CopyLink(text: text, sensitive: sensitive) }
            Text(text).font(HNFont.mono(11)).foregroundStyle(Color.hnInk).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.hnBg800.opacity(0.7)))
        }
    }
}

private enum WebhookCopy {
    static func copy(_ s: String, sensitive: Bool) {
        let pb = NSPasteboard.general
        pb.clearContents(); pb.setString(s, forType: .string)
        if sensitive { pb.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) }
    }
}

private struct CopyLink: View {
    var text: String
    var sensitive: Bool
    @State private var copied = false
    var body: some View {
        Button(copied ? "Copied" : "Copy") { WebhookCopy.copy(text, sensitive: sensitive); copied = true }.buttonStyle(LinkStyle())
    }
}

extension WindowManager {
    func showAutomations() {
        let m = AutomationsModel.shared
        show(id: "automations", title: "Hypernova Automations", size: NSSize(width: 680, height: 760), resizable: true) { AutomationsWindow(model: m) }
        m.load()
    }
}

/// The cosmos behind the window; a plain warm black for renders, where its sky draws harshly.
private struct Backdrop<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        if SnapshotMode.active { ZStack { Color.hnBg; content } } else { CosmosBackdrop { content } }
    }
}
