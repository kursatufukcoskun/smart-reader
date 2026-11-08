import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

// MARK: - Apple Pencil çizgisini yakalama

/// Yalnızca Apple Pencil dokunuşlarını izleyen, çizgiyi canlı gösteren hareket tanıyıcı.
/// Parmak dokunuşlarına karışmaz; kaydırma ve seçim eskisi gibi çalışır.
final class PenStrokeRecognizer: UIGestureRecognizer {
    /// Çizgi bitince görünüm koordinatlarındaki noktalar gelir.
    var onStroke: (([CGPoint]) -> Void)?
    /// Her çizgi başında okunur: seçili stil ve renk
    var appearance: () -> (HighlightStyle, UIColor) = { (.highlight, .systemYellow) }

    private var style: HighlightStyle = .highlight
    private var color: UIColor = .systemYellow
    private var points: [CGPoint] = []
    private let shape = CAShapeLayer()

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        allowedTouchTypes = [NSNumber(value: UITouch.TouchType.pencil.rawValue)]
        cancelsTouchesInView = true
        delaysTouchesBegan = false
        shape.fillColor = nil
        shape.lineCap = .round
        shape.lineJoin = .round
        shape.zPosition = 10_000
    }

    private func collect(_ touch: UITouch, _ event: UIEvent?) {
        guard let view else { return }
        let all = event?.coalescedTouches(for: touch) ?? [touch]
        for t in all { points.append(t.location(in: view)) }
        let path = UIBezierPath()
        if let f = points.first { path.move(to: f) }
        for p in points.dropFirst() { path.addLine(to: p) }
        shape.path = path.cgPath
    }

    private func configureShape() {
        switch style {
        case .highlight:
            shape.strokeColor = color.withAlphaComponent(0.35).cgColor
            shape.lineWidth = 16
        case .underline, .wavy:
            shape.strokeColor = color.cgColor
            shape.lineWidth = 2.5
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = touches.first, let view else { state = .failed; return }
        points = []
        (style, color) = appearance()
        configureShape()
        view.layer.addSublayer(shape)
        shape.frame = view.bounds
        CATransaction.begin(); CATransaction.setDisableActions(true)
        shape.opacity = 1
        CATransaction.commit()
        collect(touch, event)
        state = .began
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = touches.first else { return }
        collect(touch, event)
        state = .changed
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        if let touch = touches.first { collect(touch, event) }
        let pts = points
        fadeOut()
        if length(pts) > 24 { onStroke?(pts) }
        state = .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        fadeOut()
        state = .cancelled
    }

    override func reset() { points = [] }

    private func fadeOut() {
        let layer = shape
        let a = CABasicAnimation(keyPath: "opacity")
        a.fromValue = 1; a.toValue = 0; a.duration = 0.25
        layer.opacity = 0
        layer.add(a, forKey: "fade")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak layer] in layer?.removeFromSuperlayer() }
    }

    private func length(_ p: [CGPoint]) -> CGFloat {
        zip(p, p.dropFirst()).reduce(0) { $0 + hypot($1.1.x - $1.0.x, $1.1.y - $1.0.y) }
    }
}

enum PenTouch {
    private static let all: [NSNumber] = [0, 1, 2, 3].map { NSNumber(value: $0) }

    /// Kalem modundayken kaydırma yalnızca parmakla olur; kalem çizim için ayrılır.
    static func restrictScroll(in view: UIView, penOnly: Bool) {
        if let sv = view as? UIScrollView {
            sv.panGestureRecognizer.allowedTouchTypes = penOnly ? [NSNumber(value: UITouch.TouchType.direct.rawValue)] : all
        }
        for sub in view.subviews { restrictScroll(in: sub, penOnly: penOnly) }
    }

    /// Çizgiden en fazla n noktaya seyrekleştirir (JS'e gönderilecek veri küçük kalsın).
    static func sample(_ pts: [CGPoint], max n: Int = 60) -> [[Double]] {
        guard pts.count > n else { return pts.map { [Double($0.x), Double($0.y)] } }
        let step = Double(pts.count - 1) / Double(n - 1)
        return (0..<n).map { i in
            let p = pts[min(pts.count - 1, Int((Double(i) * step).rounded()))]
            return [Double(p.x), Double(p.y)]
        }
    }
}

/// Apple Pencil 2 çift dokunuşu: çizim stilini sırayla değiştirir.
final class PencilTapHandler: NSObject, UIPencilInteractionDelegate {
    var onTap: (() -> Void)?

    func attach(to view: UIView) {
        let i = UIPencilInteraction()
        i.delegate = self
        view.addInteraction(i)
    }

    func pencilInteractionDidTap(_ interaction: UIPencilInteraction) { onTap?() }
}

// MARK: - Arayüz

/// Kalem modunda üstte görünen stil + renk seçici.
struct PenPalette: View {
    @EnvironmentObject var settings: ReaderSettings
    var close: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            ForEach(HighlightStyle.allCases) { s in
                Button { settings.penStyle = s } label: {
                    Image(systemName: s.symbol)
                        .font(.system(size: 17, weight: .medium))
                        .frame(width: 38, height: 34)
                        .background(settings.penStyle == s ? Color.accentColor.opacity(0.22) : .clear, in: RoundedRectangle(cornerRadius: 9))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(s.title)
            }
            Divider().frame(height: 22)
            ForEach(HighlightColor.allCases) { c in
                Button { settings.highlightColor = c } label: {
                    Circle().fill(c.color)
                        .frame(width: 26, height: 26)
                        .overlay(Circle().strokeBorder(Color.primary.opacity(settings.highlightColor == c ? 0.9 : 0), lineWidth: 2.5).padding(-4))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(c.title)
            }
            Divider().frame(height: 22)
            Button(action: close) {
                Image(systemName: "xmark.circle.fill").font(.system(size: 20)).foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

/// Kalemle bir yer çizilince altta görünen kısa menü.
struct PenResultBar: View {
    /// Çizilen yer tek kelimeyse sözlük düğmesi çıkar
    var dictionary: (() -> Void)?
    var translate: () -> Void
    var explain: () -> Void
    var note: () -> Void
    var undo: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            if let dictionary {
                Button(action: dictionary) {
                    Image(systemName: "character.book.closed").padding(.horizontal, 16).padding(.vertical, 12)
                }
                .accessibilityLabel("Sözlük")
                Divider().frame(height: 24)
            }
            Button(action: translate) {
                Label("Çevir", systemImage: "character.bubble").padding(.horizontal, 18).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: explain) {
                Label("Açıkla", systemImage: "text.magnifyingglass").padding(.horizontal, 18).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: note) {
                Label("Not al", systemImage: "note.text.badge.plus").padding(.horizontal, 18).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: undo) {
                Image(systemName: "arrow.uturn.backward").padding(.horizontal, 16).padding(.vertical, 12)
            }
            .accessibilityLabel("Geri al")
        }
        .font(.subheadline.weight(.medium))
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

/// Çizim kaydının son hali: gösterilecek menü için hangi vurgu çizildi.
struct PenResult: Equatable {
    var highlight: Highlight
    /// Çeviri ve açıklama için çizilen metnin geçtiği paragraf
    var paragraph: String
}
