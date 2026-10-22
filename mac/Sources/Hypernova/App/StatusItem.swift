import AppKit
import Combine
import SwiftUI

/// The menu bar item and its popover, in AppKit rather than SwiftUI's MenuBarExtra: the icon turns
/// while a session works, and swapping pre-drawn frames on the status button is the cheapest way
/// to do that (no view tree re-renders, and nothing runs while it's still).
@MainActor
final class StatusItemController: NSObject {
    static let shared = StatusItemController()
    static let width: CGFloat = 360

    private var item: NSStatusItem?
    private let icon = MenuBarIconAnimator()
    private var panel: MenuPanel?
    private var scroll: NSScrollView?
    private var host: PopoverHostingView?
    private var monitors: [Any] = []
    private var observers: [NSObjectProtocol] = []
    private var bag: Set<AnyCancellable> = []
    private var maxHeight: CGFloat = 720
    /// The content's own height, as it reports it (NSHostingView doesn't say when it changes).
    private var contentHeight: CGFloat?
    private var syncQueued = false
    // Nobody sees the menu bar while the displays sleep, the screen is locked or another user has
    // the Mac: the icon doesn't turn then.
    private var displaysAsleep = false
    private var screenLocked = false
    private var sessionInactive = false
    private var seen: Bool { !displaysAsleep && !screenLocked && !sessionInactive }

    var isOpen: Bool { panel?.isVisible == true }

    // For `--popover-selftest`.
    var debugButton: NSStatusBarButton? { item?.button }
    var debugPanel: NSPanel? { panel }
    var debugAnimating: Bool { icon.isAnimating }
    var debugFittingHeight: CGFloat { host?.fittingSize.height ?? 0 }
    var debugMaxHeight: CGFloat { maxHeight }
    var debugAnchorTop: CGFloat? { panel?.anchorTop?.y }
    /// Stands in for com.apple.screenIsLocked / screenIsUnlocked, which can't be posted without
    /// telling every other app on the Mac that the screen locked.
    func debugScreen(locked: Bool) {
        screenLocked = locked
        syncIcon()
    }

