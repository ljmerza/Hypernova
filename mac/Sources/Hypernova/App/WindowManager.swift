import AppKit
import SwiftUI

/// Regular windows (Setup, Agents & providers) in the house chrome: hidden title bar, full-size content
/// view, warm near-black. While one is open the app shows in the Dock and the app switcher, so a
/// window you clicked away from is easy to find again; when the last closes it's menu-bar-only.
@MainActor
final class WindowManager: NSObject, NSWindowDelegate {
    static let shared = WindowManager()
    private var windows: [String: NSWindow] = [:]

    func show<V: View>(id: String, title: String, size: NSSize, resizable: Bool = false, @ViewBuilder content: () -> V) {
        if let w = windows[id] {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            w.makeKeyAndOrderFront(nil)
            return
        }
        var style: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .fullSizeContentView]
        if resizable { style.insert(.resizable) }
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: style, backing: .buffered, defer: false)
        w.title = title
        w.titleVisibility = .hidden
        w.titlebarAppearsTransparent = true
        w.isMovableByWindowBackground = true
        w.backgroundColor = NSColor(red: 0x14 / 255, green: 0x11 / 255, blue: 0x0E / 255, alpha: 1)
        w.appearance = NSAppearance(named: .darkAqua)
        w.isReleasedWhenClosed = false
        w.delegate = self
        w.identifier = NSUserInterfaceItemIdentifier(id)
        // The title bar floats over the content (full-size content view), so the root ignores the
        // safe area; otherwise the hosting view adds the title bar's height to the window.
        let host = NSHostingController(rootView: content().ignoresSafeArea())
        host.sizingOptions = []
        w.contentViewController = host
        // Full-size content: the frame *is* the content area (the title bar floats over it).
        w.setFrame(NSRect(origin: .zero, size: size), display: false)
        if resizable { w.minSize = size }
        w.center()
        windows[id] = w
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    func close(_ id: String) { windows[id]?.close() }
    func isOpen(_ id: String) -> Bool { windows[id] != nil }

    func windowWillClose(_ notification: Notification) {
        guard let w = notification.object as? NSWindow, let id = w.identifier?.rawValue else { return }
        windows[id] = nil
        if id == "providers" { providersModel = nil }
        NotificationCenter.default.post(name: .hypernovaWindowClosed, object: id)
        if windows.isEmpty {
            // Back to a menu bar app (after the window has gone, so focus returns sensibly).
            DispatchQueue.main.async { NSApp.setActivationPolicy(.accessory) }
        }
    }

    // MARK: The app's windows

    func showSetup(startAt step: SetupModel.Step? = nil, pairing: Bool = false) {
        let model = SetupModel.shared
        if let step { model.jump(to: step, pairing: pairing) }
        show(id: "setup", title: "Hypernova Setup", size: NSSize(width: 860, height: 600)) {
            SetupView().environmentObject(model)
        }
        model.windowOpened()
    }

    /// [editing]: open that provider's editor (Fix key from the popover).
    func showProviders(model: ProvidersModel? = nil, editing: String? = nil) {
        let m = model ?? providersModel ?? ProvidersModel()
        providersModel = m
        if let editing {
            if let p = m.providers.first(where: { $0.id == editing }) { m.edit(p) } else { m.editOnLoad = editing }
        }
        show(id: "providers", title: "Hypernova Agents & Providers", size: NSSize(width: 720, height: 760), resizable: true) {
            ProvidersView(model: m)
        }
        if editing != nil, windows["providers"] != nil { m.load() }
    }
    /// The Providers window's model, kept while it's open (so Fix key reaches the open window).
    private var providersModel: ProvidersModel?
}

extension Notification.Name {
    static let hypernovaWindowClosed = Notification.Name("hypernova.windowClosed")
}
