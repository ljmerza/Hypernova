import AppKit
import SwiftUI

// The owner's decisions for things that ask for attention (2026-10-18), the same on phone and Mac:
// - Attention cards (an update, an unfinished setup, a limit, a sign-out): the same glass as the
//   other cards, a coloured glyph and a thin accent edge instead of a tinted fill, a title and one
//   line, the action as a small pill on the right, and swipe right to dismiss, with Undo. A
//   dismissed card comes back when its state changes.
// - Dialogs: a floating glass card above the content, over a light blurred scrim (never black), a
//   small eyebrow and a title, one short line (more behind "More"), and two glass pills side by
//   side, the secondary on the left and the primary on the right; colour only as a thin accent.

// MARK: - Attention cards

/// One thing that wants attention, said once. [key] is what it's about right now: a card dismissed
/// with one key comes back when the key changes (another update, a later limit).
struct Notice: Identifiable {
    enum Tone { case accent, amber, danger }
    var id: String
    var tone: Tone
    var title: String
    var line: String? = nil
    var action: String? = nil
    var busy = false
    var help: String? = nil
    /// The glyph before the title: a point of light by default, the eclipse for a limit.
    var eclipse = false
    var key: String = ""
    /// A figure after the line that moves with the clock (a question's "Skip in 9m"), with its glyph.
    var figure: ((Date) -> String)? = nil
    /// The answers of a question, as pills under it (the first is the primary one).
    var choices: [String] = []
    var choose: (String) -> Void = { _ in }
    var run: () -> Void = {}

    var color: Color { switch tone { case .accent: .hnAccent; case .amber: .hnAmber; case .danger: .hnDanger } }
}

/// Notices swiped away, by id with the key they had: one comes back when its key changes.
@MainActor
final class DismissedNotices: ObservableObject {
    static let shared = DismissedNotices()
    @Published private(set) var keys: [String: String] = [:]
    /// The one just dismissed, for Undo, until the next or a few seconds pass.
    @Published private(set) var undo: Notice?
    private var undoTask: Task<Void, Never>?

    func shows(_ n: Notice) -> Bool { keys[n.id] != n.key }

    func dismiss(_ n: Notice) {
        withAnimation(.hnExpo(0.35)) { keys[n.id] = n.key; undo = n }
        undoTask?.cancel()
        undoTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.hnExpo(0.35)) { self?.undo = nil }
        }
    }

    func restore() {
        guard let n = undo else { return }
        undoTask?.cancel()
        withAnimation(.hnExpo(0.35)) { keys[n.id] = nil; undo = nil }
    }
}

/// The glass the popover's cards share: dense warm glass, a 1pt ink ring, a highlight on top.
struct CardGlass: View {
    var radius: CGFloat = 14
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        ZStack {
            shape.fill(LinearGradient(colors: [Color.hnBg800.opacity(0.84), Color.hnBg900.opacity(0.8)], startPoint: .top, endPoint: .bottom))
            shape.strokeBorder(Color.hnInk.opacity(0.08), lineWidth: 1)
        }
        .overlay(alignment: .top) {
            LinearGradient(colors: [.clear, Color.hnInk.opacity(0.2), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1).padding(.horizontal, 12)
        }
    }
}

/// An attention card: glass, a coloured glyph, a thin accent edge on the left, the title and one
/// line, the action as a small pill on the right. Drag it to the right to dismiss it.
struct AttentionCard: View {
    var notice: Notice
    /// Nil: it can't be dismissed (it goes when what it's about does).
    var onDismiss: (() -> Void)?
    @State private var drag: CGFloat = 0
    @State private var hover = false

