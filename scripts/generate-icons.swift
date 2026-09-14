#!/usr/bin/env swift
// Run from the repository root: swift scripts/generate-icons.swift
// Uses macOS system frameworks only. All geometry is in a 1024-point design grid.
import AppKit

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
func write(_ data: Data, _ path: String) throws {
    try data.write(to: root.appendingPathComponent(path))
}
func color(_ hex: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 255) / 255,
            green: CGFloat((hex >> 8) & 255) / 255,
            blue: CGFloat(hex & 255) / 255, alpha: 1)
}
// A bottom opening makes the hierarchy read as a sunburst, with light tracing its cells.
// Angles are clockwise from twelve o'clock; ±145 leaves a 70-degree opening below.
// Radial geometry depends only on depth, never on file/directory color.
let ringWidths: [CGFloat] = [60, 60, 15, 15]
let ringGaps: [CGFloat] = [8, 8, 5]
let fileGray: UInt32 = 0x87919F
func ringBounds(_ depth: Int) -> (CGFloat, CGFloat) {
    let inner: CGFloat = 104 + ringWidths.prefix(depth).reduce(0, +)
        + ringGaps.prefix(depth).reduce(0, +)
    return (inner, inner + ringWidths[depth])
}
// Increasing angles follow the arc from its left/leading edge toward its right edge.
// Every subdivision uses ascending weights, so the small slices always lead.
func subdivide(_ start: CGFloat, _ end: CGFloat, weights: [CGFloat], gap: CGFloat = 2)
    -> [(CGFloat, CGFloat)] {
    let available = end - start - gap * CGFloat(weights.count - 1)
    let total = weights.reduce(0, +)
    var cursor = start
    return weights.map { weight in
        let stop = cursor + available * weight / total
        defer { cursor = stop + gap }
        return (cursor, stop)
    }
}
let branches: [(CGFloat, CGFloat, UInt32)] = [
    (-145, -86, 0xA17AFF),
    (-82, -24, 0xF0ABCA),
    (-20, 96, 0x70D6FF),
    (100, 145, 0x838CFF)
]
var sectors: [(CGFloat, CGFloat, CGFloat, CGFloat, UInt32)] = []
func addCell(_ depth: Int, _ start: CGFloat, _ end: CGFloat, _ tint: UInt32) {
    let (inner, outer) = ringBounds(depth)
    sectors.append((inner, outer, start, end, tint))
}
for (branchIndex, branch) in branches.enumerated() {
    let (start, end, tint) = branch
    // A broad gray file sector occupies the same first ring as colored directories.
    let roots = branchIndex == 0 ? subdivide(start, end, weights: [0.12, 0.88]) : [(start, end)]
    if branchIndex == 0 { addCell(0, roots[0].0, roots[0].1, fileGray) }
    let root = roots.last!
    addCell(0, root.0, root.1, tint)
    let children = subdivide(root.0, root.1, weights: [0.12, 0.23, 0.65], gap: 2)
    for (childIndex, child) in children.enumerated() {
        let (from, to) = child
        // Gray is a terminal file, not a thin cap pasted outside its parent's ring.
        let isFile = childIndex == 0
        addCell(1, from, to, isFile ? fileGray : tint)
        if isFile { continue }
        // The former third broad ring becomes a set of quarter-width outer rings.
        let thinDepths = childIndex == 1 || branchIndex == 3 ? 1 : 2
        var parents = [(from, to)]
        for offset in 0..<thinDepths {
            var next: [(CGFloat, CGFloat)] = []
            for parent in parents {
                let slices = subdivide(parent.0, parent.1,
                    weights: offset == 0 ? [0.18, 0.30, 0.52] : [0.26, 0.74], gap: 0.8)
                for (index, slice) in slices.enumerated() {
                    let terminal = index == 0
                    addCell(2 + offset, slice.0, slice.1, terminal ? fileGray : tint)
                    if !terminal { next.append(slice) }
                }
            }
            parents = next
        }
    }
}
let center = NSPoint(x: 512, y: 526)
func point(_ radius: CGFloat, _ degrees: CGFloat) -> NSPoint {
    let angle = degrees * .pi / 180
    return NSPoint(x: center.x + radius * sin(angle), y: center.y + radius * cos(angle))
}
func sector(_ inner: CGFloat, _ outer: CGFloat, _ start: CGFloat, _ end: CGFloat) -> NSBezierPath {
    let path = NSBezierPath()
    path.move(to: point(outer, start))
    path.appendArc(withCenter: center, radius: outer,
                   startAngle: 90 - start, endAngle: 90 - end, clockwise: true)
    path.line(to: point(inner, end))
    path.appendArc(withCenter: center, radius: inner,
                   startAngle: 90 - end, endAngle: 90 - start, clockwise: false)
    path.close()
    return path
}
func render(_ size: Int) -> Data {
    // Supersample small icons so circular edges remain smooth at native display size.
    let workingSize = max(1024, size)
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: workingSize,
        pixelsHigh: workingSize, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
        isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    let transform = NSAffineTransform()
    transform.scale(by: CGFloat(workingSize) / 1024)
    transform.concat()
    // Classic macOS tile: 824 points, centered with 100 points of transparent canvas.
    // This is a flattened ICNS asset, not an unmasked Icon Composer input layer.
    let tile = NSBezierPath(roundedRect: NSRect(x: 100, y: 100, width: 824, height: 824),
                            xRadius: 185, yRadius: 185)
    NSGradient(starting: color(0x11162E), ending: color(0x050817))!.draw(in: tile, angle: -90)
    NSGraphicsContext.saveGraphicsState()
    tile.addClip() // Glow must never leak into the transparent outer margin.
    for (inner, outer, start, end, tint) in sectors {
        if size <= 32 && inner >= 240 { continue }
        let path = sector(inner, outer, start, end)
        let light = color(tint)
        if size <= 32 {
            light.withAlphaComponent(0.95).setFill()
            path.fill()
            continue
        }
        // Bright filled cells remain visible at Dock sizes; the rim retains the light effect.
        NSGradient(starting: light.blended(withFraction: 0.18, of: .white)!.withAlphaComponent(0.95),
                   ending: light.withAlphaComponent(0.68))!.draw(in: path, angle: -55)
        NSGraphicsContext.saveGraphicsState()
        let halo = NSShadow()
        halo.shadowColor = light.withAlphaComponent(0.85)
        halo.shadowBlurRadius = size <= 64 ? 5 : 12
        halo.shadowOffset = .zero
        halo.set()
        light.withAlphaComponent(0.9).setStroke()
        path.lineWidth = size <= 64 ? 3 : (outer - inner > 20 ? 2.5 : 1.3)
        path.stroke()
        NSGraphicsContext.restoreGraphicsState()
        // An inner filament follows each outer arc, like the flowing light in the splash art.
        if size >= 128 && outer - inner > 20 {
            let filament = NSBezierPath()
            filament.appendArc(withCenter: center, radius: outer - 7,
                startAngle: 90 - start - 1, endAngle: 90 - end + 1, clockwise: true)
            light.blended(withFraction: 0.45, of: .white)!.withAlphaComponent(0.65).setStroke()
            filament.lineWidth = 1.2
            filament.stroke()
        }
    }
    // The scan root is a quiet, slightly lighter disk, like the screenshot's center.
    // The bottom opening remains in the surrounding hierarchy.
    let hub = NSBezierPath(ovalIn: NSRect(x: center.x - 88, y: center.y - 88,
                                         width: 176, height: 176))
    NSGradient(starting: color(0x252B40), ending: color(0x191E30))!.draw(in: hub, angle: -90)
    color(0xBDCEFF).withAlphaComponent(size <= 32 ? 0.6 : 0.45).setStroke()
    hub.lineWidth = size <= 32 ? 5 : 2
    hub.stroke()
    NSGraphicsContext.restoreGraphicsState()
    NSGraphicsContext.restoreGraphicsState()
    if size == workingSize { return bitmap.representation(using: .png, properties: [:])! }
    let result = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: result)
    NSGraphicsContext.current!.imageInterpolation = .high
    bitmap.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
    NSGraphicsContext.restoreGraphicsState()
    return result.representation(using: .png, properties: [:])!
}
func le16(_ n: Int) -> Data { Data([UInt8(n & 255), UInt8((n >> 8) & 255)]) }
func le32(_ n: Int) -> Data { le16(n & 65535) + le16(n >> 16) }
func be32(_ n: Int) -> Data { Data(le32(n).reversed()) }

