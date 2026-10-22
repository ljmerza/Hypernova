// Hyper cosmos for SwiftUI: tokens, the ambient cosmos, grain, and the core components.
// Copied from skills/hyper-cosmos/assets/swiftui/HyperCosmos.swift (the design language's starter),
// with one change: fonts load through CoreText (Fonts.swift) so Manrope gets ss01 + tabular figures.
//
//   ZStack {
//       CosmosView()                        // ambient sky, behind everything
//       content
//       GrainView().allowsHitTesting(false) // film grain, over everything
//   }
//   .background(Color.hnBg)

import SwiftUI

// MARK: - Tokens

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
    static let hnBg = Color(hex: 0x14110E)
    static let hnBg950 = Color(hex: 0x0F0D0A)
    static let hnBg900 = Color(hex: 0x1B1814)
    static let hnBg800 = Color(hex: 0x221F1B)
    static let hnBg700 = Color(hex: 0x2C2823)
    static let hnLine = Color(hex: 0x2C2823)
    static let hnLineStrong = Color(hex: 0x45403A)
    static let hnInk = Color(hex: 0xF5F2EB)
    static let hnMuted = Color(hex: 0xB5B0A6)
    static let hnDim = Color(hex: 0x7A7468)
    static let hnGhost = Color(hex: 0x45403A)
    static let hnAccent = Color(hex: 0x7FFFA0)
    static let hnAccentBright = Color(hex: 0xB6FFC9)
    static let hnAccentDim = Color(hex: 0x4A8C5E)
    static let hnAmber = Color(hex: 0xE8B341)
    static let hnDanger = Color(hex: 0xFF7878)
}

enum HNFont {
    static func sans(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font { Fonts.font(.manrope, size, weight) }
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font { Fonts.font(.mono, size, weight) }
    static let display = sans(34)
    static let title = sans(22)
    static let heading = sans(16)
    static let body = sans(15)
    static let small = sans(13)
    static let label = sans(10, .semibold)
    static let wordmark = sans(20, .ultraLight)
    static let monoM = mono(12)
    static let monoS = mono(11)
}

extension Animation {
    /// The signature expo-out curve, cubic-bezier(.16, 1, .3, 1).
    static func hnExpo(_ duration: Double = 0.5) -> Animation { .timingCurve(0.16, 1, 0.3, 1, duration: duration) }
}

extension View {
    /// Tiny tracked uppercase label (10pt, 0.18em).
    func hnLabel(_ color: Color = .hnMuted) -> some View {
        font(HNFont.label).tracking(1.8).textCase(.uppercase).foregroundStyle(color)
    }
}

// MARK: - Ambient cosmos (0stems DigitalCosmos)

/// State lives in a reference type, so the 30fps timeline redraws the Canvas without re-rendering views.
final class CosmosModel {
    struct Star { var x, y, size, alpha, vx, vy, phase, phaseSpeed, twPhase, twSpeed: Double; var anchor: Bool; var label: String? }
    struct Nebula { var x, y, r, alpha, vx, vy, phase, speed: Double; var color: Color }
    private(set) var stars: [Star] = []
    private(set) var nebulae: [Nebula] = []
    private var size: CGSize = .zero
    private var lastT: Double = -1
    /// 0…1: how much light the ambient sky gives (it steps back behind dense text, as in the popover).
    var light: Double = 1

    func ensure(_ s: CGSize) {
        guard s != size, s.width > 0, s.height > 0 else { return }
        if stars.isEmpty { build(s) } else {
            let sx = s.width / size.width, sy = s.height / size.height
            for i in stars.indices { stars[i].x *= sx; stars[i].y *= sy }
            for i in nebulae.indices { nebulae[i].x *= sx; nebulae[i].y *= sy }
        }
        size = s
    }

