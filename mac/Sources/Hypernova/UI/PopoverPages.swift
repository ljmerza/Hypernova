import AppKit
import SwiftUI

// The popover's two sub-pages: pairing a device, and settings.

// MARK: - Pair

/// Pairing in the popover: a fresh single-use code each time, shown as a QR code until a device
/// takes it, then a small ignition. "Pair another" makes a new code for the next device.
struct PairPage: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var pairing: PopoverPairing
    var go: (MenuPopover.Page) -> Void
    var dismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            TimelineView(.periodic(from: .now, by: 1)) { tl in
                PageSeam(title: "Pair a phone", trail: trail(tl.date), activity: pairing.phase == .showing ? .live : .rest) { go(.main) }
            }
            content
        }
    }

    @ViewBuilder private var content: some View {
        switch pairing.phase {
        case .starting:
            VStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.hnBg800.opacity(0.6))
                    .frame(width: 184, height: 184)
                    .overlay(PlanetMeter(activity: .live, size: 16))
                Text("Making a pairing code…").font(HNFont.small).foregroundStyle(Color.hnMuted)
            }
            .frame(maxWidth: .infinity)
        case .showing:
            showing
        case .paired(let name):
            VStack(spacing: 8) {
                Ignition(size: 74)
                Text("Paired with \(name).").font(HNFont.heading).foregroundStyle(Color.hnInk).multilineTextAlignment(.center)
                Text("It can see your sessions and answer approvals now.").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).multilineTextAlignment(.center)
                HStack(spacing: 8) {
                    PopoverPill(title: "Pair another", kind: .outline) { pairing.begin() }
                    PopoverPill(title: "Done", kind: .primary) { go(.main) }
                }
                .padding(.top, 8)
            }
            .frame(maxWidth: .infinity)
        case .needsAccess:
            GlassCard {
                VStack(alignment: .leading, spacing: 8) {
                    Seam(label: "Phone access", trail: "off", activity: .alert)
                    Text("Turn on phone access first.").font(HNFont.heading).foregroundStyle(Color.hnInk)
                    Text("Phones reach this Mac through Tailscale, so the code needs this Mac's Tailscale address. Setup turns it on in a minute.")
                        .font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    PopoverPill(title: "Open setup", kind: .primary, fill: false) {
                        dismiss()
                        WindowManager.shared.showSetup(startAt: .tailscale)
                    }
                    .padding(.top, 4)
                }
            }
        case .failed(let message):
            VStack(alignment: .leading, spacing: 10) {
                ErrorText(text: message)
                PopoverPill(title: "Try again", kind: .outline, fill: false) { pairing.begin() }
            }
        }
    }

    private var showing: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Spacer()
                if let link = pairing.pair?.link { QRCodeView(text: link, side: 184) }
                Spacer()
            }
            HStack(spacing: 9) {
                PlanetMeter(activity: .live)
                Text("Waiting for your phone…").font(HNFont.small).foregroundStyle(Color.hnInk)
                Spacer(minLength: 6)
                Button("Copy link") { pairing.copyLink() }.buttonStyle(LinkStyle())
                    .help("For the Paste link field in the app")
            }
            Text("Point your phone's camera at the code; it opens Hypernova and pairs. Each code works once and lasts 10 minutes, and there's a new one every time you open this, so you can pair more than one device.")
                .font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
            if pairing.localOnly {
                // No tailnet address yet: the code carries the local network's route alone.
                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    GlyphView(glyph: .lan, size: 11, color: Color.hnAmber.opacity(0.85)).alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 3.5 }
                    Text("Works on this Wi‑Fi. Sign in to Tailscale to reach this Mac from anywhere.")
                        .font(HNFont.sans(12)).foregroundStyle(Color.hnAmber.opacity(0.9)).fixedSize(horizontal: false, vertical: true)
                }
            }
            Button("No app on it yet? Get Hypernova for Android") {
                dismiss()
                WindowManager.shared.showSetup(startAt: .phone)
            }
            .buttonStyle(LinkStyle(color: .hnMuted))
        }
    }

    private func trail(_ now: Date) -> String? {
        switch pairing.phase {
        case .showing:
            guard let exp = pairing.pair?.expiresAt?.date else { return "single use" }
            let left = exp.timeIntervalSince(now)
            return left > 20 ? "new code in \(Format.countdown(left - 20))" : "new code"
        case .paired: return "done"
        default: return nil
        }
    }
}

