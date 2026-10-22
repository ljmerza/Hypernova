import AppKit
import SwiftUI

/// The "Hypernova Lens" window (App/LensGuide.swift): two or three calm steps over the cosmos (voice
/// is left out when it's ready already), the same words as the Lens phone page (lens/src/phone/guide.ts).
struct LensGuideView: View {
    @EnvironmentObject private var m: LensGuideModel

    var body: some View {
        // The sky, quieter than Setup's and without its hex labels: this window is mostly words, and
        // nothing may read through them.
        ZStack {
            CosmosView(light: 0.6, labels: false)
            LinearGradient(colors: [Color.hnBg.opacity(0.55), Color.hnBg.opacity(0.12)], startPoint: .leading, endPoint: .trailing)
            VStack(alignment: .leading, spacing: 0) {
                Spacer().frame(height: 44)
                header
                GuideRail().padding(.top, 26)
                ZStack(alignment: .topLeading) {
                    step(m.step)
                        .id(m.step)
                        .transition(.asymmetric(insertion: .opacity.combined(with: .offset(y: 8)), removal: .opacity))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.top, 30)
                footer
            }
            .padding(.horizontal, 44)
            .padding(.bottom, 28)
            GrainView().allowsHitTesting(false)
        }
        .background(Color.hnBg)
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .environmentObject(m.voice)
        .frame(minWidth: 740, minHeight: 520)
    }

    private var header: some View {
        HStack(spacing: 10) {
            NovaMark(size: 22)
            Wordmark(name: "hypernova")
            Text("Lens").hnLabel(.hnDim).padding(.leading, -2)
            Spacer()
            let ready = m.voice.ready
            // Opens the voice step (to see which way voice is ready, or to set it up).
            Button { m.go(.voice) } label: {
                StatusChip(text: ready ? "Voice ready" : "Voice off", color: ready ? .hnAccent : m.voice.stt?.error != nil ? .hnAmber : .hnDim)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Voice")
            .accessibilityLabel(ready ? "Voice is ready. Open the voice step." : "Voice is off. Open the voice step.")
        }
    }

    @ViewBuilder private func step(_ s: LensGuideModel.Step) -> some View {
        switch s {
        case .about: AboutStep()
        case .gestures: GesturesStep()
        case .voice: VoiceStep()
        }
    }

    private var footer: some View {
        HStack(spacing: 10) {
            if m.steps.first != m.step { CompactPill(title: "Back", kind: .ghost) { m.back() } }
            Spacer()
            Text("The same guide is in Lens on your phone.").font(HNFont.sans(11)).foregroundStyle(Color.hnDim)
            // On the voice step without a key, Connect takes Return; Done steps back.
            let keyFirst = m.step == .voice && !m.voice.ready
            ActionPill(title: m.isLast ? "Done" : "Next", kind: keyFirst ? .outline : .primary) { m.next() }
        }
    }
}

// MARK: - Rail

/// 01 WHAT IT'S FOR · 02 GESTURES · 03 VOICE (the steps this window has): done in mint, this one in ink, the rest as ghosts.
private struct GuideRail: View {
    @EnvironmentObject private var m: LensGuideModel

    var body: some View {
        let all = m.steps
        let current = all.firstIndex(of: m.step) ?? 0
        HStack(spacing: 28) {
            ForEach(Array(all.enumerated()), id: \.element) { i, s in
                Button { m.go(s) } label: {
                    HStack(spacing: 7) {
                        Text(String(format: "%02d", i + 1)).font(HNFont.mono(10)).foregroundStyle(i == current ? Color.hnAccent : i < current ? Color.hnAccentDim : Color.hnGhost)
                        Text(s.rail).hnLabel(i == current ? .hnInk : i < current ? .hnAccentDim : .hnGhost)
                    }
                    .padding(.vertical, 6)
                    .overlay(alignment: .bottom) {
                        Rectangle().fill(i == current ? Color.hnAccent : .clear).frame(height: 1).offset(y: 6)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Step \(i + 1), \(s.rail)")
                .accessibilityAddTraits(i == current ? .isSelected : [])
            }
            Spacer()
        }
        .overlay(alignment: .bottom) { Rectangle().fill(Color.hnLine).frame(height: 1).offset(y: 6) }
        .animation(.hnExpo(0.4), value: m.step)
    }
}

// MARK: - Steps

private struct StepTitle: View {
    var title: String
    var lede: String
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(HNFont.sans(30)).tracking(-0.5).foregroundStyle(Color.hnInk)
            Text(lede).font(HNFont.body).lineSpacing(3).foregroundStyle(Color.hnMuted)
                .frame(maxWidth: 380, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// One line of a list: a tracked mint label, then plain words.
private struct GuideItem: View {
    var key: String
    var text: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            Text(key).hnLabel(.hnAccent).frame(width: 62, alignment: .leading)
            Text(text).font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct AboutStep: View {
    var body: some View {
        HStack(alignment: .top, spacing: 40) {
            VStack(alignment: .leading, spacing: 0) {
                StepTitle(title: "Hypernova Lens.",
                          lede: "An on-the-go app for developers: wherever you are, you can still build products and see the results. The Mac does the work; the glasses are where you glance and decide.")
                VStack(alignment: .leading, spacing: 13) {
                    GuideItem(key: "Glance", text: "Your sessions at a look: who’s working, who needs you, how much of the 5 hours is left.")
                    GuideItem(key: "Talk", text: "Hold the temple and say what to do. Nothing is sent until you tap.")
                    GuideItem(key: "See", text: "Approve with a tap, hear replies read aloud, and say “show me” to see what was built.")
                }
                .frame(maxWidth: 380, alignment: .leading)
                .padding(.top, 24)
            }
            Spacer(minLength: 0)
            VStack(spacing: 10) {
                LensPreview()
                Text("What the lens shows").hnLabel(.hnGhost)
            }
            .padding(.top, 6)
        }
    }
}

/// The glance as the glasses draw it in their own text: green on black, one font, brightness for
/// hierarchy, parts a clear gap apart (never "·"), a project column, the firmware's glyphs only.
/// The same words as Lens's glance (lens/core: glance, format) and the phone's.
private struct LensPreview: View {
    private static let green = Color(hex: 0x3CFA44)

    var body: some View {
        let g = Self.green
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text("hypernova/").foregroundStyle(g.opacity(0.55))
                Spacer()
                Text("42%   resets in 2h 11m").foregroundStyle(g.opacity(0.55))
            }
            line(mark: "●", project: "hyperscribe", what: "Bash: npm test", age: "2m", level: 1)
            line(mark: "●", project: "0stems", what: "wants to run git push", age: "now", level: 0.85)
            line(mark: "○", project: "hypernova", what: "finished: 3 files", age: "6m", level: 0.68)
            Spacer(minLength: 0)
            Text("tap open     hold talk     double tap quiet").foregroundStyle(g.opacity(0.45))
        }
        .font(HNFont.mono(9.5))
        .lineLimit(1)
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(width: 300, height: 150, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.black))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(RadialGradient(colors: [g.opacity(0.06), .clear], center: .init(x: 0.5, y: 0.4), startRadius: 0, endRadius: 190)).allowsHitTesting(false))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.hnInk.opacity(0.1), lineWidth: 1))
        .shadow(color: g.opacity(0.08), radius: 26)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("A sample of the lens: three sessions, the one that needs you marked.")
    }

