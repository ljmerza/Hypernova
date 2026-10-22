import AppKit
import SwiftUI

// The small controls of the workflow editor, in the phone's shapes: choice capsules, mono chips,
// pick rows, a text area that takes a placeholder at the cursor, and a menu in the house look.

/// Segmented choice capsules (the phone's "Off 15m 30m 1h"): the chosen one with a mint fill and ring.
struct WFSegment: View {
    var options: [String]
    var selected: Int?
    var pick: (Int) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(options.enumerated()), id: \.offset) { i, o in
                let on = selected == i
                Button { pick(i) } label: {
                    Text(o).font(HNFont.sans(12, on ? .semibold : .medium)).lineLimit(1)
                        .foregroundStyle(on ? Color.hnAccent : Color.hnMuted)
                        .frame(maxWidth: .infinity).frame(height: 28)
                        .background(Capsule().fill(on ? Color.hnAccent.opacity(0.12) : Color.hnBg800.opacity(0.5)))
                        .overlay(Capsule().strokeBorder(on ? Color.hnAccent.opacity(0.45) : Color.hnInk.opacity(0.1), lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}

/// A mono capsule: a placeholder to insert (`+ {prev}`), a rule or an answer (with a × to remove).
struct WFChip: View {
    var text: String
    var plus = false
    var remove: (() -> Void)? = nil
    var action: (() -> Void)? = nil
    var tint: Color = .hnMuted

    var body: some View {
        HStack(spacing: 5) {
            Button { action?() } label: {
                HStack(spacing: 4) {
                    if plus { Text("+").font(HNFont.mono(11)).foregroundStyle(Color.hnAccent) }
                    Text(text).font(HNFont.mono(11)).foregroundStyle(tint).lineLimit(1)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain).disabled(action == nil)
            if let remove {
                Button(action: remove) { CrossIcon(size: 8, color: .hnDim) }.buttonStyle(.plain).accessibilityLabel("Remove \(text)")
            }
        }
        .padding(.horizontal, 9).frame(height: 24)
        .background(Capsule().fill(Color.hnBg800.opacity(0.55)))
        .overlay(Capsule().strokeBorder(Color.hnInk.opacity(0.14), lineWidth: 1))
    }
}

/// Wraps its children onto lines.
struct WFFlow: Layout {
    var spacing: CGFloat = 6
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 400
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let d = s.sizeThatFits(.unspecified)
            if x > 0, x + d.width > w { x = 0; y += row + spacing; row = 0 }
            x += d.width + spacing; row = max(row, d.height); maxX = max(maxX, x - spacing)
        }
        return CGSize(width: min(maxX, w), height: y + row)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let d = s.sizeThatFits(.unspecified)
            if x > bounds.minX, x + d.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: .unspecified)
            x += d.width + spacing; row = max(row, d.height)
        }
    }
}

/// A line of small dim words under a field.
struct WFNote: View {
    var text: String
    var tone: Color = .hnDim
    var body: some View { Text(text).font(HNFont.sans(11)).foregroundStyle(tone).fixedSize(horizontal: false, vertical: true) }
}

/// A tracked small label above a control.
struct WFLabel: View {
    var text: String
    init(_ t: String) { text = t }
    var body: some View { Text(text).hnLabel(.hnDim) }
}

/// A menu in the house look: its label as a quiet pick row, `Change ›`.
struct WFMenu<Items: View>: View {
    var title: String
    var value: String
    @ViewBuilder var items: Items
    var body: some View {
        let label = HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).hnLabel(.hnDim)
                Text(value).font(HNFont.sans(12.5)).foregroundStyle(Color.hnInk).lineLimit(2).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 6)
            Text("›").font(HNFont.sans(15)).foregroundStyle(Color.hnDim)
        }
        .padding(.horizontal, 14).padding(.vertical, 9).frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.hnBg800.opacity(0.7)))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.hnInk.opacity(0.1), lineWidth: 1))
        .contentShape(Rectangle())
        if SnapshotMode.active { label } else {
            Menu { items } label: { label }.menuStyle(.borderlessButton).menuIndicator(.hidden).buttonStyle(.plain)
        }
    }
}