    func install() {
        guard item == nil else { return }
        // Square while it's just the planet: a fixed width means swapping frames never re-measures it.
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        // A test copy keeps its own place in the menu bar.
        item.autosaveName = Paths.testCopy ? "HypernovaTest" : "Hypernova"
        if let b = item.button {
            b.target = self
            b.action = #selector(clicked(_:))
            // Open on press, as menus do.
            b.sendAction(on: [.leftMouseDown, .rightMouseDown])
            b.imagePosition = .imageOnly
            icon.button = b
        }
        icon.onWidth = { [weak item] wide in item?.length = wide ? NSStatusItem.variableLength : NSStatusItem.squareLength }
        self.item = item
        // The model publishes only real changes, so this runs when there's something to show.
        AppModel.shared.objectWillChange
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.queueSync() } }
            .store(in: &bag)
        let ws = NSWorkspace.shared.notificationCenter
        ws.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.syncIcon() }
        }
        // Each of these hides the menu bar, so the icon stops turning until all of them are over.
        let watch: [(NotificationCenter, Notification.Name, (StatusItemController) -> Void)] = [
            (ws, NSWorkspace.screensDidSleepNotification, { $0.displaysAsleep = true }),
            (ws, NSWorkspace.screensDidWakeNotification, { $0.displaysAsleep = false }),
            (ws, NSWorkspace.sessionDidResignActiveNotification, { $0.sessionInactive = true }),
            (ws, NSWorkspace.sessionDidBecomeActiveNotification, { $0.sessionInactive = false }),
            // The lock screen covers a Mac whose displays are still on; only these say so.
            (DistributedNotificationCenter.default(), Notification.Name("com.apple.screenIsLocked"), { $0.screenLocked = true }),
            (DistributedNotificationCenter.default(), Notification.Name("com.apple.screenIsUnlocked"), { $0.screenLocked = false }),
        ]
        for (center, name, change) in watch {
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    change(self)
                    self.syncIcon()
                }
            }
        }
        syncIcon()
    }

    /// objectWillChange fires before the new value lands: read it on the next turn of the run loop.
    private func queueSync() {
        guard !syncQueued else { return }
        syncQueued = true
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated {
                self?.syncQueued = false
                self?.syncIcon()
            }
        }
    }

    private func syncIcon() {
        let m = AppModel.shared
        icon.apply(.init(up: m.daemonUp || !m.firstPollDone, working: m.workingCount, agents: m.agentsCount, needsYou: m.needsYouCount,
                         account: m.accountTrouble,
                         reduceMotion: NSWorkspace.shared.accessibilityDisplayShouldReduceMotion, seen: seen))
    }

    @objc private func clicked(_ sender: Any?) {
        isOpen ? close() : open()
    }

    // MARK: The popover

    func open() {
        guard let button = item?.button, let bw = button.window else { return }
        let screen = bw.screen ?? NSScreen.main
        let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let b = bw.convertToScreen(button.convert(button.bounds, to: nil))
        let top = min(b.minY, visible.maxY) - 5
        maxHeight = max(320, top - visible.minY - 10)

        let panel = self.panel ?? makePanel()
        contentHeight = nil
        let host = PopoverHostingView(rootView: AnyView(
            MenuPopover(dismiss: { [weak self] in self?.close() },
                        onHeight: { [weak self] h in
                            guard let self, h > 0, h != self.contentHeight else { return }
                            self.contentHeight = h
                            self.layout()
                        })
                .environmentObject(AppModel.shared)
        ))
        scroll?.documentView = host
        self.host = host

        // Centred under the icon, kept on screen.
        let x = min(max(b.midX - Self.width / 2, visible.minX + 8), visible.maxX - Self.width - 8)
        panel.anchorTop = NSPoint(x: x, y: top)
        layout(force: true)
        scroll?.contentView.scroll(to: .zero)

        AppModel.shared.popoverVisible = true
        button.highlight(true)
        let reduce = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        panel.alphaValue = reduce ? 1 : 0
        panel.makeKeyAndOrderFront(nil)
        if !reduce {
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.12
                panel.animator().alphaValue = 1
            }
        }
        startWatchingForDismissal()
    }

    func close() {
        guard let panel, panel.isVisible else { return }
        stopWatchingForDismissal()
        panel.orderOut(nil)
        item?.button?.highlight(false)
        // Dropping the view stops its clocks (sky, grain, meters) and resets it to the main page.
        scroll?.documentView = nil
        host = nil
        AppModel.shared.popoverVisible = false
    }

    private func makePanel() -> MenuPanel {
        let panel = MenuPanel()
        panel.onEscape = { [weak self] in self?.close() }

        let container = NSView()
        container.wantsLayer = true
        container.layer?.cornerRadius = 16
        container.layer?.cornerCurve = .continuous
        container.layer?.masksToBounds = true
        container.layer?.borderWidth = 1
        container.layer?.borderColor = NSColor(red: 0xF5 / 255, green: 0xF2 / 255, blue: 0xEB / 255, alpha: 0.11).cgColor

        // Glass: the desktop behind, blurred; the popover's warm tint and sky go on top of it.
        let fx = NSVisualEffectView()
        fx.material = .hudWindow
        fx.blendingMode = .behindWindow
        fx.state = .active
        fx.appearance = NSAppearance(named: .darkAqua)
        fx.autoresizingMask = [.width, .height]
        container.addSubview(fx)

        let scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.scrollerStyle = .overlay
        scroll.horizontalScrollElasticity = .none
        scroll.verticalScrollElasticity = .none
        scroll.autoresizingMask = [.width, .height]
        scroll.contentView = FlippedClipView()
        scroll.contentView.drawsBackground = false
        container.addSubview(scroll)

        // The glass card's top highlight, along the popover's top edge.
        let highlight = HighlightView()
        highlight.autoresizingMask = [.width, .minYMargin]
        container.addSubview(highlight)

        panel.contentView = container
        fx.frame = container.bounds
        scroll.frame = container.bounds
        highlight.frame = NSRect(x: 0, y: container.bounds.height - 1, width: container.bounds.width, height: 1)
        self.panel = panel
        self.scroll = scroll
        return panel
    }

    /// Sizes the panel to its content, up to the space below the menu bar (it scrolls past that).
    private func layout(force: Bool = false) {
        guard let panel, let host, let scroll else { return }
        let h = max(60, ceil(contentHeight ?? host.fittingSize.height))
        if host.frame.size != NSSize(width: Self.width, height: h) { host.frame = NSRect(x: 0, y: 0, width: Self.width, height: h) }
        let shown = min(h, maxHeight)
        scroll.verticalScrollElasticity = h > maxHeight ? .allowed : .none
        if force || abs(panel.frame.height - shown) > 0.5 || panel.frame.width != Self.width {
            panel.setFrame(NSRect(x: 0, y: 0, width: Self.width, height: shown), display: true)
            panel.invalidateShadow()
        }
    }

    // MARK: Dismissal: a click elsewhere, Esc, another app, another space

    private func startWatchingForDismissal() {
        stopWatchingForDismissal()
        if let m = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown], handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }) { monitors.append(m) }
        if let m = NSEvent.addLocalMonitorForEvents(matching: .keyDown, handler: { [weak self] e in
            guard e.keyCode == 53, let self, self.panel?.isKeyWindow == true else { return e }
            MainActor.assumeIsolated { self.close() }
            return nil
        }) { monitors.append(m) }
        let ws = NSWorkspace.shared.notificationCenter
        observers.append(ws.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] n in
            let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            guard app != NSRunningApplication.current else { return }
            MainActor.assumeIsolated { self?.close() }
        })
        observers.append(ws.addObserver(forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        })
    }

    private func stopWatchingForDismissal() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors = []
        for o in observers {
            NSWorkspace.shared.notificationCenter.removeObserver(o)
            NotificationCenter.default.removeObserver(o)
        }
        observers = []
    }
}