    private func build(_ s: CGSize) {
        let w = s.width, h = s.height, minSide = min(w, h)
        func r(_ a: Double, _ b: Double) -> Double { Double.random(in: a...b) }
        let tints = [Color(hex: 0x7FFFA0), Color(hex: 0x96FFC8), Color(hex: 0xB4FF8C), Color(hex: 0x69FFB9)]
        nebulae = tints.map { c in Nebula(x: r(0, w), y: r(0, h), r: minSide * r(0.3, 0.55) * 1.5, alpha: r(0.07, 0.14), vx: r(-0.25, 0.25), vy: r(-0.25, 0.25), phase: r(0, 6.28), speed: r(0.08, 0.18), color: c) }
        let clusters = (0..<6).map { _ in (x: r(0, w), y: r(0, h), rad: minSide * r(0.12, 0.22)) }
        let hex = Array("0123456789abcdef")
        stars = (0..<95).map { _ in
            let roll = Double.random(in: 0..<1), anchor = roll < 0.07, mid = !anchor && roll < 0.35
            var x = r(0, w), y = r(0, h)
            if Double.random(in: 0..<1) < 0.55, let c = clusters.randomElement() {
                let a = r(0, 6.28), d = pow(Double.random(in: 0..<1), 0.6) * c.rad
                x = (c.x + cos(a) * d).truncatingRemainder(dividingBy: w); if x < 0 { x += w }
                y = (c.y + sin(a) * d).truncatingRemainder(dividingBy: h); if y < 0 { y += h }
            }
            let speed = anchor ? r(0.08, 0.18) : r(0.15, 0.55), ang = r(0, 6.28)
            let label: String? = (anchor || Double.random(in: 0..<1) < 0.15) ? String([hex.randomElement()!, hex.randomElement()!]) : nil
            return Star(x: x, y: y, size: anchor ? r(2.4, 3.6) : mid ? r(1.1, 2) : r(0.5, 1), alpha: anchor ? r(0.6, 0.9) : mid ? r(0.28, 0.5) : r(0.1, 0.22),
                        vx: cos(ang) * speed, vy: sin(ang) * speed, phase: r(0, 6.28), phaseSpeed: r(0.2, 0.45), twPhase: r(0, 6.28), twSpeed: r(0.8, 2.2), anchor: anchor, label: label)
        }
    }

