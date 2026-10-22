import SwiftUI

/// The popover's word for a session whose Claude is thinking (owner, 2026-10-16): it breathes
/// between the accent's two greens (one slow pulse of about 1.8 s) so a glance shows the work is
/// going on. With Reduce Motion it is one steady accent colour. When the activity moves on to
/// something else, this view is gone and the row's usual constant colour is back. One animation
/// per label, driven by the system, nothing redrawn by hand.
struct ThinkingLabel: View {
    var text: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bright = false

    var body: some View {
        Text(text)
            .font(HNFont.mono(10.5))
            .foregroundStyle(reduceMotion || bright ? Color.hnAccent : Color.hnAccentDim)
            .lineLimit(1).truncationMode(.middle)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { bright = true }
            }
    }
}
