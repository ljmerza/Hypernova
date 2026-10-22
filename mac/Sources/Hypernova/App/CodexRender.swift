import AppKit
import SwiftUI

/// Renders of Setup's Codex step on sample data (called by `--render-popover`): a Setup model of its
/// own, given a status as the daemon would send it, never the daemon, and nothing written anywhere
/// but the PNGs.
@MainActor
struct CodexSetupRender {
    let dir: URL

    /// [states]: each render's name and the status's `codex` (JSON, "null" for not installed).
    func run(_ states: [(name: String, codex: String)]) -> Int {
        var failed = 0
        for st in states {
            let m = SetupModel()
            m.health = Health(ok: true, protocol: 1, name: "MacBook Air")
            m.status = decode(#"{"daemonVersion":"0.4.0","apiPort":47321,"hooks":{"installed":true},"statusLine":{"installed":true,"supported":true},"shell":{"installed":true,"supported":true},"devices":[],"codex":\#(st.codex)}"#)
            m.claude = ClaudeDetection(path: "/Users/you/.local/bin/claude", version: "2.1.296", signedIn: true)
            m.claudePhase = .ready
            m.servicePhase = .running
            m.current = .codex
            if !write(st.name, SetupView().environmentObject(m), CGSize(width: 860, height: 600)) { failed += 1 }
        }
        return failed
    }

    /// Setup on a Mac with Codex and no Claude Code: [states]: each render's name, the step shown,
    /// whether the background service answers, and the status's `codex` (before the service, Setup's own
    /// find stands in). Nothing is remembered: "Use Codex only" isn't pressed, so Codex on is what skips
    /// Claude Code's steps here.
    func codexOnly(_ states: [(name: String, step: SetupModel.Step, up: Bool, codex: String)]) -> Int {
        var failed = 0
        for st in states {
            let m = SetupModel()
            m.claude = ClaudeDetection()
            m.claudePhase = .missing
            m.codexFound = CodexDetector.Found(path: "/opt/homebrew/bin/codex", version: "0.163.0")
            if st.up {
                m.health = Health(ok: true, protocol: 1, name: "MacBook Air")
                m.status = decode(#"{"daemonVersion":"0.4.0","apiPort":47321,"claude":{"path":null,"version":null},"hooks":{"installed":false},"statusLine":{"installed":false,"supported":true},"shell":{"installed":true,"supported":true},"devices":[],"codex":\#(st.codex)}"#)
                m.servicePhase = .running
            } else {
                m.servicePhase = .notInstalled
            }
            m.current = st.step
            if !write(st.name, SetupView().environmentObject(m), CGSize(width: 860, height: 600)) { failed += 1 }
        }
        return failed
    }

    private func write<V: View>(_ name: String, _ view: V, _ size: CGSize) -> Bool {
        let r = ImageRenderer(content: view.environment(\.colorScheme, .dark).frame(width: size.width, height: size.height))
        r.scale = 1.5
        guard let cg = r.cgImage, let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) else { print("render failed: \(name)"); return false }
        do { try png.write(to: dir.appendingPathComponent(name + ".png")) } catch { print("write failed: \(name)"); return false }
        print("wrote \(dir.appendingPathComponent(name + ".png").path)")
        return true
    }

    private func decode<T: Decodable>(_ json: String) -> T {
        do { return try JSONDecoder().decode(T.self, from: Data(json.utf8)) } catch { fatalError("codex sample didn't decode as \(T.self): \(error)\n\(json)") }
    }
}
