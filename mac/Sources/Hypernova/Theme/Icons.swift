import AppKit

/// The Nova mark drawn with CoreGraphics: the menu bar template image and the app icon.
enum NovaIcons {
    /// The mark: the activity meter's small planet, lit from the upper left. Drawn y-down, filling
    /// `rect`. `spin` turns it on its axis (radians): at π/4, the rest position, its two meridians
    /// sit evenly (as the phone's launcher icon); the pattern repeats every quarter turn, since the
    /// four meridians are a quarter apart. `hollow` leaves just its outline; `alpha` fades it all.
    static func drawPlanet(in cg: CGContext, rect: CGRect, color: CGColor, stroke: CGFloat? = nil, hollow: Bool = false, spin: CGFloat = .pi / 4, alpha: CGFloat = 1) {
        let lw = stroke ?? rect.width / 2 * 0.11
        let r = rect.width / 2 - lw / 2
        let c = CGPoint(x: rect.midX, y: rect.midY)
        func col(_ a: CGFloat) -> CGColor { color.copy(alpha: a * alpha) ?? color }
        let disc = CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)
        cg.setLineWidth(lw)
        if !hollow {
            // The sphere: bright toward the light, into shadow on the far side.
            cg.saveGState()
            cg.addEllipse(in: disc); cg.clip()
            let lit = CGPoint(x: c.x - 0.7071 * r * 0.64, y: c.y - 0.7071 * r * 0.64)
            let space = color.colorSpace ?? CGColorSpaceCreateDeviceRGB()
            if let g = CGGradient(colorsSpace: space, colors: [col(0.80), col(0.24), col(0.05)] as CFArray, locations: [0, 0.5, 1]) {
                cg.drawRadialGradient(g, startCenter: lit, startRadius: 0, endCenter: lit, endRadius: r * 1.7, options: [.drawsAfterEndLocation])
            }
            cg.restoreGState()
            // Meridians: longitude λ is half an ellipse r·sin λ wide, seen while cos λ > 0, fainter
            // at the limb and on the side away from the light. Kept inside the sphere: a meridian
            // facing us is nearly a line, and its stroke would poke out past the poles.
            cg.saveGState()
            cg.addEllipse(in: disc); cg.clip()
            for k in 0..<4 {
                let lon = spin + CGFloat(k) * .pi / 2
                let depth = cos(lon)
                if depth <= 0.05 { continue }
                let half = r * sin(lon)
                let a = depth * min(0.9, max(0.1, 0.55 - 0.35 * half / r))
                // The near half of that ellipse, as an arc (a meridian facing us is a straight line).
                let squash = CGAffineTransform(translationX: c.x, y: c.y).scaledBy(x: max(abs(half), lw * 0.25) / r, y: 1)
                let arc = CGMutablePath()
                let start: CGFloat = half >= 0 ? -.pi / 2 : .pi / 2
                arc.addArc(center: .zero, radius: r, startAngle: start, endAngle: start + .pi, clockwise: false, transform: squash)
                cg.addPath(arc)
                cg.setStrokeColor(col(min(1, a * 1.1)))
                cg.strokePath()
            }
            cg.restoreGState()
        }
        cg.setStrokeColor(col(hollow ? 0.75 : 0.42))
        cg.strokeEllipse(in: disc)
    }

    /// The menu bar icon at rest: a template image, so macOS tints it for light and dark menu bars.
    /// Just its outline means the background service isn't answering.
    static func menuBar(running: Bool) -> NSImage {
        running ? MenuBarFrames.rest(badge: false) : MenuBarFrames.hollow
    }

    /// The app icon at `px` pixels: a warm near-black tile with a faint sky and the mint mark.
    static func appIconPNG(px: Int) -> Data? {
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        defer { NSGraphicsContext.restoreGraphicsState() }
        guard let ctx = NSGraphicsContext(bitmapImageRep: rep) else { return nil }
        NSGraphicsContext.current = ctx
        let cg = ctx.cgContext
        let W = CGFloat(px), k = W / 1024
        cg.clear(CGRect(x: 0, y: 0, width: W, height: W))

        // macOS icon grid: an 824 tile centred on 1024, corner radius ~185.
        let tile = CGRect(x: 100 * k, y: 100 * k, width: 824 * k, height: 824 * k)
        let shape = CGPath(roundedRect: tile, cornerWidth: 185 * k, cornerHeight: 185 * k, transform: nil)
        let rgb = CGColorSpaceCreateDeviceRGB()
        func c(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
            CGColor(colorSpace: rgb, components: [CGFloat((hex >> 16) & 0xFF) / 255, CGFloat((hex >> 8) & 0xFF) / 255, CGFloat(hex & 0xFF) / 255, a])!
        }

        // Drop shadow under the tile (as system icons have).
        cg.saveGState()
        cg.setShadow(offset: CGSize(width: 0, height: -10 * k), blur: 28 * k, color: c(0x000000, 0.45))
        cg.addPath(shape); cg.setFillColor(c(0x14110E)); cg.fillPath()
        cg.restoreGState()

        cg.saveGState()
        cg.addPath(shape); cg.clip()
        // Base: warm near-black, slightly lifted toward the centre.
        let base = CGGradient(colorsSpace: rgb, colors: [c(0x221F1B), c(0x14110E), c(0x0F0D0A)] as CFArray, locations: [0, 0.6, 1])!
        cg.drawRadialGradient(base, startCenter: CGPoint(x: 512 * k, y: 560 * k), startRadius: 0, endCenter: CGPoint(x: 512 * k, y: 512 * k), endRadius: 620 * k, options: [.drawsAfterEndLocation])
        // Two soft mint nebulae.
        for (x, y, r, a) in [(330.0, 690.0, 420.0, 0.16), (720.0, 330.0, 380.0, 0.11)] as [(CGFloat, CGFloat, CGFloat, CGFloat)] {
            let g = CGGradient(colorsSpace: rgb, colors: [c(0x7FFFA0, a), c(0x7FFFA0, a * 0.45), c(0x7FFFA0, 0)] as CFArray, locations: [0, 0.35, 1])!
            cg.drawRadialGradient(g, startCenter: CGPoint(x: x * k, y: y * k), startRadius: 0, endCenter: CGPoint(x: x * k, y: y * k), endRadius: r * k, options: [])
        }
        // A few faint stars (fixed, so every size matches).
        let stars: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [(230, 300, 5, 0.5), (300, 820, 4, 0.35), (790, 780, 6, 0.55), (840, 420, 3.5, 0.35), (420, 210, 3, 0.3), (650, 860, 3, 0.3), (180, 560, 3, 0.25), (870, 600, 3, 0.3)]
        if px >= 64 {
            for (x, y, r, a) in stars {
                cg.setFillColor(c(0x7FFFA0, a * 0.25)); cg.fillEllipse(in: CGRect(x: (x - r * 2.5) * k, y: (y - r * 2.5) * k, width: r * 5 * k, height: r * 5 * k))
                cg.setFillColor(c(0x7FFFA0, a)); cg.fillEllipse(in: CGRect(x: (x - r) * k, y: (y - r) * k, width: r * 2 * k, height: r * 2 * k))
            }
        }
        // The planet, centred, about 60% of the tile.
        let side = 500 * k
        let markRect = CGRect(x: (1024 * k - side) / 2, y: (1024 * k - side) / 2, width: side, height: side)
        // A faint glow around it.
        let glow = CGGradient(colorsSpace: rgb, colors: [c(0x7FFFA0, 0.16), c(0x7FFFA0, 0.06), c(0x7FFFA0, 0)] as CFArray, locations: [0, 0.6, 1])!
        cg.drawRadialGradient(glow, startCenter: CGPoint(x: 512 * k, y: 512 * k), startRadius: 200 * k, endCenter: CGPoint(x: 512 * k, y: 512 * k), endRadius: 360 * k, options: [])
        // Flip so the planet is lit from the upper left, as on the phone.
        cg.translateBy(x: 0, y: W); cg.scaleBy(x: 1, y: -1)
        drawPlanet(in: cg, rect: markRect, color: c(0x7FFFA0), stroke: max(side / 2 * 0.075, px <= 32 ? 1 : 0))
        cg.restoreGState()

        // Hairline ring and top highlight, like the glass cards.
        cg.saveGState()
        cg.addPath(CGPath(roundedRect: tile.insetBy(dx: 1.5 * k, dy: 1.5 * k), cornerWidth: 184 * k, cornerHeight: 184 * k, transform: nil))
        cg.setStrokeColor(c(0xF5F2EB, 0.10)); cg.setLineWidth(max(1, 3 * k)); cg.strokePath()
        cg.restoreGState()

        ctx.flushGraphics()
        return rep.representation(using: .png, properties: [:])
    }

    /// Writes a full .iconset (16…1024) for iconutil. Used by build.sh via `Hypernova --render-iconset <dir>`.
    static func writeIconset(to dir: URL) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for base in [16, 32, 128, 256, 512] {
            for scale in [1, 2] {
                let px = base * scale
                guard let png = appIconPNG(px: px) else { throw NSError(domain: "Hypernova.Icon", code: 1) }
                let name = scale == 1 ? "icon_\(base)x\(base).png" : "icon_\(base)x\(base)@2x.png"
                try png.write(to: dir.appendingPathComponent(name))
            }
        }
    }
}

