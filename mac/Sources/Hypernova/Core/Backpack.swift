import AppKit
import Foundation

/// Backpack mode's one-time setup (docs/PROTOCOL.md, "Stay awake"). A MacBook on battery sleeps
/// when its lid closes whatever apps ask; only macOS's SleepDisabled (`pmset -a disablesleep 1`,
/// root only) keeps it awake. So the daemon needs to run exactly that, and its opposite, as root,
/// and nothing else.
///
/// The smallest privileged piece that can do it is a sudoers rule, not a helper: a root
/// LaunchDaemon would be a program running as root all the time, with an interface to defend and
/// a binary to protect, while a rule is a line of configuration sudo enforces. It names this user
/// and the two exact commands, with their arguments (sudo matches them whole: no other pmset
/// setting, no other argument), and no password, so the daemon can run them with `sudo -n` (which
/// never prompts). Amphetamine's Enhancer installs the same kind of rule, for every admin.
///
/// Setup asks for the admin password once, through macOS's own prompt (AppleScript's "with
/// administrator privileges"). As root it writes the rule to a temporary name sudo ignores (it
/// skips names with a dot), checks it with `visudo -cf`, makes it root's and read-only (0440), and
/// only then moves it into place, so sudo never sees a half-written or broken rule. Removing it is
/// the same prompt and one `rm`, after Backpack has put back the SleepDisabled it found.
enum BackpackSetup {
    private static let env = ProcessInfo.processInfo.environment

    /// Where the rule lives. A test points it elsewhere with HYPERNOVA_SUDOERS, as the daemon does,
    /// and then the scripts run without the admin prompt (they can, outside /etc).
    static var rulePath: String { env["HYPERNOVA_SUDOERS"].flatMap { $0.isEmpty ? nil : $0 } ?? "/private/etc/sudoers.d/hypernova" }
    static var testRule: Bool { rulePath != "/private/etc/sudoers.d/hypernova" }
    /// The rule is in place (it's root's and unreadable, but it can be seen).
    static var installed: Bool { FileManager.default.fileExists(atPath: rulePath) }

    /// A test copy leaves the real rule to the owner's install (it's the Mac's, not the daemon's).
    static var blocked: String? {
        if testRule {
            return rulePath.hasPrefix("/private/etc/") || rulePath.hasPrefix("/etc/") ? "HYPERNOVA_SUDOERS points into /etc: a test never touches sudo's own folder." : nil
        }
        return Paths.testCopy ? "This is a test copy (a HYPERNOVA_ variable is set), so it leaves Backpack mode's permission to your real Hypernova. Set HYPERNOVA_SUDOERS to a scratch path to test it." : nil
    }