/// A borderless panel under the menu bar that takes keys without taking focus from the app you're
/// in. Its top edge stays put while its content grows and shrinks.
final class MenuPanel: NSPanel {
    /// The top-left corner on screen.
    var anchorTop: NSPoint?
    var onEscape: (() -> Void)?

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: StatusItemController.width, height: 400),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        isFloatingPanel = true
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient, .ignoresCycle]
        backgroundColor = .clear
        isOpaque = false
        hasShadow = true
        hidesOnDeactivate = false
        isMovable = false
        isReleasedWhenClosed = false
        appearance = NSAppearance(named: .darkAqua)
        title = "Hypernova"
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
    override func cancelOperation(_ sender: Any?) { onEscape?() }

    override func setFrame(_ frameRect: NSRect, display flag: Bool) {
        var r = frameRect
        if let a = anchorTop { r.origin = NSPoint(x: a.x, y: a.y - r.height) }
        super.setFrame(r, display: flag)
    }
}

/// The popover's SwiftUI content. (NSHostingView is flipped, so a scrolled popover starts at its
/// top.) Its height comes from the content itself, through MenuPopover's onHeight.
typealias PopoverHostingView = NSHostingView<AnyView>

/// Keeps a scrolled popover's content pinned to the top.
private final class FlippedClipView: NSClipView {
    override var isFlipped: Bool { true }
}

/// A 1pt line that fades in from both sides: the glass card's top highlight (ink 25%).
private final class HighlightView: NSView {
    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        let g = CAGradientLayer()
        let ink = NSColor(red: 0xF5 / 255, green: 0xF2 / 255, blue: 0xEB / 255, alpha: 1)
        g.colors = [ink.withAlphaComponent(0).cgColor, ink.withAlphaComponent(0.25).cgColor, ink.withAlphaComponent(0).cgColor]
        g.startPoint = CGPoint(x: 0, y: 0.5)
        g.endPoint = CGPoint(x: 1, y: 0.5)
        layer = g
    }
    required init?(coder: NSCoder) { fatalError() }
}

// MARK: - The icon

/// Drives the status button's image: outlined while the service is down, still when idle,
/// turning while any session works or agents a session started do (one turn in 3 s, easing in and
/// slowing to a stop on the rest frame), breathing instead of turning under Reduce Motion, and a
/// cut-out dot when something needs you. A timer runs only while it moves.
@MainActor
final class MenuBarIconAnimator {
    struct Look: Equatable {
        var up = true
        var working = 0
        /// Agents at work, their main agent idle or not: overnight runs keep it turning.
        var agents = 0
        var needsYou = 0
        /// What only you can fix about the leading agent's account, in words ("Claude Code is signed
        /// out", "no Claude plan", "Codex is signed out"): it lights the dot too, without counting as a request.
        var account: String? = nil
        var reduceMotion = false
        /// False while the displays sleep or the screen is locked.
        var seen = true
    }

    weak var button: NSStatusBarButton? { didSet { render(force: true) } }
    /// Widens the item for the count, and back to square without it.
    var onWidth: ((Bool) -> Void)?
    private(set) var look = Look()
    private var timer: Timer?
    private var interval: TimeInterval = 0
    private var motion = IconMotion()
    /// Reduce Motion: where the breath is, in breaths (2.4 s each).
    private var breath: Double = 0
    private var last: CFTimeInterval = 0
    private weak var shown: NSImage?