/// The menu bar icon's frames, drawn once and kept: the planet at rest, turning (a quarter turn is
/// enough, since the meridians repeat every quarter), breathing (Reduce Motion), and outlined (the
/// service isn't answering). Each comes with and without the needs-you badge. They're template
/// images, so macOS tints them for light and dark menu bars and for the open state; that also
/// means one colour, so "needs you" is a shape: a dot cut into the planet's upper right.
enum MenuBarFrames {
    static let side: CGFloat = 18
    /// Frames per quarter turn. At one turn in 3 s that's a frame every 75 ms.
    static let spinCount = 10
    /// Opacity steps for the breathing that stands in for spinning under Reduce Motion.
    static let breathCount = 8

    static func rest(badge: Bool) -> NSImage { spin(0, badge: badge) }
    static func spin(_ i: Int, badge: Bool) -> NSImage { spinFrames[badge ? 1 : 0][((i % spinCount) + spinCount) % spinCount] }
    static func breath(_ i: Int, badge: Bool) -> NSImage { breathFrames[badge ? 1 : 0][((i % breathCount) + breathCount) % breathCount] }
    static let hollow: NSImage = make(hollow: true)

    private static let spinFrames: [[NSImage]] = [false, true].map { badge in
        (0..<spinCount).map { i in make(spin: .pi / 4 + CGFloat(i) / CGFloat(spinCount) * .pi / 2, badge: badge) }
    }
    /// Full strength down to 45% and back, on a cosine.
    private static let breathFrames: [[NSImage]] = [false, true].map { badge in
        (0..<breathCount).map { i in
            let k = 0.5 - 0.5 * cos(2 * .pi * CGFloat(i) / CGFloat(breathCount))
            return make(badge: badge, alpha: 1 - 0.55 * k)
        }
    }

