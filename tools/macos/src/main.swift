import Cocoa
import SwiftUI

class AppDelegate: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    var statusItem: NSStatusItem?
    var popover: NSPopover?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)

        setupAppIcon()
        setupMenu()
        setupMainWindow()
        setupStatusBarItem()

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func setupAppIcon() {
        if let iconImage = NSImage(named: "AppIcon") {
            NSApp.applicationIconImage = iconImage
        }
    }

    func setupMenu() {
        let mainMenu = NSMenu()

        // Application Menu
        let appMenuItem = NSMenuItem()
        let appMenu = NSMenu()
        let appName = "Expressive Key Studio"
        appMenu.addItem(withTitle: "About \(appName)", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(NSMenuItem.separator())
        appMenu.addItem(withTitle: "Preferences...", action: #selector(openPreferences), keyEquivalent: ",")
        appMenu.addItem(NSMenuItem.separator())
        appMenu.addItem(withTitle: "Hide \(appName)", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        let hideOthers = NSMenuItem(title: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        hideOthers.keyEquivalentModifierMask = [.command, .option]
        appMenu.addItem(hideOthers)
        appMenu.addItem(withTitle: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: "")
        appMenu.addItem(NSMenuItem.separator())
        appMenu.addItem(withTitle: "Quit \(appName)", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appMenuItem.submenu = appMenu
        mainMenu.addItem(appMenuItem)

        // Edit Menu (Essential for Cmd+C, Cmd+V, Cmd+A, Cmd+Z, Cmd+X in TextFields)
        let editMenuItem = NSMenuItem()
        let editMenu = NSMenu(title: "Edit")
        editMenu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        editMenu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        editMenu.addItem(NSMenuItem.separator())
        editMenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editMenuItem.submenu = editMenu
        mainMenu.addItem(editMenuItem)

        // Window Menu
        let windowMenuItem = NSMenuItem()
        let windowMenu = NSMenu(title: "Window")
        windowMenu.addItem(withTitle: "Minimize", action: #selector(NSWindow.miniaturize(_:)), keyEquivalent: "m")
        windowMenu.addItem(withTitle: "Zoom", action: #selector(NSWindow.zoom(_:)), keyEquivalent: "")
        windowMenu.addItem(NSMenuItem.separator())
        windowMenu.addItem(withTitle: "Bring All to Front", action: #selector(NSApplication.arrangeInFront(_:)), keyEquivalent: "")
        windowMenuItem.submenu = windowMenu
        mainMenu.addItem(windowMenuItem)

        NSApp.mainMenu = mainMenu
    }

    func setupMainWindow() {
        let rect = NSRect(x: 0, y: 0, width: 1100, height: 750)
        window = NSWindow(
            contentRect: rect,
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.center()
        window.title = "Expressive Pro — License Studio"
        window.titlebarAppearsTransparent = true
        window.minSize = NSSize(width: 860, height: 560)
        window.isReleasedWhenClosed = false

        let contentView = ContentView()
        window.contentView = NSHostingView(rootView: contentView)
    }

    func setupStatusBarItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem?.button {
            button.image = NSImage(systemSymbolName: "key.horizontal.fill", accessibilityDescription: "Expressive Key Studio")
            button.action = #selector(togglePopover(_:))
            button.target = self
        }

        let popover = NSPopover()
        popover.contentSize = NSSize(width: 280, height: 260)
        popover.behavior = .transient

        let menuBarView = MenuBarView { [weak self] in
            self?.openMainWindow()
            self?.popover?.performClose(nil)
        }
        popover.contentViewController = NSHostingController(rootView: menuBarView)
        self.popover = popover
    }

    @objc func togglePopover(_ sender: AnyObject?) {
        guard let button = statusItem?.button, let popover = popover else { return }
        if popover.isShown {
            popover.performClose(sender)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
        }
    }

    @objc func openPreferences() {
        openMainWindow()
    }

    func openMainWindow() {
        if !window.isVisible {
            window.makeKeyAndOrderFront(nil)
        }
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag {
            openMainWindow()
        }
        return true
    }
}

// App Entry Point
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
