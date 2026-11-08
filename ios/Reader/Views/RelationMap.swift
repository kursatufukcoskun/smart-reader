import SwiftUI
import WebKit

/// Karakter / kavram ilişki haritası: yapay zekâ yalnızca düğüm ve kenar listesi (JSON) üretir; yerleşim ve çizim
/// sayfadaki küçük bir JS ile (kuvvet tabanlı yerleşim, kaydırma/yakınlaştırma) yapılır.
enum RelationMap {
    struct Failure: LocalizedError { var errorDescription: String? }

    static func html(context: String, bookTitle: String, config: LLMConfig, bg: String, fg: String) async throws -> String {
        let out = try await LLMClient.complete(config, system: "You output only valid JSON, with no commentary.",
                                               user: Prompts.relationMap(context: context, bookTitle: bookTitle), maxTokens: 3500)
        return page(json: try sanitize(out), bg: bg, fg: fg)
    }

    /// Model çıktısından geçerli bir {nodes, edges} nesnesi çıkarır; fazlalıkları ve hatalı kayıtları atar.
    static func sanitize(_ out: String) throws -> String {
        guard let a = out.firstIndex(of: "{"), let b = out.lastIndex(of: "}"), a < b,
              let data = String(out[a...b]).data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawNodes = obj["nodes"] as? [[String: Any]] else {
            throw Failure(errorDescription: "Harita üretilemedi, tekrar dene.")
        }
        func str(_ v: Any?) -> String? { (v as? String) ?? (v as? Int).map(String.init) }
        var nodes: [[String: Any]] = []
        var ids = Set<String>()
        for n in rawNodes.prefix(24) {
            guard let id = str(n["id"]), let label = n["label"] as? String, !label.isEmpty, !ids.contains(id) else { continue }
            ids.insert(id)
            nodes.append(["id": id, "label": String(label.prefix(40)), "group": (n["group"] as? String) ?? "other",
                          "note": String(((n["note"] as? String) ?? "").prefix(240))])
        }
        var edges: [[String: Any]] = []
        for e in ((obj["edges"] as? [[String: Any]]) ?? []).prefix(40) {
            guard let f = str(e["from"]), let t = str(e["to"]), ids.contains(f), ids.contains(t), f != t else { continue }
            edges.append(["from": f, "to": t, "label": String(((e["label"] as? String) ?? "").prefix(30))])
        }
        guard nodes.count >= 2 else { throw Failure(errorDescription: "Okuduğun kısımda haritalanacak yeterli karakter ya da kavram yok.") }
        let d = try JSONSerialization.data(withJSONObject: ["nodes": nodes, "edges": edges])
        return (String(data: d, encoding: .utf8) ?? "{}").replacingOccurrences(of: "</", with: "<\\/")
    }

    static func page(json: String, bg: String, fg: String) -> String {
        template.replacingOccurrences(of: "__BG__", with: bg)
            .replacingOccurrences(of: "__FG__", with: fg)
            .replacingOccurrences(of: "__DATA__", with: json)
    }

