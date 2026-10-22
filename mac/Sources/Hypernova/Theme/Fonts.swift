import AppKit
import CoreText
import SwiftUI

/// Manrope and JetBrains Mono (variable TTFs, OFL) ship in Contents/Resources/Fonts and are
/// registered for this process at launch. Fonts are built through CoreText from the variable
/// fonts' named instances, with Manrope's `ss01` and tabular figures switched on.
enum Fonts {
    enum Family { case manrope, mono }

    private(set) static var registered = false
    private static var cache: [String: Font] = [:]
    private static let lock = NSLock()

    /// Registers every .ttf in Contents/Resources/Fonts. Safe to call more than once.
    @discardableResult
    static func register() -> Bool {
        if registered { return true }
        var dirs: [URL] = []
        if let res = Bundle.main.resourceURL { dirs.append(res.appendingPathComponent("Fonts")) }
        // `swift run` during development: fall back to the repo's copies.
        let exe = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        var up = exe.deletingLastPathComponent()
        for _ in 0..<6 {
            let candidate = up.appendingPathComponent("android/app/src/main/res/font")
            if FileManager.default.fileExists(atPath: candidate.path) { dirs.append(candidate); break }
            up = up.deletingLastPathComponent()
        }
        var ok = 0
        for dir in dirs {
            guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { continue }
            for f in files where f.pathExtension.lowercased() == "ttf" {
                var err: Unmanaged<CFError>?
                if CTFontManagerRegisterFontsForURL(f as CFURL, .process, &err) { ok += 1 }
                else if let e = err?.takeRetainedValue(), CFErrorGetCode(e) == CTFontManagerError.alreadyRegistered.rawValue { ok += 1 }
            }
            if ok > 0 { break }
        }
        registered = ok >= 2
        return registered
    }

    private static func postScriptName(_ family: Family, _ weight: Font.Weight) -> String {
        switch family {
        case .manrope:
            switch weight {
            case .ultraLight, .thin: return "Manrope-ExtraLight"
            case .light: return "Manrope-Light"
            case .regular: return "Manrope-Regular"
            case .semibold: return "Manrope-SemiBold"
            case .bold: return "Manrope-Bold"
            case .heavy, .black: return "Manrope-ExtraBold"
            default: return "Manrope-Medium"
            }
        case .mono:
            switch weight {
            case .ultraLight, .thin: return "JetBrainsMonoRoman-ExtraLight"
            case .light: return "JetBrainsMonoRoman-Light"
            case .medium: return "JetBrainsMonoRoman-Medium"
            case .semibold, .bold: return "JetBrainsMonoRoman-Bold"
            default: return "JetBrainsMono-Regular"
            }
        }
    }

    static func ctFont(_ family: Family, _ size: CGFloat, _ weight: Font.Weight) -> CTFont {
        let name = postScriptName(family, weight)
        var attrs: [CFString: Any] = [kCTFontNameAttribute: name]
        if family == .manrope {
            attrs[kCTFontFeatureSettingsAttribute] = [
                [kCTFontOpenTypeFeatureTag: "ss01", kCTFontOpenTypeFeatureValue: 1],
                [kCTFontOpenTypeFeatureTag: "tnum", kCTFontOpenTypeFeatureValue: 1],
            ]
        }
        let desc = CTFontDescriptorCreateWithAttributes(attrs as CFDictionary)
        return CTFontCreateWithFontDescriptor(desc, size, nil)
    }

    static func font(_ family: Family, _ size: CGFloat, _ weight: Font.Weight) -> Font {
        let key = "\(family)-\(size)-\(weight)"
        lock.lock(); defer { lock.unlock() }
        if let f = cache[key] { return f }
        let f: Font
        if registered {
            f = Font(ctFont(family, size, weight))
        } else {
            f = family == .mono ? .system(size: size, weight: weight, design: .monospaced) : .system(size: size, weight: weight)
        }
        cache[key] = f
        return f
    }
}