// MARK: - Settings

/// Everything the old menu had, gathered: the connection, phones, the background service,
/// providers, setup, open at login, uninstall.
struct SettingsPage: View {
    @EnvironmentObject private var model: AppModel
    var go: (MenuPopover.Page) -> Void
    var dismiss: () -> Void
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PageSeam(title: "Settings") { go(.main) }
            connection
            phones
            FilesSettings()
            AutomationsSettings()
            StayAwakeSettings(dismiss: dismiss)
            service
            general
        }
    }

    // MARK: Connection

    /// Phone access: through the Tailscale app, Hypernova's own node (the built-in one counts as
    /// on, owner's audit M1), or the local network alone; with the choice between the app and the
    /// built-in node where both are there (M2).
    private var connection: some View {
        VStack(alignment: .leading, spacing: 9) {
            let action = model.tailscaleAction
            let on = TailscaleStepLogic.isOn(action)
            Seam(label: "Phone access", trail: !model.daemonUp ? "service off" : action == nil ? "checking" : on ? "on" : model.localRoute ? "this Wi‑Fi" : "off",
                 activity: model.daemonUp && action != nil && !on && !model.localRoute ? .alert : .rest)
            if !model.daemonUp {
                Text("The background service isn't running, so phones can't reach this Mac.").font(HNFont.sans(12)).foregroundStyle(Color.hnMuted)
            } else if let ts = model.status?.tailscale, let action {
                switch action {
                case .ready(let url):
                    address(url)
                    HStack(spacing: 6) {
                        StatusChip(text: "Tailscale app", color: .hnMuted).fixedSize()
                        if let n = ts.dnsName { TagChip(text: n.split(separator: ".").first.map(String.init) ?? n, fixed: false).help("This Mac on the tailnet: \(n)") }
                    }
                    Text("Only devices signed in to your Tailscale account can open it.").font(HNFont.sans(11)).foregroundStyle(Color.hnDim)
                case .readyBuiltIn(let url, _, let account, let node):
                    address(url)
                    HStack(spacing: 6) {
                        StatusChip(text: "Built in", color: .hnMuted).fixedSize()
                        if let account { TagChip(text: account, fixed: false).help("Signed in to Tailscale as \(account)" + (node.map { ". This Mac on the tailnet: \($0)" } ?? "")) }
                        Spacer(minLength: 4)
                        Button("Sign out") { model.signOutBuiltIn() }.buttonStyle(LinkStyle(color: .hnMuted)).fixedSize()
                            .help("Sign Hypernova's Tailscale node out (another account, or a fresh start). Setup shows the new sign-in.")
                    }
                    Text("Hypernova's own Tailscale node. Only devices on your tailnet can open it.").font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                default:
                    Text(explain(action)).font(HNFont.sans(12)).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    if model.localRoute {
                        Text("Phones on this Wi‑Fi still pair and connect.").font(HNFont.sans(11)).foregroundStyle(Color.hnDim)
                    }
                    PopoverPill(title: "Open setup", kind: .outline, fill: false) {
                        dismiss()
                        WindowManager.shared.showSetup(startAt: .tailscale)
                    }
                }
                choice(ts)
                if let exp = model.keyExpiry {
                    let left = exp.timeIntervalSinceNow
                    AttentionCard(notice: Notice(id: "key", tone: .amber,
                                                 title: left <= 0 ? "This Mac’s Tailscale key has expired" : "Tailscale key expires in \(Format.duration(left).components(separatedBy: " ").first ?? "")",
                                                 line: "Turn key expiry off for this Mac.", action: "Fix",
                                                 help: "In the admin console, open this Mac's ⋯ menu and choose Disable key expiry.") {
                        NSWorkspace.shared.open(Endpoints.tailscaleMachines)
                        dismiss()
                    }, onDismiss: nil)
                }
            } else {
                Text("Asking Tailscale…").font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
            }
        }
    }

    private func address(_ url: String) -> some View {
        HStack(spacing: 10) {
            Text(url).font(HNFont.mono(11)).foregroundStyle(Color.hnAccentBright).lineLimit(1).truncationMode(.middle)
            Spacer(minLength: 6)
            Button(copied ? "Copied" : "Copy") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(url, forType: .string)
                copied = true
            }
            .buttonStyle(LinkStyle())
        }
    }

    /// The Tailscale app or Hypernova's own node, where there's a choice (the daemon's
    /// `builtInTailscale` setting): the app installed while the node serves, or the node turned off
    /// while it could run.
    @ViewBuilder private func choice(_ ts: TailscaleStatus) -> some View {
        let t = model.status?.tsnet
        if t?.mode == "builtIn", ts.installed == true {
            Button("Use the Tailscale app instead") {
                model.setBuiltInTailscale(false) {
                    dismiss()
                    WindowManager.shared.showSetup(startAt: .tailscale)
                }
            }
            .buttonStyle(LinkStyle(color: .hnMuted))
            .help("Hand phone access to the Tailscale app on this Mac; Setup then turns its phone access on. Paired phones follow by themselves.")
        } else if t?.mode != "system", t?.enabled == false, t?.available == true {
            Button("Use Hypernova's own Tailscale") { model.setBuiltInTailscale(true) }
                .buttonStyle(LinkStyle(color: .hnMuted))
                .help("Hypernova runs its own Tailscale node: no Tailscale app needed. You sign it in once, in Setup.")
        }
    }

    private func explain(_ a: TailscaleAction) -> String {
        switch a {
        case .install: return "Tailscale isn't on this Mac yet. Phones reach Hypernova through it."
        case .openApp(let out): return out ? "Tailscale is signed out. Sign in with the same account as your phone." : "Tailscale isn't running."
        case .enableHTTPS: return "Your tailnet needs HTTPS certificates turned on, so phones get a trusted connection."
        case .turnOnServe: return "Tailscale is ready; phone access just needs turning on."
        case .signInBuiltIn: return "Hypernova's built-in Tailscale needs your sign-in: open Setup for the code."
        case .builtInWaiting: return "Hypernova's built-in Tailscale is starting."
        case .ready, .readyBuiltIn: return ""
        }
    }

    // MARK: Phones

    /// Trusted devices: each paired phone and pair of glasses, when it was last seen, and Forget
    /// (it can't reach this Mac until it pairs again). Forget asks first, in a glass card.
    private var phones: some View {
        let devices = model.devices
        let phones = devices.filter { !$0.isLens }.count
        let glasses = devices.count - phones
        return VStack(alignment: .leading, spacing: 8) {
            Seam(label: glasses > 0 ? "Phones and glasses" : "Phones", trail: devices.isEmpty ? "none yet" : "\(devices.count) paired")
            ForEach(devices) { d in
                DeviceRow(device: d, via: d.via.flatMap { v in devices.first { $0.id == v }?.displayName }) { askForget(d) }
            }
            Button(phones == 0 ? "Pair your phone" : "Pair another phone") { go(.pair) }
                .buttonStyle(LinkStyle())
                .disabled(!model.daemonUp)
        }
        .task { await model.loadDevices() }
    }

    private func askForget(_ d: Device) {
        let glasses = d.isLens
        model.dialog = GlassDialog(
            eyebrow: glasses ? "Forget glasses" : "Forget a phone",
            title: "Forget \(d.displayName)?",
            line: glasses ? "They can't reach this Mac until they're connected again." : "It can't reach this Mac until it pairs again.",
            more: glasses ? nil : "Its token stops working at once, on Tailscale and on the local network. Glasses that come through it stop too.",
            accent: .hnDanger, secondary: "Keep", primary: "Forget",
            confirm: { _ in model.closeDialog(); model.forget(d) },
            cancel: { model.closeDialog() })
    }

    // MARK: Service

    private var service: some View {
        VStack(alignment: .leading, spacing: 9) {
            Seam(label: "Background service", trail: model.daemonUp ? (model.daemonVersion.map { "running, version \($0)" } ?? "running") : "not running", activity: model.daemonUp ? .rest : .alert)
            HStack(spacing: 8) {
                PopoverPill(title: model.restarting ? "Restarting…" : "Restart", kind: .outline, busy: model.restarting, fill: false) { model.restartService() }
                    .disabled(!model.serviceInstalled)
                    .help("Shared terminals keep running through a restart")
                PopoverPill(title: "Show log", kind: .ghost, fill: false) {
                    NSWorkspace.shared.open([Paths.daemonLog], withApplicationAt: URL(fileURLWithPath: "/System/Applications/Utilities/Console.app"), configuration: NSWorkspace.OpenConfiguration())
                    dismiss()
                }
                if model.serviceUpdate {
                    PopoverPill(title: model.updatingService ? "Updating…" : "Update", kind: .primary, busy: model.updatingService, fill: false) { model.updateService() }
                        .help("This copy of Hypernova carries a newer service")
                }
            }
        }
    }

    // MARK: General

    private var general: some View {
        VStack(spacing: 0) {
            row("Agents & providers…", "square.stack.3d.up") { dismiss(); WindowManager.shared.showProviders() }
                .disabled(!model.daemonUp)
            row("Open setup…", "sparkles") { dismiss(); WindowManager.shared.showSetup() }
            row("Lens guide…", "eyeglasses") { dismiss(); LensGuideModel.shared.show() }
                .disabled(!model.daemonUp)
            row("Voice…", "waveform") { dismiss(); WindowManager.shared.showSetup(startAt: .voice) }
                .disabled(!model.daemonUp)
            Button { model.setLoginItem(!model.loginItemEnabled) } label: {
                HStack(spacing: 10) {
                    Image(systemName: "power").font(.system(size: 11, weight: .medium)).frame(width: 16)
                    Text("Open at login")
                    Spacer()
                    if model.loginItemEnabled { CheckIcon(size: 11) }
                }
            }
            .buttonStyle(MenuRowStyle())
            // The login item belongs to the owner's copy.
            .disabled(Paths.testCopy)
            .help(Paths.testCopy ? "A test copy never opens at login" : "")
            row("Uninstall…", "trash", danger: true) { model.askUninstall() }
        }
        .padding(.horizontal, -10)
        .overlay(alignment: .top) { Rectangle().fill(Color.hnLine.opacity(0.7)).frame(height: 1).padding(.horizontal, -16).offset(y: -9) }
    }

    private func row(_ title: String, _ symbol: String, danger: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol).font(.system(size: 11, weight: .medium)).frame(width: 16)
                Text(title)
                Spacer()
            }
        }
        .buttonStyle(MenuRowStyle(danger: danger))
    }
}

