// Renders text lines to transparent PNGs with the macOS text system, for tools/frame-screenshots.py.
// The system engine shapes every script (Devanagari, Japanese, Latin) and wraps each one by its
// own rules, with the system font and its automatic fallbacks, so no font paths are needed.
//
// Usage: render-text <jobs.json>
// jobs.json: [{"text": "...", "size": 74, "minSize": 52, "maxLines": 2, "weight": "bold",
//              "maxWidth": 918, "color": [1, 1, 1, 1], "out": "/path/line.png"}, ...]
// Each job writes a PNG the width of maxWidth and as tall as the wrapped text, and prints
// "<out> <width> <height> <lines> <size>" on one line.

import AppKit
import Foundation

struct Job: Decodable {
    let text: String
    let size: CGFloat
    let minSize: CGFloat
    let maxLines: Int
    let weight: String
    let maxWidth: CGFloat
    let color: [CGFloat]
    let out: String
}

func attributes(_ job: Job, size: CGFloat) -> [NSAttributedString.Key: Any] {
    let weight: NSFont.Weight = job.weight == "bold" ? .bold : .medium
    let font = NSFont.systemFont(ofSize: size, weight: weight)
    let para = NSMutableParagraphStyle()
    para.lineBreakMode = .byWordWrapping
    para.alignment = .left
    let c = job.color
    let color = NSColor(srgbRed: c[0], green: c[1], blue: c[2], alpha: c.count > 3 ? c[3] : 1)
    return [.font: font, .foregroundColor: color, .paragraphStyle: para]
}

let options: NSString.DrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]

func lineCount(_ job: Job, size: CGFloat) -> (Int, CGFloat) {
    let attrs = attributes(job, size: size)
    let one = NSAttributedString(string: "Ag", attributes: attrs)
        .boundingRect(with: NSSize(width: job.maxWidth, height: 10000), options: options).height
    let all = NSAttributedString(string: job.text, attributes: attrs)
        .boundingRect(with: NSSize(width: job.maxWidth, height: 10000), options: options).height
    return (max(1, Int((all / one).rounded())), all)
}

guard CommandLine.arguments.count == 2,
      let data = FileManager.default.contents(atPath: CommandLine.arguments[1]),
      let jobs = try? JSONDecoder().decode([Job].self, from: data) else {
    FileHandle.standardError.write("usage: render-text <jobs.json>\n".data(using: .utf8)!)
    exit(2)
}

for job in jobs {
    var size = job.size
    var (lines, height) = lineCount(job, size: size)
    while lines > job.maxLines && size - 2 >= job.minSize {
        size -= 2
        (lines, height) = lineCount(job, size: size)
    }
    let attrs = attributes(job, size: size)
    let string = NSAttributedString(string: job.text, attributes: attrs)
    let width = Int(ceil(job.maxWidth))
    let pixelHeight = Int(ceil(height)) + Int(size * 0.25)

    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: pixelHeight, bitsPerSample: 8,
        samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
        bytesPerRow: 0, bitsPerPixel: 0
    ), let context = NSGraphicsContext(bitmapImageRep: rep) else {
        FileHandle.standardError.write("could not make a bitmap for \(job.out)\n".data(using: .utf8)!)
        exit(1)
    }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = context
    context.cgContext.clear(CGRect(x: 0, y: 0, width: width, height: pixelHeight))
    // With usesLineFragmentOrigin the first line sits at the top of the rect, so the text fills
    // the bitmap from its top edge down.
    string.draw(with: NSRect(x: 0, y: 0, width: job.maxWidth, height: CGFloat(pixelHeight)), options: options)
    context.flushGraphics()
    NSGraphicsContext.restoreGraphicsState()

    guard let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
    do {
        try png.write(to: URL(fileURLWithPath: job.out))
    } catch {
        FileHandle.standardError.write("could not write \(job.out): \(error)\n".data(using: .utf8)!)
        exit(1)
    }
    print("\(job.out) \(width) \(pixelHeight) \(lines) \(Int(size))")
}