let sizes = [16, 24, 32, 48, 64, 128, 256, 512, 1024]
let images = Dictionary(uniqueKeysWithValues: sizes.map { ($0, render($0)) })
for size in sizes where size <= 256 {
    try write(images[size]!, "src/main/resources/se/hirt/diskspace/icon-\(size).png")
}
try write(images[512]!, "src/linux/assets/icon.png")
try write(images[1024]!, "docs/design/app-icon.png")

let icoSizes = sizes.filter { $0 <= 256 }
var ico = le16(0) + le16(1) + le16(icoSizes.count)
var offset = 6 + 16 * icoSizes.count
for size in icoSizes {
    let png = images[size]!
    ico += Data([UInt8(size % 256), UInt8(size % 256), 0, 0])
    ico += le16(1) + le16(32) + le32(png.count) + le32(offset)
    offset += png.count
}
for size in icoSizes { ico += images[size]! }
try write(ico, "src/windows/assets/icon.ico")

// Include 1x and Retina representations for the classic .icns bundle pipeline.
let entries = [("icp4", 16), ("icp5", 32), ("icp6", 64), ("ic07", 128),
               ("ic08", 256), ("ic09", 512), ("ic10", 1024),
               ("ic11", 32), ("ic12", 64), ("ic13", 256), ("ic14", 512)]
var body = Data()
for (type, size) in entries {
    let png = images[size]!
    body += Data(type.utf8) + be32(png.count + 8) + png
}
try write(Data("icns".utf8) + be32(body.count + 8) + body, "src/macos/assets/icon.icns")
print("Generated runtime PNGs, Linux PNG, Windows ICO, macOS ICNS, and design preview.")