/// A paired phone or pair of glasses: its glyph, its name (glasses through a phone say which),
/// when it was last seen (green while in the last 3 minutes), and Forget on hover.
private struct DeviceRow: View {
    var device: Device
    /// The phone glasses come through.
    var via: String?
    var forget: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 8) {
            GlyphView(glyph: device.isLens ? .glasses : .phone, size: device.isLens ? 14 : 11, color: seenRecently ? Color.hnAccent.opacity(0.85) : Color.hnMuted)
                .frame(width: 16)
            Text(device.displayName).font(HNFont.small).foregroundStyle(Color.hnInk).lineLimit(1)
            if let via {
                Text("through \(via)").font(HNFont.sans(11)).foregroundStyle(Color.hnDim).lineLimit(1)
            } else if let m = device.model, !m.isEmpty, m != device.displayName, !device.isLens {
                Text(m).font(HNFont.mono(10)).foregroundStyle(Color.hnGhost).lineLimit(1)
            }
            Spacer(minLength: 6)
            if hover {
                Button("Forget", action: forget).buttonStyle(LinkStyle(color: .hnDanger))
            } else if let seen = device.lastSeenAt?.date {
                if seenRecently {
                    Text("online").font(HNFont.mono(10)).foregroundStyle(Color.hnAccentDim)
                } else {
                    GlyphFigure(glyph: .clock, text: Format.short(Date().timeIntervalSince(seen)))
                }
            }
        }
        .frame(height: 22)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .help(help)
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Forget", forget)
    }

    private var seenRecently: Bool {
        guard let s = device.lastSeenAt?.date else { return false }
        return Date().timeIntervalSince(s) < 180
    }

    private var help: String {
        var parts: [String] = []
        if let c = device.createdAt?.date { parts.append("Paired \(Self.day.string(from: c)).") }
        if let s = device.lastSeenAt?.date { parts.append(seenRecently ? "Connected now." : "Last seen \(Format.ago(s)).") }
        if let v = device.appVersion, !v.isEmpty { parts.append(device.isLens ? "Lens \(v)." : "Hypernova \(v).") }
        return parts.joined(separator: " ")
    }
    private static let day: DateFormatter = { let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("d MMM"); return f }()
}
