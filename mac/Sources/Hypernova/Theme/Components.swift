import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

// More hyper-cosmos components for the Mac app (references/components.md).

// MARK: - Brand (the Nova mark itself lives in Planet.swift)

/// The brand row: Nova mark plus `hypernova/`.
struct BrandRow: View {
    var body: some View {
        HStack(spacing: 9) {
            NovaMark(size: 22)
            Wordmark(name: "hypernova")
        }
    }
}

// MARK: - Meter

/// A usage window as a projection meter (Core/UsageMeter.swift): the label with the percentage in
/// mono beside it, then the bar, a 1pt track with a 3pt fill (accent, amber ≥ 80%, danger at 100%)
/// and, while something is being spent, the same hue faint ahead of it to where the pace leads by
/// the reset, with a red notch at the end when the limit would come first. One tag on the right:
/// the time to that limit in red after the drawn "runs into the end" glyph, or else the reset
/// countdown, dim, after the reset glyph. Changes ease once; nothing moves in between. VoiceOver
/// reads one sentence.
struct Meter: View {
    var label: String
    /// A model's week: its chip before the label ("[FABLE] 7 DAYS"), never "Fable weekly".
    var chip: String? = nil
    var reading: MeterReading

    private var color: Color {
        guard let used = reading.used else { return .hnDim }
        return used >= 100 ? .hnDanger : used >= 80 ? .hnAmber : .hnAccent
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if let chip { TagChip(text: chip).alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 3.5 } }
                Text(label).hnLabel(.hnDim).lineLimit(1)
                // Red once it's spent, as the phone's.
                Text(reading.used.map { "\(Int($0.rounded()))%" } ?? "—").font(HNFont.mono(11, .medium)).foregroundStyle(reading.atLimit ? Color.hnDanger : Color.hnInk)
                Spacer(minLength: 8)
                tag
            }
            bar
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(reading.sentence)
    }

    @ViewBuilder private var tag: some View {
        switch reading.tag {
        case .limit(let s):
            HStack(spacing: 5) {
                LimitGlyph(size: 10)
                Text(MeterReading.short(s)).font(HNFont.mono(11, .medium)).foregroundStyle(Color.hnDanger)
            }
            .fixedSize()
        case .reset(let s):
            HStack(spacing: 5) {
                ResetGlyph(size: 9)
                Text(s > 0 ? MeterReading.short(s) : "now").font(HNFont.monoS).foregroundStyle(Color.hnDim)
            }
            .fixedSize()
        case .resetOn(let d):
            HStack(spacing: 5) {
                ResetGlyph(size: 9)
                Text(Clock.weekdayTime(d)).font(HNFont.monoS).foregroundStyle(Color.hnDim)
            }
            .fixedSize()
        case nil:
            EmptyView()
        }
    }

    private var bar: some View {
        GeometryReader { g in
            let w = g.size.width
            let x = { (pct: Double?) -> CGFloat in w * CGFloat(min(1, max(0, (pct ?? 0) / 100))) }
            ZStack(alignment: .leading) {
                Rectangle().fill(Color.hnLine).frame(height: 1)
                // The pace: faint, from the start, so it shows only past the used part.
                Capsule().fill(color.opacity(0.3))
                    .frame(width: reading.paceAhead ? max(3, x(reading.projected)) : max(0, x(reading.used)), height: 3)
                    .opacity(reading.paceAhead ? 1 : 0)
                Capsule().fill(color).frame(width: max(0, x(reading.used)), height: 3)
                Capsule().fill(Color.hnDanger).frame(width: 2, height: 9)
                    .offset(x: w - 2)
                    .opacity(reading.limitFirst ? 1 : 0)
            }
            .frame(height: 9)
            .animation(.hnExpo(0.6), value: reading.used)
            .animation(.hnExpo(0.6), value: reading.projected)
            .animation(.hnExpo(0.4), value: reading.limitFirst)
        }
        .frame(height: 9)
    }
}

/// A plain progress line for downloads.
struct ProgressLine: View {
    var fraction: Double?
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Rectangle().fill(Color.hnLine).frame(height: 1)
                if let fraction {
                    Capsule().fill(Color.hnAccent).frame(width: max(3, g.size.width * min(1, max(0, fraction))), height: 3)
                        .animation(.hnExpo(0.3), value: fraction)
                } else {
                    IndeterminateBar(width: g.size.width)
                }
            }
            .frame(height: 3)
        }
        .frame(height: 3)
    }
}

private struct IndeterminateBar: View {
    var width: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            let k = (t / 1.6).truncatingRemainder(dividingBy: 1)
            let w = width * 0.28
            Capsule().fill(Color.hnAccent.opacity(0.85)).frame(width: w, height: 3)
                .offset(x: reduceMotion ? 0 : (width + w) * k - w)
        }
        .frame(width: width, alignment: .leading)
        .clipped()
    }
}

