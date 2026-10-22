import AppKit
import SwiftUI

// The activity meter and the Nova mark: one small planet, lit from the upper left, drawn by
// NovaIcons.drawPlanet (the same geometry as the menu bar icon and the app icon).

/// The activity meter: a small planet spinning on its axis. Live: mint, one turn in 3 s, faint
/// meridians sliding across its face. Rest: still, in dim ink. Alert: amber and still, pulsing
/// gently. Between states it eases, never snaps: the colour fades over about a second, the spin
/// slows to a stop or spins up, and the angle only ever advances. The clock runs only while it
/// moves, so a still planet costs nothing; Reduce Motion keeps it still.
struct PlanetMeter: View {
    var activity: Activity
    var size: CGFloat = 12
    var stroke: CGFloat? = nil
    /// The logo: mint at rest instead of dim ink.
    var brand = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var spin = PlanetSpin()
    /// Keeps the clock going for a moment after a change, so the planet can ease into the new state.
    @State private var settling = false
    @State private var seen: Activity?

    var body: some View {
        let moving = !reduceMotion && (activity != .rest || settling)
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !moving)) { tl in
            Canvas { ctx, sz in
                let look = spin.step(to: activity, at: tl.date.timeIntervalSinceReferenceDate, reduced: reduceMotion, brand: brand)
                let lw = stroke ?? max(0.7, size * 0.06)
                ctx.withCGContext { cg in
                    NovaIcons.drawPlanet(in: cg, rect: CGRect(origin: .zero, size: sz).insetBy(dx: 0.5, dy: 0.5), color: look.color, stroke: lw, spin: look.angle, alpha: look.alpha)
                }
            }
        }
        .frame(width: size, height: size)
        .task(id: activity) {
            // The first appearance takes its state at once; later changes ease in.
            guard seen != nil else { seen = activity; return }
            seen = activity
            settling = true
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            if !Task.isCancelled { settling = false }
        }
        .accessibilityHidden(true)
    }
}

/// The meter's state between frames: angle, spin speed, colour and pulse, integrated over time so
/// a change of rate never makes it jump.
final class PlanetSpin {
    private var angle: Double = .pi / 4
    private var speed: Double = 0
    private var rgb: SIMD3<Double>?
    private var alert: Double = 0
    private var last: Double = .nan

    static func rgb(_ a: Activity, brand: Bool = false) -> SIMD3<Double> {
        switch a {
        case .rest: return brand ? SIMD3(0x7F, 0xFF, 0xA0) / 255 : SIMD3(0x7A, 0x74, 0x68) / 255
        case .live: return SIMD3(0x7F, 0xFF, 0xA0) / 255
        case .alert: return SIMD3(0xE8, 0xB3, 0x41) / 255
        }
    }

    func step(to a: Activity, at t: Double, reduced: Bool, brand: Bool = false) -> (color: CGColor, angle: CGFloat, alpha: CGFloat) {
        let dt = last.isNaN ? 0 : min(max(t - last, 0), 0.2)
        last = t
        let target = Self.rgb(a, brand: brand)
        if let c = rgb, !reduced { rgb = c + (target - c) * (1 - exp(-dt / 0.3)) } else { rgb = target }
        // Spins up in about 0.7 s; finishing, it slows to a stop over about 1.4 s.
        let live = a == .live && !reduced
        speed += ((live ? 1 : 0) - speed) * (1 - exp(-dt / (live ? 0.22 : 0.45)))
        if !live && speed < 0.003 { speed = 0 }
        angle = (angle + dt * speed * 2 * .pi / 3).truncatingRemainder(dividingBy: 2 * .pi)
        let wantAlert: Double = a == .alert && !reduced ? 1 : 0
        alert = reduced ? wantAlert : alert + (wantAlert - alert) * (1 - exp(-dt / 0.25))
        let pulse = 0.5 - 0.5 * cos(2 * .pi * t / 1.4)
        let c = rgb ?? target
        return (CGColor(srgbRed: c.x, green: c.y, blue: c.z, alpha: 1), CGFloat(angle), CGFloat(1 - alert * 0.45 * (1 - pulse)))
    }
}

/// The Nova mark: the activity meter's planet, still, turned so its two meridians sit evenly.
/// It's the app icon, the menu bar icon and the brand row's logo. `hollow` is just its outline.
struct NovaMark: View {
    var size: CGFloat = 22
    var color: Color = .hnAccent
    var hollow = false

    var body: some View {
        Canvas { ctx, sz in
            let cg = NSColor(color).cgColor
            ctx.withCGContext { NovaIcons.drawPlanet(in: $0, rect: CGRect(origin: .zero, size: sz).insetBy(dx: 0.5, dy: 0.5), color: cg, hollow: hollow) }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