/// The text area of a prompt, text or command: it takes a placeholder at the cursor.
@MainActor
final class WFCursor: ObservableObject {
    weak var view: NSTextView?
    /// Inserts [token] where the cursor is, a space before it when it would touch a word.
    func insert(_ token: String) {
        guard let v = view else { return }
        let range = v.selectedRange()
        let s = v.string as NSString
        var t = token
        if range.location > 0, range.location <= s.length {
            let prev = s.substring(with: NSRange(location: range.location - 1, length: 1))
            if prev.rangeOfCharacter(from: .whitespacesAndNewlines) == nil { t = " " + t }
        }
        if v.shouldChangeText(in: range, replacementString: t) {
            v.replaceCharacters(in: range, with: t)
            v.didChangeText()
        }
        v.window?.makeFirstResponder(v)
    }
}

struct WFTextArea: View {
    @Binding var text: String
    var placeholder = ""
    var mono = false
    var minHeight: CGFloat = 64
    var invalid = false
    @ObservedObject var cursor: WFCursor

    var body: some View {
        Group {
            if SnapshotMode.active {
                Text(text.isEmpty ? placeholder : text)
                    .font(mono ? HNFont.monoM : HNFont.small).foregroundStyle(text.isEmpty ? Color.hnGhost : Color.hnInk)
                    .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .topLeading).padding(10)
            } else {
                ZStack(alignment: .topLeading) {
                    if text.isEmpty { Text(placeholder).font(mono ? HNFont.monoM : HNFont.small).foregroundStyle(Color.hnGhost).padding(.top, 10).padding(.leading, 14).allowsHitTesting(false) }
                    WFTextView(text: $text, mono: mono, cursor: cursor).frame(minHeight: minHeight)
                }
            }
        }
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.hnBg800.opacity(0.7)))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(invalid ? Color.hnDanger.opacity(0.6) : Color.hnInk.opacity(0.1), lineWidth: 1))
    }
}

private struct WFTextView: NSViewRepresentable {
    @Binding var text: String
    var mono: Bool
    var cursor: WFCursor

    func makeNSView(context: Context) -> NSTextView {
        let v = NSTextView()
        v.drawsBackground = false
        v.isRichText = false
        v.allowsUndo = true
        v.isAutomaticQuoteSubstitutionEnabled = false
        v.isAutomaticDashSubstitutionEnabled = false
        v.isAutomaticTextReplacementEnabled = false
        v.isAutomaticSpellingCorrectionEnabled = false
        v.textColor = NSColor(Color.hnInk)
        v.insertionPointColor = NSColor(Color.hnAccent)
        v.font = mono ? (Fonts.ctFont(.mono, 12, .regular) as NSFont) : (Fonts.ctFont(.manrope, 13, .medium) as NSFont)
        v.textContainerInset = NSSize(width: 9, height: 9)
        v.isVerticallyResizable = true
        v.isHorizontallyResizable = false
        v.textContainer?.widthTracksTextView = true
        v.delegate = context.coordinator
        v.string = text
        cursor.view = v
        return v
    }
    func updateNSView(_ v: NSTextView, context: Context) {
        if v.string != text { v.string = text }
        cursor.view = v
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: WFTextView
        init(_ p: WFTextView) { parent = p }
        func textDidChange(_ n: Notification) { if let v = n.object as? NSTextView { parent.text = v.string } }
    }
}

/// Placeholder chips under a text: `+ {prev}` insert at the cursor; one dim line on what the last one touched fills in with.
struct WFPlaceholders: View {
    var tokens: [String]
    var cursor: WFCursor
    /// The workflow's steps, so an earlier step's chip reads "Step 2’s result" (`{step:<id>}`, or a command's variable).
    var steps: [WFStep] = []
    @State private var last: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            WFFlow { ForEach(tokens, id: \.self) { t in WFChip(text: WorkflowWords.chip(t, steps: steps), plus: true, action: { last = t; cursor.insert(t) }) } }
            if let last { WFNote(text: WorkflowWords.placeholder(last, steps: steps)) }
        }
    }
}