    /// One frame, rendered at 1× and 2× so drawing it is a blit.
    static func make(spin: CGFloat = .pi / 4, badge: Bool = false, alpha: CGFloat = 1, hollow: Bool = false) -> NSImage {
        let img = NSImage(size: NSSize(width: side, height: side))
        for scale in [1, 2] {
            let px = Int(side) * scale
            guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
                  let ctx = NSGraphicsContext(bitmapImageRep: rep) else { continue }
            rep.size = img.size
            let cg = ctx.cgContext
            cg.scaleBy(x: CGFloat(scale), y: CGFloat(scale))
            // y-down, so the planet is lit from the upper left like everywhere else.
            cg.translateBy(x: 0, y: side); cg.scaleBy(x: 1, y: -1)
            draw(in: cg, spin: spin, badge: badge, alpha: alpha, hollow: hollow)
            ctx.flushGraphics()
            img.addRepresentation(rep)
        }
        img.isTemplate = true
        img.accessibilityDescription = "Hypernova"
        return img
    }

    /// One frame by name, for the renders: drawn fresh at any size.
    enum Frame { case hollow, spin(Int, badge: Bool), breath(Int, badge: Bool) }

    static func cgImage(_ f: Frame, px: Int) -> CGImage? {
        guard let cg = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                 bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let k = CGFloat(px) / side
        cg.translateBy(x: 0, y: CGFloat(px)); cg.scaleBy(x: k, y: -k)
        switch f {
        case .hollow: draw(in: cg, spin: .pi / 4, badge: false, alpha: 1, hollow: true)
        case .spin(let i, let badge): draw(in: cg, spin: .pi / 4 + CGFloat(i) / CGFloat(spinCount) * .pi / 2, badge: badge, alpha: 1, hollow: false)
        case .breath(let i, let badge):
            let p = 0.5 - 0.5 * cos(2 * .pi * CGFloat(i) / CGFloat(breathCount))
            draw(in: cg, spin: .pi / 4, badge: badge, alpha: 1 - 0.55 * p, hollow: false)
        }
        return cg.makeImage()
    }

    static func draw(in cg: CGContext, spin: CGFloat, badge: Bool, alpha: CGFloat, hollow: Bool) {
        let black = CGColor(gray: 0, alpha: 1)
        NovaIcons.drawPlanet(in: cg, rect: CGRect(x: 1.5, y: 1.5, width: side - 3, height: side - 3), color: black, stroke: 1.2, hollow: hollow, spin: spin, alpha: alpha)
        guard badge else { return }
        // A gap first, so the dot reads as its own shape on any menu bar, then the dot at full strength.
        let dot = CGPoint(x: side - 3.4, y: 3.4)
        cg.saveGState()
        cg.setBlendMode(.clear)
        cg.fillEllipse(in: CGRect(x: dot.x - 4.3, y: dot.y - 4.3, width: 8.6, height: 8.6))
        cg.restoreGState()
        cg.setFillColor(black)
        cg.fillEllipse(in: CGRect(x: dot.x - 2.8, y: dot.y - 2.8, width: 5.6, height: 5.6))
    }
}