    func step(_ t: Double) {
        let dt = lastT < 0 ? 0 : min(max(t - lastT, 0), 0.05); lastT = t
        guard dt > 0 else { return }
        let wa = t * 0.04, wx = cos(wa) * 0.4, wy = sin(wa) * 0.4, w = size.width, h = size.height
        for i in nebulae.indices {
            nebulae[i].x += (nebulae[i].vx + wx * 0.5) * dt; nebulae[i].y += (nebulae[i].vy + wy * 0.5) * dt
            let m = nebulae[i].r
            if nebulae[i].x < -m { nebulae[i].x = w + m }; if nebulae[i].x > w + m { nebulae[i].x = -m }
            if nebulae[i].y < -m { nebulae[i].y = h + m }; if nebulae[i].y > h + m { nebulae[i].y = -m }
        }
        for i in stars.indices {
            stars[i].x += (stars[i].vx + wx) * dt; stars[i].y += (stars[i].vy + wy) * dt
            if stars[i].x < 0 { stars[i].x += w }; if stars[i].x > w { stars[i].x -= w }
            if stars[i].y < 0 { stars[i].y += h }; if stars[i].y > h { stars[i].y -= h }
        }
    }
}

/// The ambient sky. 30fps from one TimelineView; a static frame under Reduce Motion.
struct CosmosView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model = CosmosModel()
    var light: Double = 1
    /// Stops the clock while the view isn't on screen (e.g. the menu bar popover is closed).
    var paused = false
    /// Hex labels beside stars. Off where dense text sits on the sky (the menu bar popover).
    var labels = true
    /// Paints the warm near-black base. Off over glass, where the blurred desktop shows through.
    var base = true

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion || paused)) { timeline in
            Canvas { ctx, size in
                let t = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 100_000)
                model.ensure(size); model.step(t); model.light = light
                if base { ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.hnBg)) }
                for n in model.nebulae {
                    let a = n.alpha * (0.6 + 0.4 * sin(t * n.speed + n.phase)) * light
                    let g = Gradient(stops: [.init(color: n.color.opacity(a), location: 0), .init(color: n.color.opacity(a * 0.55), location: 0.3),
                                             .init(color: n.color.opacity(a * 0.2), location: 0.6), .init(color: n.color.opacity(0), location: 1)])
                    ctx.fill(Path(ellipseIn: CGRect(x: n.x - n.r, y: n.y - n.r, width: n.r * 2, height: n.r * 2)),
                             with: .radialGradient(g, center: CGPoint(x: n.x, y: n.y), startRadius: 0, endRadius: n.r))
                }
                let starLight = light <= 0.001 ? 0 : 0.4 + 0.6 * light
                for s in model.stars {
                    let a = s.alpha * (0.7 + 0.3 * sin(t * s.phaseSpeed + s.phase)) * (0.85 + 0.15 * sin(t * s.twSpeed + s.twPhase)) * starLight
                    guard a > 0.002 else { continue }
                    func dot(_ k: Double, _ al: Double) {
                        let rr = s.size * k
                        ctx.fill(Path(ellipseIn: CGRect(x: s.x - rr, y: s.y - rr, width: rr * 2, height: rr * 2)), with: .color(Color.hnAccent.opacity(al)))
                    }
                    if s.anchor { dot(4.5, a * 0.05); dot(2.5, a * 0.14); dot(1.4, a * 0.45) }
                    dot(1, a)
                    if labels, let l = s.label {
                        ctx.draw(Text(l).font(HNFont.mono(9)).foregroundStyle(Color.hnAccent.opacity(a * 0.55)), at: CGPoint(x: s.x + s.size + 7, y: s.y), anchor: .leading)
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Grain (0stems GrainField)

/// Overlay film grain: alpha 0…90/255 noise, overlay blend at 0.32, 24 patterns a second.
/// Four tiles are generated once; each frame draws one at a random offset.
struct GrainView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private static let tiles: [CGImage] = (0..<4).compactMap { _ in GrainView.makeTile(192) }
    var opacity: Double = 0.32
    var paused = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 24.0, paused: reduceMotion || paused)) { timeline in
            Canvas { ctx, size in
                guard !GrainView.tiles.isEmpty else { return }
                let f = reduceMotion ? 0 : Int(timeline.date.timeIntervalSinceReferenceDate * 24)
                let img = Image(decorative: GrainView.tiles[f % GrainView.tiles.count], scale: 1)
                let cell = 1.6, side = 192 * cell
                let ox = -Double((f * 73) % 192) * cell, oy = -Double((f * 151) % 192) * cell
                var y = oy
                while y < size.height { var x = ox; while x < size.width { ctx.draw(img, in: CGRect(x: x, y: y, width: side, height: side)); x += side }; y += side }
            }
        }
        .blendMode(.overlay)
        .opacity(opacity)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private static func makeTile(_ n: Int) -> CGImage? {
        var px = [UInt8](repeating: 0, count: n * n * 4)
        for i in 0..<(n * n) {
            let v = UInt8.random(in: 0...255), a = UInt8.random(in: 0...90)
            // Premultiplied RGBA.
            let pv = UInt8((Int(v) * Int(a)) / 255)
            px[i * 4] = pv; px[i * 4 + 1] = pv; px[i * 4 + 2] = pv; px[i * 4 + 3] = a
        }
        guard let provider = CGDataProvider(data: Data(px) as CFData) else { return nil }
        return CGImage(width: n, height: n, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: n * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}

// MARK: - Components

enum Activity { case rest, live, alert }

/// Hyperscribe's seam: planet · LABEL · hairline · trail. The label's colour eases with the planet's.
struct Seam: View {
    var label: String
    var trail: String? = nil
    var activity: Activity = .rest
    var body: some View {
        HStack(spacing: 10) {
            PlanetMeter(activity: activity)
            // When the row is tight the hairline gives way first, then the trail; the label never does.
            Text(label).hnLabel(activity == .alert ? .hnAmber : .hnMuted).lineLimit(1)
                .animation(.easeOut(duration: 0.9), value: activity)
                .layoutPriority(2)
            Rectangle().fill(Color.hnLine).frame(height: 1)
            if let trail { Text(trail).font(HNFont.label).tracking(0.9).foregroundStyle(Color.hnDim).lineLimit(1).layoutPriority(1) }
        }
    }
}

/// 0stems glass card: dense translucent gradient, 1pt ink ring, top/bottom highlight lines.
struct GlassCard<Content: View>: View {
    var needsYou = false
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                LinearGradient(colors: [Color.hnBg800.opacity(0.84), Color.hnBg900.opacity(0.78), Color.hnBg900.opacity(0.86)], startPoint: .top, endPoint: .bottom)
                    .overlay(needsYou ? Color.hnAmber.opacity(0.05) : .clear)
            )
            .overlay(alignment: .top) { LinearGradient(colors: [.clear, Color.hnInk.opacity(0.25), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1) }
            .overlay(alignment: .bottom) { LinearGradient(colors: [.clear, Color.hnInk.opacity(0.12), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1) }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(needsYou ? Color.hnAmber.opacity(0.35) : Color.hnInk.opacity(0.08), lineWidth: 1))
    }
}

