#!/usr/bin/env swift
// Export the approved artwork into Android's 108dp adaptive canvas and store metadata.
// Run from the repository root: swift scripts/export-expressive-icon.swift
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

func load(_ path: String) -> CGImage {
    let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil)!
    return CGImageSourceCreateImageAtIndex(source, 0, nil)!
}

func context(_ size: Int) -> CGContext {
    CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
              bytesPerRow: size * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
}

func save(_ image: CGImage, _ path: String) {
    let url = URL(fileURLWithPath: path)
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                           withIntermediateDirectories: true)
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    precondition(CGImageDestinationFinalize(destination), "PNG export failed: \(path)")
}

let source = load("docs/assets/expressive/expressive-bloom-foreground.png")
precondition(source.width == source.height)
let decoded = context(source.width)
decoded.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
let pixels = decoded.data!.assumingMemoryBound(to: UInt8.self)
var minX = source.width, minY = source.height, maxX = 0, maxY = 0
for y in 0..<source.height {
    for x in 0..<source.width where pixels[(y * source.width + x) * 4 + 3] >= 128 {
        minX = min(minX, x); maxX = max(maxX, x)
        minY = min(minY, y); maxY = max(maxY, y)
    }
}
precondition(minX > 0 && minY > 0 && maxX < source.width - 1 && maxY < source.height - 1,
             "Foreground must have transparent margins")
let centerX = Double(minX + maxX + 1) / 2, centerY = Double(minY + maxY + 1) / 2
var radius = 0.0
for y in minY...maxY {
    for x in minX...maxX where pixels[(y * source.width + x) * 4 + 3] >= 128 {
        radius = max(radius, hypot(Double(x) + 0.5 - centerX, Double(y) + 0.5 - centerY))
    }
}
// Keep the mark inside the centered 66dp safe circle with 1px for antialiasing.
let canvas = 432
let scale = 131.0 / radius
let foreground = context(canvas)
foreground.interpolationQuality = .high
// Bitmap rows run from the top; CGContext drawing coordinates run from the bottom.
foreground.draw(source, in: CGRect(x: 216 - centerX * scale, y: 216 - (Double(source.height) - centerY) * scale,
                                  width: Double(source.width) * scale, height: Double(source.height) * scale))
save(foreground.makeImage()!, "expressive/res/drawable-xxxhdpi/ic_launcher_expressive_foreground.png")

let monochrome = context(canvas)
let colorPixels = foreground.data!.assumingMemoryBound(to: UInt8.self)
let monoPixels = monochrome.data!.assumingMemoryBound(to: UInt8.self)
for index in stride(from: 0, to: canvas * canvas * 4, by: 4) {
    let alpha = colorPixels[index + 3]
    monoPixels[index] = alpha; monoPixels[index + 1] = alpha
    monoPixels[index + 2] = alpha; monoPixels[index + 3] = alpha
}
save(monochrome.makeImage()!, "expressive/res/drawable-xxxhdpi/ic_launcher_expressive_monochrome.png")

let artwork = load("docs/assets/expressive/expressive-bloom.png")
let store = context(512)
store.interpolationQuality = .high
store.draw(artwork, in: CGRect(x: 0, y: 0, width: 512, height: 512))
save(store.makeImage()!, "fastlane/metadata/android/en-US/images/icon.png")
print("Exported 432px adaptive foreground and monochrome; 512px store icon. Mark radius: 32.75dp.")