// MARK: - QR code

enum QRCode {
    /// A 1-pixel-per-module QR image in the app's colours (dark modules on the light tile colour).
    static func image(_ text: String) -> CGImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let qr = f.outputImage else { return nil }
        let tint = CIFilter.falseColor()
        tint.inputImage = qr
        tint.color0 = CIColor(red: 0x14 / 255, green: 0x11 / 255, blue: 0x0E / 255)
        tint.color1 = CIColor(red: 0xF5 / 255, green: 0xF2 / 255, blue: 0xEB / 255)
        guard let out = tint.outputImage else { return nil }
        return CIContext(options: [.useSoftwareRenderer: false]).createCGImage(out, from: out.extent)
    }
}

/// A crisp QR code on a light tile: modules scaled with interpolation off.
struct QRCodeView: View {
    var text: String
    var side: CGFloat = 200
    @State private var image: CGImage?
    @State private var shownText = ""

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.hnInk)
            // Offscreen renders don't run onAppear, so they draw the code straight away.
            if let image = image ?? (SnapshotMode.active ? QRCode.image(text) : nil) {
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .aspectRatio(1, contentMode: .fit)
                    .padding(side * 0.07)
                    .transition(.opacity)
            }
        }
        .frame(width: side, height: side)
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.hnInk.opacity(0.4), lineWidth: 1))
        .shadow(color: Color.hnAccent.opacity(0.12), radius: 24)
        .onAppear(perform: render)
        .onChange(of: text) { _, _ in render() }
        .accessibilityLabel("QR code")
    }

    private func render() {
        guard shownText != text else { return }
        shownText = text
        withAnimation(.hnExpo(0.3)) { image = QRCode.image(text) }
    }
}

// MARK: - Scrolling

/// Offscreen snapshots (`--snapshots`) can't draw AppKit-backed scroll views, so they get a
/// clipped stack instead. Never set in the running app.
enum SnapshotMode { static var active = false }

struct HNScroll<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        if SnapshotMode.active {
            VStack(spacing: 0) { content }.frame(maxHeight: .infinity, alignment: .top).clipped()
        } else {
            ScrollView(.vertical) { content }.scrollIndicators(.never)
        }
    }
}

// MARK: - Log (a mini terminal)

struct LogView: View {
    var title: String
    var lines: [String]
    var running: Bool
    var failed = false
    var height: CGFloat = 150

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Seam(label: title, trail: running ? "running" : failed ? "failed" : "done", activity: running ? .live : failed ? .alert : .rest)
            if SnapshotMode.active {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(lines.suffix(Int(height / 16)).enumerated()), id: \.offset) { _, line in
                        Text(line).font(HNFont.monoS).foregroundStyle(color(for: line)).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if running { Cursor() }
                }
                .frame(height: height, alignment: .bottom).clipped()
            } else {
            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                            Text(line)
                                .font(HNFont.monoS)
                                .foregroundStyle(color(for: line))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .id(i)
                        }
                        if running { Cursor().id("cursor") }
                    }
                }
                .scrollIndicators(.never)
                .onChange(of: lines.count) { _, n in
                    withAnimation(.hnExpo(0.2)) { proxy.scrollTo(running ? AnyHashable("cursor") : AnyHashable(n - 1), anchor: .bottom) }
                }
            }
            .frame(height: height)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.hnBg950.opacity(0.86)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(running ? Color.hnAccent.opacity(0.28) : failed ? Color.hnDanger.opacity(0.35) : Color.hnInk.opacity(0.08), lineWidth: 1))
    }

    private func color(for line: String) -> Color {
        let l = line.lowercased()
        if l.contains("error") || l.contains("failed") { return .hnDanger }
        if l.contains("warn") { return .hnAmber }
        if l.contains("success") || l.hasPrefix("✓") || l.contains("installed") { return .hnAccent }
        return .hnMuted
    }

    private struct Cursor: View {
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        var body: some View {
            TimelineView(.periodic(from: .now, by: 0.53)) { tl in
                let on = reduceMotion || Int(tl.date.timeIntervalSinceReferenceDate / 0.53) % 2 == 0
                Rectangle().fill(Color.hnAccent).frame(width: 6, height: 12).opacity(on ? 1 : 0)
            }
        }
    }
}

// MARK: - Fields