    /// A short user name sudo can take as it is (macOS's are letters, digits, `_`, `.` and `-`).
    static func validUser(_ u: String) -> Bool {
        u.range(of: #"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$"#, options: .regularExpression) != nil
    }

    /// The rule: this user, as root, the two exact commands, no password.
    static func rule(user: String) -> [String] {
        [
            "# Hypernova's Backpack mode: lets \(user) turn macOS sleep off and back on (pmset disablesleep), nothing else.",
            "# Remove it in Hypernova (Settings, Stay awake), or with: sudo rm /etc/sudoers.d/hypernova",
            "\(user) ALL=(root) NOPASSWD: /usr/bin/pmset -a disablesleep 0, /usr/bin/pmset -a disablesleep 1",
        ]
    }

    private static func q(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: #"'\''"#) + "'" }

    /// The setup as a shell script for root. [asRoot] false (a test): the file stays the user's.
    static func installScript(user: String, target: String, asRoot: Bool = true) -> String {
        let dir = (target as NSString).deletingLastPathComponent
        var lines = [
            "set -e",
            "umask 077",
            "/bin/mkdir -p \(q(dir))",
            // A dot in the name: sudo's includedir skips it while it's being written.
            "tmp=$(/usr/bin/mktemp \(q(dir + "/.hypernova.XXXXXX")))",
            "trap '/bin/rm -f \"$tmp\"' EXIT",
            "/usr/bin/printf '%s\\n' \(rule(user: user).map(q).joined(separator: " ")) > \"$tmp\"",
            "/usr/sbin/visudo -cf \"$tmp\" >/dev/null",
        ]
        if asRoot { lines.append("/usr/sbin/chown root:wheel \"$tmp\"") }
        lines += [
            "/bin/chmod 0440 \"$tmp\"",
            "/bin/mv -f \"$tmp\" \(q(target))",
            "trap - EXIT",
        ]
        return lines.joined(separator: "\n")
    }

    /// Removing it, after putting back SleepDisabled [restore] when Backpack had changed it and the
    /// daemon couldn't (it isn't running). [pmset]: the real one as root; a stand-in in a test.
    static func uninstallScript(target: String, restore: Int?, pmset: String? = "/usr/bin/pmset") -> String {
        var lines = ["set -e"]
        if let r = restore, r == 0 || r == 1, let pmset { lines.append("\(q(pmset)) -a disablesleep \(r)") }
        lines.append("/bin/rm -f \(q(target))")
        return lines.joined(separator: "\n")
    }

    static let installPrompt = "Hypernova needs your password once to set up Backpack mode: a rule that lets it turn sleep off and back on, and nothing else."
    static let removePrompt = "Hypernova needs your password to remove Backpack mode's permission."

    enum Failure: LocalizedError {
        case cancelled
        case failed(String)
        var errorDescription: String? {
            switch self {
            case .cancelled: return "Cancelled."
            case .failed(let m): return m
            }
        }
    }

    /// Install the rule (asks for the password once). Nothing to do when it's there already.
    /// [inApp]: the app's own prompt; else (`--run-step`) osascript's.
    static func install(inApp: Bool) async throws {
        if let why = blocked { throw Failure.failed(why) }
        if installed { return }
        let user = NSUserName()
        guard validUser(user) else { throw Failure.failed("Backpack mode can't name your account (\(user)) in sudo's rules.") }
        try await run(installScript(user: user, target: rulePath, asRoot: !testRule), prompt: installPrompt, inApp: inApp)
        guard installed else { throw Failure.failed("The setup finished, but the permission isn't there. Try again.") }
    }

    /// Remove the rule: Backpack first ends through the daemon (which puts SleepDisabled back while
    /// it still may); if it couldn't, the removal puts back what awake.json says it found.
    static func uninstall(inApp: Bool) async throws {
        if let why = blocked { throw Failure.failed(why) }
        guard installed else { return }
        if await DaemonClient.shared.health() != nil, let a = try? await DaemonClient.shared.awake(), a.mode == .backpack {
            _ = try? await DaemonClient.shared.setAwake(mode: .working)
            for _ in 0..<20 where pendingRestore() != nil { try? await Task.sleep(nanoseconds: 200_000_000) }
        }
        // A test never runs the real pmset: only a stand-in it names (HYPERNOVA_PMSET).
        let pmset = testRule ? env["HYPERNOVA_PMSET"].flatMap { $0.isEmpty ? nil : $0 } : "/usr/bin/pmset"
        let restore = pendingRestore()
        try await run(uninstallScript(target: rulePath, restore: restore, pmset: pmset), prompt: removePrompt, inApp: inApp)
        if installed { throw Failure.failed("The permission is still there. Try again.") }
        // The daemon wasn't there to do it: the record of what Backpack switched is settled now,
        // so a daemon that starts later doesn't try to switch it back again.
        if restore != nil { forgetRestore() }
    }

    /// Clears Backpack's record in the daemon's awake.json (and Backpack itself), keeping the rest.
    private static func forgetRestore() {
        let url = Paths.support.appendingPathComponent("awake.json")
        guard let data = try? Data(contentsOf: url), var o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        o["backpack"] = NSNull()
        if o["mode"] as? String == "backpack" { o["mode"] = "working"; o["until"] = NSNull() }
        guard let out = try? JSONSerialization.data(withJSONObject: o, options: [.prettyPrinted, .sortedKeys]) else { return }
        try? out.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    /// The SleepDisabled value Backpack changed and hasn't put back yet (from the daemon's awake.json).
    static func pendingRestore() -> Int? {
        struct Saved: Decodable { struct BP: Decodable { var prev: Int; var applied: Bool }; var backpack: BP? }
        guard let data = try? Data(contentsOf: Paths.support.appendingPathComponent("awake.json")),
              let s = try? JSONDecoder().decode(Saved.self, from: data), let bp = s.backpack, bp.applied else { return nil }
        return bp.prev
    }

    /// The AppleScript that runs [script] as root behind macOS's password prompt.
    static func adminAppleScript(_ script: String, prompt: String) -> String {
        "do shell script \"\(appleScriptString(script))\" with prompt \"\(appleScriptString(prompt))\" with administrator privileges"
    }

    /// `--backpack-scripts`: what the setup and its removal would run as root on this Mac, and the
    /// AppleScript around the setup, printed and never run.
    static func printScripts() -> Int32 {
        let user = NSUserName()
        print("# install (as root), for \(user):\n\(installScript(user: user, target: "/private/etc/sudoers.d/hypernova"))\n")
        print("# remove (as root), Backpack having switched SleepDisabled from 0:\n\(uninstallScript(target: "/private/etc/sudoers.d/hypernova", restore: 0))\n")
        print("# AppleScript:\n\(adminAppleScript(installScript(user: user, target: "/private/etc/sudoers.d/hypernova"), prompt: installPrompt))")
        return validUser(user) ? 0 : 1
    }

    /// [script] as root through macOS's admin prompt, or as it is for a test rule (outside /etc).
    static func run(_ script: String, prompt: String, inApp: Bool) async throws {
        if testRule {
            let r = await Shell.runAsync("/bin/sh", ["-c", script], timeout: 20)
            if !r.ok { throw Failure.failed(r.combined.isEmpty ? "The setup script failed (\(r.status))." : r.combined) }
            return
        }
        let source = adminAppleScript(script, prompt: prompt)
        if inApp {
            // In the app: its own prompt ("Hypernova wants to make changes"), which needs the main thread.
            try await MainActor.run { try runInProcess(source) }
        } else {
            // From `--run-step`, whose main thread waits: osascript asks instead.
            let r = await Shell.runAsync("/usr/bin/osascript", ["-e", source], timeout: 300)
            if r.ok { return }
            if r.combined.contains("-128") { throw Failure.cancelled }
            throw Failure.failed(r.combined.isEmpty ? "The setup didn't finish." : r.combined)
        }
    }

    @MainActor
    private static func runInProcess(_ source: String) throws {
        var error: NSDictionary?
        guard let s = NSAppleScript(source: source) else { throw Failure.failed("The setup couldn't start.") }
        s.executeAndReturnError(&error)
        guard let error else { return }
        if (error[NSAppleScript.errorNumber] as? Int) == -128 { throw Failure.cancelled }
        throw Failure.failed((error[NSAppleScript.errorMessage] as? String) ?? "The setup didn't finish.")
    }

    /// [s] inside an AppleScript string literal.
    static func appleScriptString(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"").replacingOccurrences(of: "\n", with: "\\n")
    }
}