    private func line(mark: String, project: String, what: String, age: String, level: Double) -> some View {
        let g = Self.green
        return HStack(spacing: 9) {
            Text(mark).foregroundStyle(g.opacity(level))
            // The project as a column, so the activities line up.
            Text(project).foregroundStyle(g.opacity(level)).frame(width: 74, alignment: .leading)
            Text(what).foregroundStyle(g.opacity(level * 0.8)).truncationMode(.tail)
            Spacer(minLength: 4)
            Text(age).foregroundStyle(g.opacity(0.5))
        }
    }
}

private struct GesturesStep: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StepTitle(title: "Four gestures.", lede: "On the temple of the glasses. In Lens on your phone, the same four are buttons under the lens mirror.")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                GestureCard(glyph: .hold, title: "Hold", text: "Talk to the session in front of you. Let go to read what you said.")
                GestureCard(glyph: .tap, title: "Tap", text: "Open, send, allow, read aloud.")
                GestureCard(glyph: .doubleTap, title: "Double tap", text: "Back, discard, deny. On the glance, it quiets the lens.")
                GestureCard(glyph: .swipe, title: "Swipe", text: "Move between sessions, options and pages.")
            }
            .padding(.top, 22)
            Text("You can also say “status”, “send it”, “show me” or “approve”.")
                .font(HNFont.sans(12)).foregroundStyle(Color.hnDim).padding(.top, 14)
        }
    }
}

private struct GestureCard: View {
    var glyph: GestureGlyph.Kind
    var title: String
    var text: String
    var body: some View {
        GlassCard {
            HStack(alignment: .top, spacing: 14) {
                GestureGlyph(kind: glyph).frame(width: 30, height: 30)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(HNFont.sans(14, .semibold)).foregroundStyle(Color.hnInk)
                    Text(text).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A finger on the temple, in the icons' hairline: a press held (rings), one tap, two taps, a swipe.
private struct GestureGlyph: View {
    enum Kind { case hold, tap, doubleTap, swipe }
    var kind: Kind

    var body: some View {
        Canvas { ctx, s in
            let c = CGPoint(x: s.width / 2, y: s.height / 2)
            let ink = GraphicsContext.Shading.color(.hnAccent)
            let line = StrokeStyle(lineWidth: 1.2, lineCap: .round, lineJoin: .round)
            func dot(_ p: CGPoint, _ r: CGFloat) { ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: ink) }
            func ring(_ p: CGPoint, _ r: CGFloat, _ a: Double) { ctx.stroke(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(Color.hnAccent.opacity(a)), style: line) }
            switch kind {
            case .hold:
                dot(c, 3.2); ring(c, 8, 0.8); ring(c, 13, 0.35)
            case .tap:
                dot(c, 3.2); ring(c, 8, 0.6)
            case .doubleTap:
                let a = CGPoint(x: c.x - 6, y: c.y), b = CGPoint(x: c.x + 6, y: c.y)
                dot(a, 3); dot(b, 3); ring(a, 7, 0.4); ring(b, 7, 0.4)
            case .swipe:
                var p = Path()
                p.move(to: CGPoint(x: c.x - 11, y: c.y)); p.addLine(to: CGPoint(x: c.x + 11, y: c.y))
                p.move(to: CGPoint(x: c.x + 6, y: c.y - 5)); p.addLine(to: CGPoint(x: c.x + 11, y: c.y)); p.addLine(to: CGPoint(x: c.x + 6, y: c.y + 5))
                p.move(to: CGPoint(x: c.x - 6, y: c.y - 5)); p.addLine(to: CGPoint(x: c.x - 11, y: c.y)); p.addLine(to: CGPoint(x: c.x - 6, y: c.y + 5))
                ctx.stroke(p, with: ink, style: line)
                dot(c, 2.6)
            }
        }
        .accessibilityHidden(true)
    }
}

private struct VoiceStep: View {
    var body: some View {
        VoicePanel(context: .guide) {
            StepTitle(title: "Voice.", lede: "Soniox turns what you say into text, live as you talk. Connect it once: the key stays on this Mac.")
        }
    }
}