    static let template = #"""
<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body{margin:0;height:100%;background:__BG__;color:__FG__;font-family:-apple-system,Roboto,Helvetica,sans-serif;overflow:hidden;touch-action:none;-webkit-user-select:none;user-select:none}
svg{width:100%;height:100%;display:block}
.node rect{stroke-width:1.6}
.node text{font-size:13px;fill:__FG__;text-anchor:middle;dominant-baseline:central;pointer-events:none}
.node.sel rect{stroke-width:3}
.edge{stroke:__FG__;stroke-opacity:.42;stroke-width:1.3;fill:none}
.elabel{font-size:10.5px;fill:__FG__;opacity:.85;text-anchor:middle;dominant-baseline:central}
.elbg{fill:__BG__;opacity:.88}
#note{position:fixed;left:12px;right:12px;bottom:14px;padding:10px 14px;border-radius:14px;background:rgba(128,128,128,.28);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);font-size:14px;line-height:1.35;display:none}
#note b{display:block;margin-bottom:3px;font-size:15px}
#note i{opacity:.75}
#legend{position:fixed;left:12px;top:10px;font-size:11px;opacity:.9}
#legend span{display:inline-flex;align-items:center;margin-right:10px}
#legend em{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:4px}
#empty{position:fixed;inset:0;display:none;align-items:center;justify-content:center;text-align:center;padding:30px;opacity:.7}
</style></head>
<body>
<svg id="s"><defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0L10,5L0,10z" fill="__FG__" fill-opacity=".55"/></marker></defs><g id="vp"></g></svg>
<div id="legend"></div><div id="note"></div><div id="empty">Haritalanacak yeterli karakter ya da kavram bulunamadı.</div>
<script>
var DATA = __DATA__;
var NS = 'http://www.w3.org/2000/svg';
var COLORS = { person: '#4C8BF5', place: '#34A853', concept: '#F5A623', org: '#B36AE2', other: '#8E8E93' };
var LABELS = { person: 'Kişi', place: 'Yer', concept: 'Kavram', org: 'Grup', other: 'Diğer' };
var nodes = (DATA.nodes || []).slice(0, 24), edges = [];
var byId = {};
nodes.forEach(function (n, i) { n.id = String(n.id); n.label = String(n.label || '?'); if (!COLORS[n.group]) n.group = 'other'; byId[n.id] = n; });
(DATA.edges || []).forEach(function (e) {
  e.from = String(e.from); e.to = String(e.to);
  if (byId[e.from] && byId[e.to] && e.from !== e.to) edges.push(e);
});
var svg = document.getElementById('s'), vp = document.getElementById('vp');
var noteEl = document.getElementById('note');
if (nodes.length < 2) { document.getElementById('empty').style.display = 'flex'; }
function el(name, attrs, parent) {
  var e = document.createElementNS(NS, name);
  for (var k in attrs) e.setAttribute(k, attrs[k]);
  if (parent) parent.appendChild(e);
  return e;
}
var cv = document.createElement('canvas').getContext('2d');
cv.font = '13px sans-serif';
nodes.forEach(function (n, i) {
  n.w = Math.max(64, Math.round(cv.measureText(n.label).width) + 28);
  n.h = 32;
  var a = 2 * Math.PI * i / Math.max(1, nodes.length);
  n.x = Math.cos(a) * 220 + ((i * 37) % 11 - 5);
  n.y = Math.sin(a) * 170 + ((i * 53) % 13 - 6);
});

// Fruchterman–Reingold yerleşimi + kutu çakışmalarını giderme
(function layout() {
  var K = 150, N = nodes.length;
  for (var it = 0; it < 500; it++) {
    var temp = 55 * (1 - it / 500) + 2;
    nodes.forEach(function (n) { n.dx = 0; n.dy = 0; });
    for (var i = 0; i < N; i++) for (var j = i + 1; j < N; j++) {
      var a = nodes[i], b = nodes[j], dx = a.x - b.x, dy = a.y - b.y;
      var d = Math.sqrt(dx * dx + dy * dy) || 0.1, rep = (K * K) / d, ux = dx / d, uy = dy / d;
      a.dx += ux * rep; a.dy += uy * rep; b.dx -= ux * rep; b.dy -= uy * rep;
    }
    edges.forEach(function (e) {
      var a = byId[e.from], b = byId[e.to], dx = a.x - b.x, dy = a.y - b.y;
      var d = Math.sqrt(dx * dx + dy * dy) || 0.1, att = (d * d) / K, ux = dx / d, uy = dy / d;
      a.dx -= ux * att; a.dy -= uy * att; b.dx += ux * att; b.dy += uy * att;
    });
    nodes.forEach(function (n) {
      n.dx -= n.x * 0.9; n.dy -= n.y * 0.9;
      var d = Math.sqrt(n.dx * n.dx + n.dy * n.dy) || 0.1, m = Math.min(d, temp);
      n.x += n.dx / d * m; n.y += n.dy / d * m;
    });
  }
  for (var pass = 0; pass < 80; pass++) {
    var moved = false;
    for (var i2 = 0; i2 < N; i2++) for (var j2 = i2 + 1; j2 < N; j2++) {
      var p = nodes[i2], q = nodes[j2];
      var ox = (p.w + q.w) / 2 + 18 - Math.abs(p.x - q.x), oy = (p.h + q.h) / 2 + 22 - Math.abs(p.y - q.y);
      if (ox > 0 && oy > 0) {
        moved = true;
        if (ox < oy) { var sx = (p.x < q.x ? -1 : 1) * ox / 2; p.x += sx; q.x -= sx; }
        else { var sy = (p.y < q.y ? -1 : 1) * oy / 2; p.y += sy; q.y -= sy; }
      }
    }
    if (!moved) break;
  }
})();

// Kutunun kenarında, diğer düğüme doğru çıkış noktası
function edgePoint(a, b) {
  var dx = b.x - a.x, dy = b.y - a.y;
  if (!dx && !dy) return { x: a.x, y: a.y };
  var t = Math.min((a.w / 2) / (Math.abs(dx) || 1e-6), (a.h / 2) / (Math.abs(dy) || 1e-6));
  return { x: a.x + dx * t, y: a.y + dy * t };
}
var pairTotal = {}, pairIdx = {};
function pairKey(e) { return e.from < e.to ? e.from + '|' + e.to : e.to + '|' + e.from; }
edges.forEach(function (e) { var k = pairKey(e); pairTotal[k] = (pairTotal[k] || 0) + 1; });
var gE = el('g', {}, vp), gL = el('g', {}, vp), gN = el('g', {}, vp);
edges.forEach(function (e) {
  var a = byId[e.from], b = byId[e.to];
  var key = pairKey(e), idx = pairIdx[key] = (pairIdx[key] === undefined ? 0 : pairIdx[key] + 1);
  var p1 = edgePoint(a, b), p2 = edgePoint(b, a);
  // Aynı çiftin birden çok kenarı ters yönlere eğilir (ortak, sıralı bir dik doğrultu kullanılır)
  var lo = e.from < e.to ? a : b, hi = e.from < e.to ? b : a;
  var cx = hi.x - lo.x, cy = hi.y - lo.y, cl = Math.sqrt(cx * cx + cy * cy) || 1;
  var px = -cy / cl, py = cx / cl;
  var off = (idx - (pairTotal[key] - 1) / 2) * 34;
  var mx = (p1.x + p2.x) / 2 + px * off, my = (p1.y + p2.y) / 2 + py * off;
  el('path', { class: 'edge', d: 'M' + p1.x + ',' + p1.y + ' Q' + ((p1.x + p2.x) / 2 + px * off * 2) + ',' + ((p1.y + p2.y) / 2 + py * off * 2) + ' ' + p2.x + ',' + p2.y, 'marker-end': 'url(#arr)' }, gE);
  if (e.label) {
    var txt = String(e.label);
    var tw = Math.round(cv.measureText(txt).width * 0.82) + 10;
    el('rect', { class: 'elbg', x: mx - tw / 2, y: my - 8, width: tw, height: 16, rx: 5 }, gL);
    var t = el('text', { class: 'elabel', x: mx, y: my }, gL);
    t.textContent = txt;
  }
});
var groupsUsed = {};
nodes.forEach(function (n, i) {
  groupsUsed[n.group] = true;
  var g = el('g', { class: 'node', 'data-i': i, transform: 'translate(' + n.x + ',' + n.y + ')' }, gN);
  el('rect', { x: -n.w / 2, y: -n.h / 2, width: n.w, height: n.h, rx: 10, ry: 10, fill: COLORS[n.group] + '38', stroke: COLORS[n.group] }, g);
  var t = el('text', { x: 0, y: 0 }, g);
  t.textContent = n.label;
});
var lg = document.getElementById('legend');
Object.keys(groupsUsed).forEach(function (g) {
  var s = document.createElement('span');
  s.innerHTML = '<em style="background:' + COLORS[g] + '"></em>' + LABELS[g];
  lg.appendChild(s);
});

// Görünüme sığdır + kaydır / yakınlaştır
var tx = 0, ty = 0, sc = 1, fitSc = 1, fitTx = 0, fitTy = 0;
function fit() {
  if (!nodes.length) return;
  var minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
  nodes.forEach(function (n) { minX = Math.min(minX, n.x - n.w / 2); maxX = Math.max(maxX, n.x + n.w / 2); minY = Math.min(minY, n.y - n.h / 2); maxY = Math.max(maxY, n.y + n.h / 2); });
  var vw = window.innerWidth, vh = window.innerHeight;
  var bw = maxX - minX + 70, bh = maxY - minY + 130;
  fitSc = Math.min(vw / bw, vh / bh, 1.5);
  fitTx = vw / 2 - (minX + maxX) / 2 * fitSc;
  fitTy = vh / 2 - (minY + maxY) / 2 * fitSc + 8;
  tx = fitTx; ty = fitTy; sc = fitSc; apply();
}
function apply() { vp.setAttribute('transform', 'translate(' + tx + ',' + ty + ') scale(' + sc + ')'); }
window.addEventListener('resize', fit);
function zoomAt(px, py, ns) {
  ns = Math.max(fitSc * 0.5, Math.min(fitSc * 6, ns));
  tx = px - (px - tx) * (ns / sc); ty = py - (py - ty) * (ns / sc); sc = ns; apply();
}
function select(i) {
  document.querySelectorAll('.node').forEach(function (g) { g.classList.remove('sel'); });
  if (i < 0) { noteEl.style.display = 'none'; return; }
  var n = nodes[i];
  document.querySelectorAll('.node')[i].classList.add('sel');
  var rel = [];
  edges.forEach(function (e) {
    if (e.from === n.id) rel.push('→ ' + byId[e.to].label + (e.label ? ' (' + e.label + ')' : ''));
    else if (e.to === n.id) rel.push('← ' + byId[e.from].label + (e.label ? ' (' + e.label + ')' : ''));
  });
  noteEl.innerHTML = '';
  var b = document.createElement('b'); b.textContent = n.label; noteEl.appendChild(b);
  if (n.note) { var p = document.createElement('div'); p.textContent = n.note; noteEl.appendChild(p); }
  if (rel.length) { var r = document.createElement('i'); r.textContent = rel.slice(0, 8).join('   '); noteEl.appendChild(r); }
  noteEl.style.display = 'block';
}
function nodeAt(x, y) {
  var e = document.elementFromPoint(x, y);
  var g = e && e.closest ? e.closest('.node') : null;
  return g ? parseInt(g.getAttribute('data-i'), 10) : -1;
}
var mode = 0, sx = 0, sy = 0, ox = 0, oy = 0, moved = false, d0 = 0, s0 = 1, lastTap = 0;
function dist(t) { return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }
svg.addEventListener('touchstart', function (e) {
  e.preventDefault();
  if (e.touches.length === 2) { mode = 2; d0 = dist(e.touches); s0 = sc; ox = tx; oy = ty; moved = true; sx = (e.touches[0].clientX + e.touches[1].clientX) / 2; sy = (e.touches[0].clientY + e.touches[1].clientY) / 2; }
  else { mode = 1; sx = e.touches[0].clientX; sy = e.touches[0].clientY; ox = tx; oy = ty; moved = false; }
}, { passive: false });
svg.addEventListener('touchmove', function (e) {
  e.preventDefault();
  if (mode === 2 && e.touches.length === 2) {
    var ns = Math.max(fitSc * 0.5, Math.min(fitSc * 6, s0 * dist(e.touches) / (d0 || 1)));
    tx = sx - (sx - ox) * (ns / s0); ty = sy - (sy - oy) * (ns / s0); sc = ns; apply();
  } else if (mode === 1 && e.touches.length === 1) {
    var dx = e.touches[0].clientX - sx, dy = e.touches[0].clientY - sy;
    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
    tx = ox + dx; ty = oy + dy; apply();
  }
}, { passive: false });
svg.addEventListener('touchend', function (e) {
  e.preventDefault();
  if (e.touches.length > 0) return;
  if (mode === 1 && !moved) {
    var t = e.changedTouches[0], now = Date.now();
    if (now - lastTap < 320) { lastTap = 0; tx = fitTx; ty = fitTy; sc = fitSc; apply(); select(-1); }
    else { lastTap = now; select(nodeAt(t.clientX, t.clientY)); }
  }
  mode = 0;
}, { passive: false });
// Fare (Mac): sürükle ve tekerlekle yakınlaştır
var down = false;
svg.addEventListener('mousedown', function (e) { down = true; moved = false; sx = e.clientX; sy = e.clientY; ox = tx; oy = ty; });
window.addEventListener('mousemove', function (e) { if (!down) return; if (Math.abs(e.clientX - sx) > 4 || Math.abs(e.clientY - sy) > 4) moved = true; tx = ox + e.clientX - sx; ty = oy + e.clientY - sy; apply(); });
window.addEventListener('mouseup', function (e) { if (down && !moved) select(nodeAt(e.clientX, e.clientY)); down = false; });
svg.addEventListener('wheel', function (e) { e.preventDefault(); zoomAt(e.clientX, e.clientY, sc * (e.deltaY < 0 ? 1.12 : 0.89)); }, { passive: false });
fit();
</script></body></html>
"""#
}

@MainActor
final class RelationMapModel: ObservableObject {
    enum State { case idle, loading, ready(String), failed(String) }
    @Published var state: State = .idle
    @Published var presented = false
    private var task: Task<Void, Never>?

    /// Okunan kısma dayanarak haritayı üretir (kitapla sohbetle aynı spoiler'sız bağlam).
    func start(bookTitle: String, settings: ReaderSettings, context: @escaping (Int) async -> BookContext) {
        presented = true
        state = .loading
        task?.cancel()
        let config = settings.config(.smart)
        let bg = settings.theme.bgHex, fg = settings.theme.textHex
        task = Task { [weak self] in
            let ctx = await context(250_000)
            guard !Task.isCancelled else { return }
            guard !ctx.text.isEmpty else { self?.state = .failed("Okunan kısımdan metin çıkarılamadı (taranmış PDF olabilir)."); return }
            do {
                let html = try await RelationMap.html(context: ctx.text, bookTitle: bookTitle, config: config, bg: bg, fg: fg)
                if !Task.isCancelled { self?.state = .ready(html) }
            } catch {
                if !Task.isCancelled { self?.state = .failed(error.localizedDescription) }
            }
        }
    }

    func cancel() { task?.cancel() }
}

struct RelationMapWebView: UIViewRepresentable {
    let html: String
    func makeUIView(context: Context) -> WKWebView {
        let w = WKWebView(frame: .zero)
        w.isOpaque = false
        w.backgroundColor = .clear
        w.scrollView.isScrollEnabled = false
        w.loadHTMLString(html, baseURL: nil)
        return w
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct RelationMapSheet: View {
    @ObservedObject var map: RelationMapModel
    var regenerate: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch map.state {
                case .idle, .loading:
                    VStack(spacing: 14) {
                        ProgressView()
                        Text("Okuduğun kısımdan harita çiziliyor…").font(.footnote).foregroundStyle(.secondary)
                    }
                case .failed(let msg):
                    ContentUnavailableView {
                        Label("Harita oluşturulamadı", systemImage: "exclamationmark.triangle")
                    } description: { Text(msg) } actions: {
                        Button("Tekrar dene", action: regenerate).buttonStyle(.borderedProminent)
                    }
                case .ready(let html):
                    RelationMapWebView(html: html)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("Karakter ve ilişki haritası")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Kapat") { map.cancel(); dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: regenerate) { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Yeniden üret")
                }
            }
        }
    }
}

private struct RelationMapSheetModifier: ViewModifier {
    @ObservedObject var map: RelationMapModel
    var regenerate: () -> Void
    func body(content: Content) -> some View {
        content.sheet(isPresented: $map.presented) {
            RelationMapSheet(map: map, regenerate: regenerate).presentationDetents([.large])
        }
    }
}

extension View {
    func relationMapSheet(_ map: RelationMapModel, regenerate: @escaping () -> Void) -> some View {
        modifier(RelationMapSheetModifier(map: map, regenerate: regenerate))
    }
}