/// A pill-ish input: label above, bg800 well, ink 10% ring, our own placeholder (in inkGhost).
struct HNField: View {
    var label: String
    @Binding var text: String
    var placeholder: String = ""
    var mono = false
    var secure = false
    var hint: String?
    /// Marked: what's in it was refused (a key).
    var invalid = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(label).hnLabel(.hnDim)
            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(placeholder).font(mono ? HNFont.monoM : HNFont.small).foregroundStyle(Color.hnGhost).lineLimit(1).allowsHitTesting(false)
                }
                Group {
                    if SnapshotMode.active { Text(secure ? String(repeating: "•", count: text.count) : text).font(mono ? HNFont.monoM : HNFont.small).lineLimit(1) }
                    else if secure { SecureField("", text: $text) } else { TextField("", text: $text) }
                }
                .textFieldStyle(.plain)
                .font(mono ? HNFont.monoM : HNFont.small)
                .foregroundStyle(Color.hnInk)
                .focused($focused)
                .autocorrectionDisabled()
            }
            .padding(.horizontal, 14)
            .frame(height: 38)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.hnBg800.opacity(0.7)))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(invalid ? Color.hnDanger.opacity(0.6) : focused ? Color.hnAccent.opacity(0.45) : Color.hnInk.opacity(0.1), lineWidth: 1))
            .animation(.hnExpo(0.15), value: focused)
            if let hint { Text(hint).font(HNFont.sans(11)).foregroundStyle(invalid ? Color.hnDanger : Color.hnDim).fixedSize(horizontal: false, vertical: true) }
        }
    }
}

// MARK: - Drawn icons (one consistent hairline stroke)

struct CheckIcon: View {
    var size: CGFloat = 12
    var color: Color = .hnAccent
    var body: some View {
        Canvas { ctx, s in
            var p = Path()
            p.move(to: CGPoint(x: s.width * 0.16, y: s.height * 0.54))
            p.addLine(to: CGPoint(x: s.width * 0.4, y: s.height * 0.76))
            p.addLine(to: CGPoint(x: s.width * 0.86, y: s.height * 0.26))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: max(1.2, size / 8), lineCap: .round, lineJoin: .round))
        }
        .frame(width: size, height: size)
    }
}

struct CrossIcon: View {
    var size: CGFloat = 12
    var color: Color = .hnDanger
    var body: some View {
        Canvas { ctx, s in
            var p = Path()
            p.move(to: CGPoint(x: s.width * 0.22, y: s.height * 0.22)); p.addLine(to: CGPoint(x: s.width * 0.78, y: s.height * 0.78))
            p.move(to: CGPoint(x: s.width * 0.78, y: s.height * 0.22)); p.addLine(to: CGPoint(x: s.width * 0.22, y: s.height * 0.78))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: max(1.2, size / 8), lineCap: .round))
        }
        .frame(width: size, height: size)
    }
}

/// The reset: a circle that comes round again (an open ring with its arrow, clockwise).
struct ResetGlyph: View {
    var size: CGFloat = 9
    var color: Color = .hnDim
    var body: some View {
        Canvas { ctx, s in
            let c = CGPoint(x: s.width / 2, y: s.height / 2), r = min(s.width, s.height) * 0.36
            let lw = max(1.1, size / 8)
            // From right of the top, round through the bottom, to just left of the top.
            let start = Angle.degrees(-35), end = Angle.degrees(258)
            var arc = Path()
            arc.addArc(center: c, radius: r, startAngle: start, endAngle: end, clockwise: false)
            ctx.stroke(arc, with: .color(color), style: StrokeStyle(lineWidth: lw, lineCap: .round))
            // The arrowhead at the end, pointing on round (y grows downwards, so on is +angle).
            let t = end.radians
            let tip = CGPoint(x: c.x + r * cos(t), y: c.y + r * sin(t))
            let dir = CGPoint(x: -sin(t), y: cos(t)), nrm = CGPoint(x: cos(t), y: sin(t))
            let a = size * 0.4
            var head = Path()
            head.move(to: CGPoint(x: tip.x + dir.x * a * 0.75, y: tip.y + dir.y * a * 0.75))
            head.addLine(to: CGPoint(x: tip.x - dir.x * a * 0.35 + nrm.x * a * 0.62, y: tip.y - dir.y * a * 0.35 + nrm.y * a * 0.62))
            head.addLine(to: CGPoint(x: tip.x - dir.x * a * 0.35 - nrm.x * a * 0.62, y: tip.y - dir.y * a * 0.35 - nrm.y * a * 0.62))
            head.closeSubpath()
            ctx.fill(head, with: .color(color))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The limit before the reset: a line that runs into the meter's red notch.
struct LimitGlyph: View {
    var size: CGFloat = 10
    var color: Color = .hnDanger
    var body: some View {
        Canvas { ctx, s in
            let w = s.width, h = s.height, lw = max(1.1, size / 8)
            var run = Path()
            run.move(to: CGPoint(x: w * 0.06, y: h * 0.5)); run.addLine(to: CGPoint(x: w * 0.66, y: h * 0.5))
            run.move(to: CGPoint(x: w * 0.44, y: h * 0.28)); run.addLine(to: CGPoint(x: w * 0.66, y: h * 0.5)); run.addLine(to: CGPoint(x: w * 0.44, y: h * 0.72))
            ctx.stroke(run, with: .color(color.opacity(0.75)), style: StrokeStyle(lineWidth: lw, lineCap: .round, lineJoin: .round))
            var notch = Path()
            notch.move(to: CGPoint(x: w * 0.88, y: h * 0.1)); notch.addLine(to: CGPoint(x: w * 0.88, y: h * 0.9))
            ctx.stroke(notch, with: .color(color), style: StrokeStyle(lineWidth: lw * 1.3, lineCap: .round))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - Rows, links

/// A menu row with a hover wash (the popover's action list).
struct MenuRowStyle: ButtonStyle {
    var danger = false
    func makeBody(configuration: Configuration) -> some View {
        MenuRowBody(configuration: configuration, danger: danger)
    }
    private struct MenuRowBody: View {
        let configuration: ButtonStyleConfiguration
        let danger: Bool
        @State private var hover = false
        @Environment(\.isEnabled) private var isEnabled
        var body: some View {
            configuration.label
                .opacity(isEnabled ? 1 : 0.4)
                .font(HNFont.small)
                .foregroundStyle(danger ? (hover ? Color.hnDanger : Color.hnMuted) : (hover ? Color.hnInk : Color.hnMuted))
                .padding(.horizontal, 10).frame(height: 30)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.hnInk.opacity(configuration.isPressed ? 0.1 : hover ? 0.06 : 0)))
                .contentShape(Rectangle())
                .onHover { hover = $0 }
                .animation(.hnExpo(0.15), value: hover)
        }
    }
}

/// Small accent text button ("Show log", "Copy").
struct LinkStyle: ButtonStyle {
    var color: Color = .hnAccent
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HNFont.sans(12, .semibold))
            .foregroundStyle(color.opacity(configuration.isPressed ? 0.6 : 1))
            .contentShape(Rectangle())
    }
}

/// A compact pill (34pt), for secondary actions inside cards.
struct CompactPill: View {
    var title: String
    var kind: PillKind = .outline
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(HNFont.sans(12, .semibold)).padding(.horizontal, 14).frame(height: 30)
        }
        .buttonStyle(PillStyle(kind: kind))
    }
}

