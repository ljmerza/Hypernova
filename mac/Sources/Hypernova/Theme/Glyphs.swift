import SwiftUI

// Drawn glyphs and the small marks built from them, ported from the Android app's
// ui/components/Icons.kt and SessionMeta.kt (one design across phone, Mac and glasses, DECISIONS
// 2026-10-18): a session's metadata drawn, never "·"-joined text. Neither Manrope nor JetBrains Mono
// carries these shapes, and a system symbol would sit at another weight, so all of them share one
// hairline stroke, like the Nova mark.

enum Glyph {
    /// A session's age, or a time of day (a clock face).
    case clock
    /// How long something has run (a stopwatch), where `clock` is when.
    case timer
    /// Money: a balance, a cost (a banknote).
    case note
    /// Over the internet: the tailnet.
    case globe
    /// On the Mac's own network (Wi‑Fi's fan).
    case lan
    /// A phone.
    case phone
    /// Glasses (Hypernova Lens).
    case glasses
    /// The permission modes (modeGlyph): a shield for asking first, with a check for auto, crossed
    /// out for bypass, with a bar for "allowed only"; a pencil for accepting edits, a list for plan.
    case shield, shieldCheck, shieldOff, shieldBar, pencil, plan
}

/// A permission mode's glyph (see Glyph.shield).
func modeGlyph(_ mode: String?) -> Glyph {
    switch mode {
    // Codex's sandbox: allowed to edit its folder (read only asks first, the shield).
    case "acceptEdits", "workspace-write": return .pencil
    case "plan": return .plan
    case "auto": return .shieldCheck
    case "bypassPermissions": return .shieldOff
    case "dontAsk": return .shieldBar
    default: return .shield
    }
}

struct GlyphView: View {
    var glyph: Glyph
    var size: CGFloat = 12
    var color: Color = .hnDim
    var stroke: CGFloat = 1.1

    var body: some View {
        Canvas { ctx, s in
            let w = s.width, h = s.height
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * w, y: y * h) }
            let st = StrokeStyle(lineWidth: stroke, lineCap: .round, lineJoin: .round)
            func line(_ a: CGPoint, _ b: CGPoint) { var q = Path(); q.move(to: a); q.addLine(to: b); ctx.stroke(q, with: .color(color), style: st) }
            func circle(_ c: CGPoint, _ r: CGFloat) { ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)), with: .color(color), style: st) }
            func dot(_ c: CGPoint, _ r: CGFloat) { ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)), with: .color(color)) }
            func poly(_ pts: [CGPoint], close: Bool = false) {
                var q = Path(); q.addLines(pts); if close { q.closeSubpath() }
                ctx.stroke(q, with: .color(color), style: st)
            }
            func arc(_ c: CGPoint, _ r: CGFloat, from a: Double, sweep: Double) {
                var q = Path()
                q.addArc(center: c, radius: r, startAngle: .degrees(a), endAngle: .degrees(a + sweep), clockwise: false)
                ctx.stroke(q, with: .color(color), style: st)
            }
            switch glyph {
            case .clock:
                circle(p(0.5, 0.5), 0.38 * w)
                line(p(0.5, 0.5), p(0.5, 0.27))
                line(p(0.5, 0.5), p(0.66, 0.58))
            case .timer:
                circle(p(0.5, 0.56), 0.34 * w)
                line(p(0.5, 0.22), p(0.5, 0.1))
                line(p(0.4, 0.1), p(0.6, 0.1))
                line(p(0.79, 0.27), p(0.86, 0.2))
                line(p(0.5, 0.56), p(0.62, 0.42))
            case .note:
                ctx.stroke(Path(roundedRect: CGRect(x: 0.1 * w, y: 0.26 * h, width: 0.8 * w, height: 0.48 * h), cornerRadius: 0.08 * w), with: .color(color), style: st)
                circle(p(0.5, 0.5), 0.11 * w)
            case .globe:
                circle(p(0.5, 0.5), 0.38 * w)
                ctx.stroke(Path(ellipseIn: CGRect(x: 0.34 * w, y: 0.12 * h, width: 0.32 * w, height: 0.76 * h)), with: .color(color), style: st)
                line(p(0.12, 0.5), p(0.88, 0.5))
            case .lan:
                for r in [0.18, 0.32, 0.46] as [CGFloat] { arc(p(0.5, 0.8), r * w, from: -135, sweep: 90) }
                dot(p(0.5, 0.8), stroke * 0.8)
            case .phone:
                ctx.stroke(Path(roundedRect: CGRect(x: 0.28 * w, y: 0.08 * h, width: 0.44 * w, height: 0.84 * h), cornerRadius: 0.09 * w), with: .color(color), style: st)
                line(p(0.44, 0.8), p(0.56, 0.8))
            case .glasses:
                circle(p(0.27, 0.58), 0.17 * w)
                circle(p(0.73, 0.58), 0.17 * w)
                arc(p(0.5, 0.6), 0.07 * w, from: 200, sweep: 140)
                line(p(0.1, 0.56), p(0.04, 0.36))
                line(p(0.9, 0.56), p(0.96, 0.36))
            case .shield, .shieldCheck, .shieldOff, .shieldBar:
                var q = Path()
                q.move(to: p(0.5, 0.1)); q.addLine(to: p(0.84, 0.22)); q.addLine(to: p(0.84, 0.48))
                q.addCurve(to: p(0.5, 0.92), control1: p(0.84, 0.7), control2: p(0.68, 0.84))
                q.addCurve(to: p(0.16, 0.48), control1: p(0.32, 0.84), control2: p(0.16, 0.7))
                q.addLine(to: p(0.16, 0.22)); q.closeSubpath()
                ctx.stroke(q, with: .color(color), style: st)
                switch glyph {
                case .shieldCheck: poly([p(0.34, 0.5), p(0.46, 0.62), p(0.67, 0.38)])
                case .shieldOff: line(p(0.1, 0.9), p(0.9, 0.1))
                case .shieldBar: line(p(0.35, 0.5), p(0.65, 0.5))
                default: break
                }
            case .pencil:
                poly([p(0.16, 0.84), p(0.22, 0.62), p(0.66, 0.18), p(0.82, 0.34), p(0.38, 0.78)], close: true)
                line(p(0.56, 0.28), p(0.72, 0.44))
            case .plan:
                for y in [0.28, 0.5, 0.72] as [CGFloat] {
                    dot(p(0.2, y), stroke * 0.7)
                    line(p(0.38, y), p(0.86, y))
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A small sun with a dark disc over it: Claude can't reply for a while (a limit, the account, its
/// provider), the light blocked, nothing burned out. A blocked session's line leads with it.
struct EclipseMark: View {
    var color: Color
    var size: CGFloat = 11
    var body: some View {
        Canvas { ctx, s in
            let r = min(s.width, s.height) / 2
            let c = CGPoint(x: s.width / 2, y: s.height / 2)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * 0.78, y: c.y - r * 0.78, width: r * 1.56, height: r * 1.56)), with: .color(color.opacity(0.9)))
            let d = CGPoint(x: c.x + r * 0.22, y: c.y - r * 0.1)
            ctx.blendMode = .destinationOut
            ctx.fill(Path(ellipseIn: CGRect(x: d.x - r * 0.7, y: d.y - r * 0.7, width: r * 1.4, height: r * 1.4)), with: .color(.black))
        }
        .frame(width: size, height: size)
        .compositingGroup()
        .accessibilityHidden(true)
    }
}