    var body: some View {
        let c = notice.color
        HStack(alignment: notice.choices.isEmpty ? .center : .top, spacing: 10) {
            Group {
                if notice.eclipse { EclipseMark(color: c, size: 11) } else {
                    ZStack {
                        Circle().fill(RadialGradient(colors: [c.opacity(0.45), c.opacity(0)], center: .center, startRadius: 0, endRadius: 6)).frame(width: 12, height: 12)
                        Circle().fill(c).frame(width: 5, height: 5)
                    }
                }
            }
            .frame(width: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(notice.title).font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnInk).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                if let line = notice.line {
                    Text(line).font(HNFont.sans(11.5)).foregroundStyle(Color.hnMuted).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                NoticeExtras(notice: notice, color: c)
            }
            Spacer(minLength: 6)
            if notice.busy { PlanetMeter(activity: .live, size: 10) }
            if let action = notice.action {
                SmallGlassPill(title: action, accent: c, action: notice.run).disabled(notice.busy)
            }
        }
        .padding(.leading, 13).padding(.trailing, 10).padding(.vertical, 9)
        .frame(minHeight: 40)
        .background(CardGlass())
        // The thin accent edge.
        .overlay(alignment: .leading) {
            Capsule().fill(c.opacity(0.85)).frame(width: 2).padding(.vertical, 9).padding(.leading, 1)
        }
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .offset(x: max(0, drag))
        .opacity(1 - Double(min(max(drag, 0), 160)) / 220)
        .gesture(onDismiss == nil ? nil : DragGesture(minimumDistance: 8)
            .onChanged { v in if abs(v.translation.width) > abs(v.translation.height) { drag = v.translation.width } }
            .onEnded { v in
                if v.translation.width > 90 || v.predictedEndTranslation.width > 160 { onDismiss?() }
                withAnimation(.hnExpo(0.3)) { drag = 0 }
            })
        .onHover { hover = $0 }
        .help(notice.help ?? notice.title + (notice.line.map { ". " + $0 } ?? "") + (onDismiss == nil ? "" : "\n\nDrag to the right to dismiss."))
        .accessibilityElement(children: .combine)
        .accessibilityActions {
            if let action = notice.action { Button(action) { notice.run() } }
            if let onDismiss { Button("Dismiss") { onDismiss() } }
        }
    }
}

/// A question's countdown and answers, under its title.
private struct NoticeExtras: View {
    var notice: Notice
    var color: Color
    var body: some View {
        if let figure = notice.figure {
            TimelineView(.periodic(from: .now, by: 1)) { tl in GlyphFigure(glyph: .timer, text: figure(tl.date), color: .hnDim) }.padding(.top, 1)
        }
        if !notice.choices.isEmpty {
            HStack(spacing: 6) {
                ForEach(Array(notice.choices.enumerated()), id: \.offset) { i, c in
                    SmallGlassPill(title: c, accent: i == 0 ? color : .hnMuted) { notice.choose(c) }
                }
            }.padding(.top, 5)
        }
    }
}

/// "Dismissed. Undo": for a few seconds after a card is swiped away.
struct UndoLine: View {
    @ObservedObject var dismissed: DismissedNotices
    var body: some View {
        if let n = dismissed.undo {
            HStack(spacing: 8) {
                Text("Dismissed: \(n.title)").font(HNFont.sans(11.5)).foregroundStyle(Color.hnDim).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 6)
                Button("Undo") { dismissed.restore() }.buttonStyle(LinkStyle())
            }
            .padding(.horizontal, 4)
            .transition(.opacity)
        }
    }
}

/// A small glass pill for an action inside a card: frosted glass, its words and a thin ring in the
/// card's colour.
struct SmallGlassPill: View {
    var title: String
    var accent: Color = .hnAccent
    var action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Text(title).font(HNFont.sans(11.5, .semibold)).foregroundStyle(accent.opacity(hover ? 1 : 0.9)).lineLimit(1).fixedSize()
                .padding(.horizontal, 11).frame(height: 24)
                .background(Capsule().fill(LinearGradient(colors: [Color.hnBg700.opacity(0.7), Color.hnBg800.opacity(0.6)], startPoint: .top, endPoint: .bottom)))
                .background(Capsule().fill(accent.opacity(hover ? 0.1 : 0.05)))
                .overlay(Capsule().strokeBorder(accent.opacity(hover ? 0.5 : 0.32), lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(PressStyle())
        .onHover { hover = $0 }
        .animation(.hnExpo(0.15), value: hover)
    }
}

// MARK: - Dialogs

/// A question before something that can't be taken back, or a short word after it.
struct GlassDialog: Identifiable, Equatable {
    var id = UUID()
    var eyebrow: String
    var title: String
    var line: String
    /// Longer details, behind "More".
    var more: String? = nil
    var accent: Color = .hnAmber
    var secondary: String? = "Cancel"
    var primary: String
    /// While the primary action works: its words ("Uninstalling…").
    var busy: String? = nil
    /// A checkbox under the line ("Also delete Hypernova's data").
    var option: String? = nil
    var confirm: (_ option: Bool) -> Void = { _ in }
    var cancel: () -> Void = {}
    /// The secondary pill's own action, when it is more than a cancel ("From the start"); the scrim still cancels.
    var secondaryAction: (() -> Void)? = nil