/// A primary pill that shows live bars while busy.
struct ActionPill: View {
    var title: String
    var kind: PillKind = .primary
    var busy = false
    var disabled = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                if busy { PlanetMeter(activity: .live).colorMultiply(kind == .primary ? .hnBg : .hnInk) }
                Text(title).font(HNFont.sans(13, .semibold))
            }
            .padding(.horizontal, 22).frame(height: 40)
        }
        .buttonStyle(PillStyle(kind: kind))
        .disabled(busy || disabled)
        .opacity(disabled ? 0.45 : 1)
        .keyboardShortcut(kind == .primary ? .defaultAction : nil)
    }
}

// MARK: - Ignition (pairing success)

/// A mint ignition: the Nova mark grows out of a flash, with two rings racing out.
/// Rare and meaningful, so it gets a little drama; Reduce Motion shows the end state.
struct Ignition: View {
    var size: CGFloat = 120
    /// Offscreen renders show where it ends, the mark in place.
    @State private var t: Double = SnapshotMode.active ? 1 : 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Color.hnAccentBright.opacity(0.55 * (1 - t)), .clear], center: .center, startRadius: 0, endRadius: size * 0.7))
                .frame(width: size * 1.6, height: size * 1.6)
            Circle().strokeBorder(Color.hnAccent.opacity(0.7 * (1 - t)), lineWidth: 1.4)
                .frame(width: size * (0.3 + 1.3 * t), height: size * (0.3 + 1.3 * t))
            Circle().strokeBorder(Color(hex: 0x5FE0C8).opacity(0.5 * (1 - t)), lineWidth: 1)
                .frame(width: size * (0.2 + 0.95 * t), height: size * (0.2 + 0.95 * t))
            NovaMark(size: size * 0.62)
                .scaleEffect(0.4 + 0.6 * t)
                .opacity(min(1, t * 2.5))
        }
        .frame(width: size * 1.6, height: size * 1.6)
        .onAppear {
            if reduceMotion { t = 1 } else { withAnimation(.hnExpo(1.6)) { t = 1 } }
        }
        .accessibilityHidden(true)
    }
}

/// The standard backdrop for Hypernova windows: cosmos behind, grain on top.
struct CosmosBackdrop<Content: View>: View {
    var light: Double = 1
    @ViewBuilder var content: Content
    var body: some View {
        ZStack {
            CosmosView(light: light)
            content
            GrainView().allowsHitTesting(false)
        }
        .background(Color.hnBg)
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
    }
}