enum PillKind { case primary, outline, ghost, danger }

struct PillStyle: ButtonStyle {
    var kind: PillKind
    func makeBody(configuration: Configuration) -> some View {
        let (bg, fg, ring): (Color, Color, Color) = switch kind {
        case .primary: (.hnAccent, .hnBg, Color.hnAccentBright.opacity(0.5))
        case .outline: (Color.hnBg800.opacity(0.3), .hnInk, Color.hnInk.opacity(0.22))
        case .ghost: (Color.hnBg800.opacity(0.2), .hnMuted, .hnLine)
        case .danger: (.clear, .hnDanger, Color.hnDanger.opacity(0.4))
        }
        return configuration.label
            .foregroundStyle(fg)
            .background(Capsule().fill(bg.opacity(configuration.isPressed ? 0.8 : 1)))
            .overlay(Capsule().strokeBorder(ring, lineWidth: 1))
            .offset(y: configuration.isPressed ? 1 : 0)
            .animation(.hnExpo(0.15), value: configuration.isPressed)
    }
}

struct StatusChip: View {
    var text: String
    var color: Color = .hnAccent
    var live = false
    var filled = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        HStack(spacing: 6) {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !live || reduceMotion)) { tl in
                let k = 0.5 - 0.5 * cos(2 * .pi * tl.date.timeIntervalSinceReferenceDate / 2.4)
                Circle().fill(filled ? Color.hnBg : color).frame(width: 5, height: 5).opacity(live && !reduceMotion ? 1 - 0.65 * k : 1)
            }
            Text(text).font(HNFont.label).tracking(1.35).textCase(.uppercase).foregroundStyle(filled ? Color.hnBg : color)
        }
        .padding(.horizontal, 9).padding(.vertical, 4)
        .background(Capsule().fill(filled ? color : color.opacity(0.08)))
        .overlay(Capsule().strokeBorder(color.opacity(filled ? 1 : 0.3), lineWidth: 1))
    }
}

/// `hypernova/`: light lowercase name plus a mint slash.
struct Wordmark: View {
    var name: String
    var body: some View {
        (Text(name).foregroundColor(.hnMuted) + Text("/").foregroundColor(.hnAccent))
            .font(HNFont.wordmark).tracking(-0.4)
            .accessibilityLabel(name)
    }
}