    static func == (a: GlassDialog, b: GlassDialog) -> Bool {
        a.id == b.id && a.title == b.title && a.line == b.line && a.busy == b.busy && a.primary == b.primary
    }
}

/// The dialog card: floating glass above the content, the secondary pill on the left and the
/// primary on the right.
struct GlassDialogCard: View {
    var dialog: GlassDialog
    var working = false
    @State private var option = false
    @State private var more = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        VStack(alignment: .leading, spacing: 8) {
            Text(dialog.eyebrow).hnLabel(dialog.accent.opacity(0.9)).lineLimit(1)
            Text(dialog.title).font(HNFont.heading).foregroundStyle(Color.hnInk).fixedSize(horizontal: false, vertical: true)
            Text(dialog.line).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
            if let m = dialog.more {
                if more {
                    Text(m).font(HNFont.sans(11.5)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true).transition(.opacity)
                } else {
                    Button("More") { withAnimation(.hnExpo(0.3)) { more = true } }.buttonStyle(LinkStyle(color: .hnMuted))
                }
            }
            if let o = dialog.option {
                Button { option.toggle() } label: {
                    HStack(alignment: .top, spacing: 8) {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .strokeBorder(option ? dialog.accent.opacity(0.7) : Color.hnInk.opacity(0.25), lineWidth: 1)
                            .background(RoundedRectangle(cornerRadius: 4, style: .continuous).fill(option ? dialog.accent.opacity(0.12) : .clear))
                            .frame(width: 14, height: 14)
                            .overlay { if option { CheckIcon(size: 10, color: dialog.accent) } }
                        Text(o).font(HNFont.sans(11.5)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(working)
                .accessibilityAddTraits(option ? .isSelected : [])
                .padding(.top, 2)
            }
            HStack(spacing: 8) {
                if let s = dialog.secondary {
                    DialogPill(title: s, accent: .hnInk, quiet: true) { (dialog.secondaryAction ?? dialog.cancel)() }.disabled(working)
                }
                DialogPill(title: working ? (dialog.busy ?? dialog.primary) : dialog.primary, accent: dialog.accent, busy: working) { dialog.confirm(option) }
                    .disabled(working)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 6)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            ZStack {
                shape.fill(.ultraThinMaterial)
                shape.fill(LinearGradient(colors: [Color.hnBg700.opacity(0.78), Color.hnBg900.opacity(0.86)], startPoint: .top, endPoint: .bottom))
            }
        )
        .overlay(shape.strokeBorder(Color.hnInk.opacity(0.12), lineWidth: 1))
        // Colour only as a thin accent: the top edge.
        .overlay(alignment: .top) {
            LinearGradient(colors: [.clear, dialog.accent.opacity(0.7), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1).padding(.horizontal, 18)
        }
        .clipShape(shape)
        .shadow(color: .black.opacity(0.35), radius: 22, y: 10)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }
}

/// The dialog's pills: glass, the primary's words and ring in the accent colour.
private struct DialogPill: View {
    var title: String
    var accent: Color
    var quiet = false
    var busy = false
    var action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                if busy { PlanetMeter(activity: .live, size: 10) }
                Text(title).font(HNFont.sans(12, .semibold)).lineLimit(1)
            }
            .foregroundStyle(quiet ? Color.hnMuted : accent)
            .frame(maxWidth: .infinity).frame(height: 32)
            .background(Capsule().fill(LinearGradient(colors: [Color.hnBg700.opacity(0.75), Color.hnBg800.opacity(0.6)], startPoint: .top, endPoint: .bottom)))
            .background(Capsule().fill(quiet ? Color.clear : accent.opacity(hover ? 0.12 : 0.07)))
            .overlay(Capsule().strokeBorder(quiet ? Color.hnInk.opacity(hover ? 0.2 : 0.12) : accent.opacity(hover ? 0.55 : 0.38), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle())
        .onHover { hover = $0 }
        .animation(.hnExpo(0.15), value: hover)
    }
}

/// Puts [dialog] above [content]: a light blurred scrim, the card floating near the top.
struct DialogHost<Content: View>: View {
    var dialog: GlassDialog?
    var working = false
    @ViewBuilder var content: Content

    var body: some View {
        content
            .blur(radius: dialog == nil ? 0 : 4)
            .allowsHitTesting(dialog == nil)
            .overlay {
                if let d = dialog {
                    ZStack(alignment: .top) {
                        Color.hnBg.opacity(0.22).contentShape(Rectangle()).onTapGesture { if !working { d.cancel() } }
                        GlassDialogCard(dialog: d, working: working)
                            .padding(.horizontal, 12).padding(.top, 56)
                            .id(d.id)
                    }
                    .transition(.opacity)
                }
            }
            .animation(.hnExpo(0.3), value: dialog)
    }
}