/// A name as a small chip of neutral frosted glass, tracked caps, muted: a model ("OPUS"), an agent
/// count. Never a state: no point of light. The chip says nothing to VoiceOver on its own; the row
/// it sits in says the whole of it in one sentence.
struct TagChip: View {
    var text: String
    var color: Color = .hnMuted
    /// Never shorter than its words (a model, a count); false lets a long one (an account) be cut.
    var fixed = true
    var body: some View {
        Text(text).font(HNFont.sans(9, .semibold)).tracking(1.0).textCase(.uppercase)
            .foregroundStyle(color).lineLimit(1).truncationMode(.middle)
            .fixedSize(horizontal: fixed, vertical: false)
            .padding(.horizontal, 6).frame(height: 16)
            .background(Capsule().fill(LinearGradient(colors: [Color.hnBg700.opacity(0.62), Color.hnBg800.opacity(0.5)], startPoint: .top, endPoint: .bottom)))
            .overlay(Capsule().strokeBorder(Color.hnInk.opacity(0.1), lineWidth: 1))
    }
}

/// A count with its state's point of light, still: "● 2 need you" in amber, "● 3 working" in mint.
/// Several side by side read as a legend, never a "·"-joined list.
struct CountMark: View {
    var text: String
    var color: Color
    var body: some View {
        HStack(spacing: 4) {
            ZStack {
                Circle().fill(RadialGradient(colors: [color.opacity(0.4), color.opacity(0)], center: .center, startRadius: 0, endRadius: 4))
                    .frame(width: 8, height: 8)
                Circle().fill(color).frame(width: 3.8, height: 3.8)
            }
            Text(text).font(HNFont.label).tracking(0.9).foregroundStyle(color).lineLimit(1).fixedSize()
        }
    }
}

/// The permission mode: its glyph and, when there's room, its words ("ask first"), quiet.
struct ModeTag: View {
    var mode: String?
    var label: String?
    var color: Color = .hnDim
    var body: some View {
        HStack(spacing: 3) {
            GlyphView(glyph: modeGlyph(mode), size: 10.5, color: color, stroke: 1.05)
            if let label { Text(label).font(HNFont.sans(11)).foregroundStyle(color).lineLimit(1).fixedSize() }
        }
    }
}

/// A figure with its glyph, quiet: "◷ 9:00", "⏱ 3m", a balance after a banknote.
struct GlyphFigure: View {
    var glyph: Glyph
    var text: String
    var color: Color = .hnDim
    var size: CGFloat = 10
    var body: some View {
        HStack(spacing: 3) {
            GlyphView(glyph: glyph, size: size, color: color.opacity(0.85), stroke: 1.05)
            Text(text).font(HNFont.mono(10)).foregroundStyle(color).lineLimit(1).fixedSize()
        }
    }
}

/// A session's short id and how long since it was last active, as quiet marks: "c7  ◷ 1m".
struct SessionStamp: View {
    var hex: String
    var age: String
    var body: some View {
        HStack(spacing: 7) {
            Text(hex).font(HNFont.mono(10)).foregroundStyle(Color.hnDim)
            GlyphFigure(glyph: .clock, text: age)
        }
        .fixedSize()
    }
}
