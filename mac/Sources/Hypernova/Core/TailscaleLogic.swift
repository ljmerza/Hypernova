import Foundation

/// What the Tailscale step should ask for next, from the daemon's TailscaleStatus.
enum TailscaleAction: Equatable {
    case install
    case openApp(loggedOut: Bool)
    case enableHTTPS
    case turnOnServe
    case ready(String)
    /// The built-in node (no Tailscale app) needs the user's sign-in: show this https link as a QR code.
    case signInBuiltIn(url: String)
    /// The built-in node is starting, or waiting for the sign-in to finish.
    case builtInWaiting(String)
    /// The built-in node serves the phone API at this URL ("pinned": with this Mac's own certificate, as the
    /// tailnet has no HTTPS certificates), signed in as `account`, named `node` on the tailnet.
    case readyBuiltIn(url: String, pinned: Bool, account: String?, node: String?)

    var dryRun: String {
        switch self {
        case .signInBuiltIn(let url): return "show the built-in Tailscale sign-in code (\(url))"
        case .builtInWaiting(let what): return "wait for the built-in Tailscale node (\(what))"
        case .readyBuiltIn(let url, _, _, _): return "nothing (the built-in node serves \(url))"
        case .install: return "open \(Endpoints.tailscaleDownload.absoluteString)"
        case .openApp(let loggedOut): return "open the Tailscale app (\(loggedOut ? "not signed in" : "not running"))"
        case .enableHTTPS: return "explain HTTPS certificates and open \(Endpoints.tailscaleDNS.absoluteString)"
        case .turnOnServe: return "POST /local/tailscale/serve (tailscale serve for port \(Endpoints.apiPort))"
        case .ready(let url): return "nothing (phone access is on at \(url))"
        }
    }
}

enum TailscaleStepLogic {
    /// With the daemon's built-in node in charge (tsnet.ts, `mode: "builtIn"`), the Tailscale app's
    /// states don't matter: the sign-in, the wait, or the address it serves. Otherwise the app's.
    static func action(_ ts: TailscaleStatus, tsnet: TsnetStatus?) -> TailscaleAction {
        if let t = tsnet, t.mode == "builtIn" {
            let h = t.helper
            if h?.state == "Running", let url = h?.listening ?? t.url { return .readyBuiltIn(url: url, pinned: h?.tls == "pinned", account: h?.account, node: h?.dnsName) }
            // A sign-in link is opened in the browser and shown as a code: https only, whatever the daemon says.
            if h?.state == "NeedsLogin", let url = h?.loginUrl, url.hasPrefix("https://") { return .signInBuiltIn(url: url) }
            if let e = h?.error, !e.isEmpty { return .builtInWaiting(e) }
            return .builtInWaiting(h?.state ?? "starting")
        }
        return appAction(ts)
    }

    /// The Tailscale app's own states. Private: everything asks with the built-in node too, so a Mac
    /// served by Hypernova's own node never reads as "phone access off" (the popover once did).
    private static func appAction(_ ts: TailscaleStatus) -> TailscaleAction {
        if ts.installed == false { return .install }
        if ts.running == false { return .openApp(loggedOut: false) }
        if ts.loggedIn == false { return .openApp(loggedOut: true) }
        if ts.httpsEnabled == false { return .enableHTTPS }
        if ts.serveOn == true, let u = ts.baseURL { return .ready(u) }
        return .turnOnServe
    }

    /// Phone access is on, through the Tailscale app or Hypernova's own node.
    static func isOn(_ a: TailscaleAction?) -> Bool {
        switch a {
        case .ready?, .readyBuiltIn?: return true
        default: return false
        }
    }

    /// The Tailscale app's key expiry date when it's within 14 days (a gentle warning), else nil.
    /// Only while the app is the one serving: the built-in node's key isn't the app's.
    static func expiringSoon(_ ts: TailscaleStatus, tsnet: TsnetStatus?, now: Date = Date()) -> Date? {
        if tsnet?.mode == "builtIn" { return nil }
        guard let d = ts.keyExpiry?.date else { return nil }
        return d.timeIntervalSince(now) < 14 * 86_400 ? d : nil
    }
}

extension String {
    /// "ab12 cd34 …": a checksum in groups of four, easier to compare by eye.
    var grouped4: String { stride(from: 0, to: count, by: 4).map { i in String(self[index(startIndex, offsetBy: i)..<index(startIndex, offsetBy: Swift.min(i + 4, count))]) }.joined(separator: " ") }
}

enum Format {
    /// "1h 12m", "4d 2h", "38s".
    static func duration(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds.rounded()))
        if s >= 86_400 { return "\(s / 86_400)d \((s % 86_400) / 3600)h" }
        if s >= 3600 { return "\(s / 3600)h \((s % 3600) / 60)m" }
        if s >= 60 { return "\(s / 60)m" }
        return "\(s)s"
    }

    static func ago(_ d: Date, now: Date = Date()) -> String {
        let s = now.timeIntervalSince(d)
        if s < 60 { return "just now" }
        return duration(s) + " ago"
    }

    /// "now", "4m", "2h", "3d": how long ago, in a row's corner.
    static func short(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        if s < 60 { return "now" }
        if s < 3600 { return "\(s / 60)m" }
        if s < 86_400 { return "\(s / 3600)h" }
        return "\(s / 86_400)d"
    }

    static func countdown(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    static func megabytes(_ bytes: Int64) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.minimumFractionDigits = 1; f.maximumFractionDigits = 1
        return (f.string(from: NSNumber(value: Double(bytes) / 1_000_000)) ?? "0") + " MB"
    }
}