    var isAnimating: Bool { timer != nil }
    private var busy: Bool { look.working > 0 || look.agents > 0 }
    private var spinning: Bool { look.up && look.seen && busy && !look.reduceMotion }
    private var breathing: Bool { look.up && look.seen && busy && look.reduceMotion }

    func apply(_ l: Look) {
        look = l
        if !look.up || !look.seen || look.reduceMotion { motion.stop() }
        if !breathing { breath = 0 }
        updateText()
        updateTimer()
        render(force: false)
    }

    private func updateTimer() {
        let want: TimeInterval? = spinning || (look.up && motion.moving) ? IconMotion.tick : breathing ? 0.15 : nil
        if want == interval, timer != nil { return }
        timer?.invalidate()
        timer = nil
        interval = want ?? 0
        guard let want else { return }
        last = CACurrentMediaTime()
        let t = Timer(timeInterval: want, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        t.tolerance = 0.012
        // .common, so it keeps turning while a menu is open elsewhere in the menu bar.
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func tick() {
        let now = CACurrentMediaTime()
        let dt = min(max(now - last, 0), 0.25)
        last = now
        if breathing {
            breath = (breath + dt / 2.4).truncatingRemainder(dividingBy: 1)
        } else {
            motion.step(dt: dt, spinning: spinning)
        }
        render(force: false)
        if !spinning && !breathing && !motion.moving { updateTimer() }
    }

    private func render(force: Bool) {
        guard let button else { return }
        let badge = look.needsYou > 0 || look.account != nil
        let img: NSImage
        if !look.up {
            img = MenuBarFrames.hollow
        } else if breathing {
            img = MenuBarFrames.breath(Int(breath * Double(MenuBarFrames.breathCount)), badge: badge)
        } else {
            img = MenuBarFrames.spin(motion.frame, badge: badge)
        }
        if force || img !== shown {
            button.image = img
            shown = img
        }
    }

    private func updateText() {
        guard let button else { return }
        // The dot says "needs you"; from two on, the count says how many.
        let title = look.up && look.needsYou >= 2 ? "\(look.needsYou)" : ""
        if button.title != title {
            button.title = title
            button.imagePosition = title.isEmpty ? .imageOnly : .imageLeading
            onWidth?(!title.isEmpty)
            button.font = NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium)
        }
        var parts: [String] = []
        if !look.up { parts.append("the background service isn’t running") }
        else {
            if let trouble = look.account { parts.append(trouble) }
            // Needs first, then working by the one rule (a session whose agents run is working);
            // agents themselves are on the rows, not counted here.
            if look.needsYou > 0 { parts.append(look.needsYou == 1 ? "1 needs you" : "\(look.needsYou) need you") }
            if look.working > 0 { parts.append(look.working == 1 ? "1 session working" : "\(look.working) sessions working") }
        }
        let label = parts.isEmpty ? "Hypernova" : "Hypernova: " + parts.joined(separator: ", ")
        // Set only on a change: the feed can bring several updates a second while Claude works.
        if button.toolTip != label {
            button.setAccessibilityLabel(label)
            button.toolTip = label
        }
    }
}

/// The icon's turn: one turn in 3 s at full speed, spinning up over 0.7 s; once work stops it
/// slows over about a second to a crawl and stops on the next rest frame, so it never freezes
/// mid-turn. Kept apart from the timer so the renders can play it too.
struct IconMotion {
    /// The timer's step: one frame at full speed.
    static let tick: TimeInterval = 0.75 / Double(MenuBarFrames.spinCount)
    /// Where the turn is, in quarter turns (the frames repeat every quarter).
    private(set) var phase: Double = 0
    /// 0…1 of full speed.
    private(set) var speed: Double = 0

    var moving: Bool { speed > 0 }
    var frame: Int { Int((phase - phase.rounded(.down)) * Double(MenuBarFrames.spinCount)) % MenuBarFrames.spinCount }

    mutating func step(dt: Double, spinning: Bool) {
        if spinning { speed = min(1, speed + dt / 0.7) } else if speed > 0 { speed = max(0.3, speed - dt / 1.2) }
        guard speed > 0 else { return }
        let before = phase
        phase += dt * speed / 0.75
        if !spinning, speed <= 0.3, phase.rounded(.down) > before.rounded(.down) {
            phase = phase.rounded(.down)
            speed = 0
        }
        if phase > 1000 { phase = phase.truncatingRemainder(dividingBy: 1) }
    }

    /// Straight to rest (the service went away, or Reduce Motion came on).
    mutating func stop() {
        phase = phase.rounded(.down)
        speed = 0
    }
}
