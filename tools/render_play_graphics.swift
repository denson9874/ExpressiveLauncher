#!/usr/bin/env swift

import AppKit
import Foundation

private func color(_ hex: UInt32) -> NSColor {
    NSColor(
        calibratedRed: CGFloat((hex >> 16) & 0xff) / 255,
        green: CGFloat((hex >> 8) & 0xff) / 255,
        blue: CGFloat(hex & 0xff) / 255,
        alpha: 1
    )
}

private func render(width: Int, height: Int, hasAlpha: Bool, draw: () -> Void) -> Data {
    guard let bitmap = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: width,
        pixelsHigh: height,
        bitsPerSample: 8,
        samplesPerPixel: hasAlpha ? 4 : 3,
        hasAlpha: hasAlpha,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else {
        fatalError("Unable to allocate bitmap")
    }

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    draw()
    NSGraphicsContext.restoreGraphicsState()

    guard let data = bitmap.representation(using: .png, properties: [:]) else {
        fatalError("Unable to encode PNG")
    }
    return data
}

private func write(_ data: Data, to url: URL) throws {
    try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )
    try data.write(to: url, options: .atomic)
}

guard CommandLine.arguments.count == 4 else {
    fputs("usage: render_play_graphics.swift FOREGROUND.png ICON.png FEATURE.png\n", stderr)
    exit(2)
}

let foregroundURL = URL(fileURLWithPath: CommandLine.arguments[1])
let iconURL = URL(fileURLWithPath: CommandLine.arguments[2])
let featureURL = URL(fileURLWithPath: CommandLine.arguments[3])

guard let foreground = NSImage(contentsOf: foregroundURL) else {
    fputs("Unable to read foreground image at \(foregroundURL.path)\n", stderr)
    exit(1)
}

let icon = render(width: 512, height: 512, hasAlpha: true) {
    color(0xE8DEFF).setFill()
    NSBezierPath(rect: NSRect(x: 0, y: 0, width: 512, height: 512)).fill()
    foreground.draw(
        in: NSRect(x: 0, y: 0, width: 512, height: 512),
        from: .zero,
        operation: .sourceOver,
        fraction: 1
    )
}

// Play rejects feature graphics with an alpha channel, even when every pixel is opaque.
let feature = render(width: 1024, height: 500, hasAlpha: false) {
    let canvas = NSRect(x: 0, y: 0, width: 1024, height: 500)
    NSGradient(starting: color(0x171027), ending: color(0x55339A))?.draw(in: canvas, angle: 12)

    color(0x8E6CFF).withAlphaComponent(0.17).setFill()
    NSBezierPath(ovalIn: NSRect(x: 730, y: 250, width: 390, height: 390)).fill()
    color(0x5DE1B7).withAlphaComponent(0.12).setFill()
    NSBezierPath(ovalIn: NSRect(x: 610, y: -185, width: 430, height: 430)).fill()

    let tileRect = NSRect(x: 72, y: 74, width: 352, height: 352)
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.28)
    shadow.shadowBlurRadius = 28
    shadow.shadowOffset = NSSize(width: 0, height: -10)
    shadow.set()
    color(0xE8DEFF).setFill()
    NSBezierPath(roundedRect: tileRect, xRadius: 82, yRadius: 82).fill()
    NSShadow().set()
    foreground.draw(in: tileRect, from: .zero, operation: .sourceOver, fraction: 1)

    let paragraph = NSMutableParagraphStyle()
    paragraph.alignment = .left
    let expressiveAttributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 68, weight: .bold),
        .foregroundColor: NSColor.white,
        .paragraphStyle: paragraph,
    ]
    let launcherAttributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 43, weight: .semibold),
        .foregroundColor: color(0xDED2FF),
        .paragraphStyle: paragraph,
    ]
    let subtitleAttributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 24, weight: .medium),
        .foregroundColor: NSColor.white.withAlphaComponent(0.84),
        .paragraphStyle: paragraph,
    ]

    NSString(string: "Expressive").draw(at: NSPoint(x: 474, y: 285), withAttributes: expressiveAttributes)
    NSString(string: "Launcher L3").draw(at: NSPoint(x: 478, y: 226), withAttributes: launcherAttributes)
    NSString(string: "Your Android 17 home, made personal.").draw(
        at: NSPoint(x: 480, y: 156),
        withAttributes: subtitleAttributes
    )
}

do {
    try write(icon, to: iconURL)
    try write(feature, to: featureURL)
} catch {
    fputs("Unable to write Play graphics: \(error)\n", stderr)
    exit(1)
}
