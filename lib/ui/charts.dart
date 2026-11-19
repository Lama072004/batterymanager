import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/format.dart';
import '../core/model.dart';
import 'theme.dart';

/// Schraffur (45°, 1 dp, Abstand 6 dp) – bedeutet ausschließlich „keine Daten“.
void paintHatch(Canvas c, Rect r, Color color) {
  if (r.width <= 0 || r.height <= 0) return;
  c.save();
  c.clipRect(r);
  final p = Paint()
    ..color = color.withValues(alpha: 0.55)
    ..strokeWidth = 1;
  for (var x = r.left - r.height; x < r.right; x += 6) {
    c.drawLine(Offset(x, r.bottom), Offset(x + r.height, r.top), p);
  }
  c.restore();
}

TextPainter _label(String s, TextStyle style) =>
    TextPainter(text: TextSpan(text: s, style: style), textDirection: TextDirection.ltr)..layout();

/// Unbedeckte Intervalle im Fenster (Lücken aus der Abdeckung, nie aus der Punktdichte).
List<(int, int)> gapsIn(int from, int to, List<Coverage> cov) {
  final sorted = [...cov]..sort((a, b) => a.start.compareTo(b.start));
  final out = <(int, int)>[];
  var cur = from;
  for (final c in sorted) {
    if (c.start > cur + 5 * 60 * 1000) out.add((cur, math.min(c.start, to)));
    cur = math.max(cur, c.end);
    if (cur >= to) break;
  }
  if (cur < to - 5 * 60 * 1000) out.add((cur, to));
  return out.where((g) => g.$2 > g.$1).toList();
}

/// clamp, das bei zu kleinem Platz (hi < lo) nicht wirft, sondern lo liefert.
double _safeClamp(double v, double lo, double hi) => hi < lo ? lo : v.clamp(lo, hi);

const _hourMs = 3600 * 1000;
const _dayMs = 24 * _hourMs;

String _dayLabel(DateTime d) => '${weekdays[d.weekday - 1]} ${d.day}.${d.month}.';

/// Beschriftungs-Kasten der Lupe (oben im Plot, horizontal an der Markierung ausgerichtet).
void _paintReadout(Canvas canvas, Rect plot, double x, String text, TextStyle style, ColorScheme cs) {
  final tp = _label(text, style.copyWith(color: cs.onInverseSurface, fontWeight: FontWeight.w600));
  const padH = 8.0, padV = 4.0;
  final w = tp.width + 2 * padH, h = tp.height + 2 * padV;
  final left = _safeClamp(x - w / 2, plot.left, plot.right - w);
  final r = RRect.fromRectAndRadius(Rect.fromLTWH(left, plot.top, w, h), const Radius.circular(6));
  canvas.drawRRect(r, Paint()..color = cs.inverseSurface);
  tp.paint(canvas, Offset(left + padH, plot.top + padV));
}

void _dashedH(Canvas canvas, double x0, double x1, double y, Paint p, {double dash = 4, double gap = 4}) {
  for (var dx = x0; dx < x1; dx += dash + gap) {
    canvas.drawLine(Offset(dx, y), Offset(math.min(dx + dash, x1), y), p);
  }
}

void _dashedV(Canvas canvas, double x, double y0, double y1, Paint p) {
  for (var dy = y0; dy < y1; dy += 7) {
    canvas.drawLine(Offset(x, dy), Offset(x, math.min(dy + 3.5, y1)), p);
  }
}

/// Graph 1: Akkustand-Verlauf. Fenster 24 Std. oder 7 Tage, per Wischen in die Vergangenheit verschiebbar
/// (24 Std.: bis 7 Tage zurück, 7 Tage: bis 30 Tage zurück). Tippen zeigt den Wert an der Stelle.
class LevelChart extends StatefulWidget {
  const LevelChart({super.key, required this.history, required this.targetPct, required this.week});
  final History history;
  final int targetPct;
  final bool week;

  @override
  State<LevelChart> createState() => _LevelChartState();
}

class _LevelChartState extends State<LevelChart> {
  /// Verschiebung in ms zurück von „jetzt“.
  int _offset = 0;
  double? _scrubX;

  int get _window => widget.week ? 7 * _dayMs : _dayMs;
  int get _maxOffset => math.max(0, widget.history.to - widget.history.levelFrom - _window);

  @override
  void didUpdateWidget(LevelChart old) {
    super.didUpdateWidget(old);
    if (old.week != widget.week) {
      _offset = 0;
      _scrubX = null;
    }
    _offset = _offset.clamp(0, _maxOffset);
  }

  void _shift(int ms) => setState(() {
        _offset = (_offset + ms).clamp(0, _maxOffset);
        _scrubX = null;
      });

  @override
  Widget build(BuildContext context) {
    final h = widget.history;
    final vTo = h.to - _offset;
    final vFrom = vTo - _window;
    final pts = h.levels;
    final last = pts.isEmpty ? null : pts.last;
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final a = DateTime.fromMillisecondsSinceEpoch(vFrom), b = DateTime.fromMillisecondsSinceEpoch(vTo);
    final range = _offset == 0
        ? (widget.week ? 'Letzte 7 Tage' : 'Letzte 24 Stunden')
        : widget.week
            ? '${_dayLabel(a)} – ${_dayLabel(b)}'
            : '${_dayLabel(a)} ${fmtClock(a)} – ${_dayLabel(b)} ${fmtClock(b)}';
    final semantic = last == null
        ? 'Akkustand-Verlauf: noch keine Daten.'
        : 'Akkustand-Verlauf der letzten ${widget.week ? '7 Tage' : '24 Stunden'}: zuletzt ${last.levelPct.round()} Prozent.';
    final step = widget.week ? _dayMs : 6 * _hourMs;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        IconButton(
          visualDensity: VisualDensity.compact,
          tooltip: 'Früher',
          onPressed: _offset < _maxOffset ? () => _shift(step) : null,
          icon: const Icon(Icons.chevron_left),
        ),
        Expanded(child: Text(range, textAlign: TextAlign.center, style: tt.labelLarge)),
        IconButton(
          visualDensity: VisualDensity.compact,
          tooltip: 'Später',
          onPressed: _offset > 0 ? () => _shift(-step) : null,
          icon: const Icon(Icons.chevron_right),
        ),
        if (_offset > 0)
          TextButton(onPressed: () => _shift(-_offset), child: const Text('Jetzt'))
        else
          const SizedBox(width: 4),
      ]),
      Semantics(
        label: semantic,
        excludeSemantics: true,
        child: SizedBox(
          height: 240,
          child: LayoutBuilder(builder: (context, box) {
            final plotW = math.max(1.0, box.maxWidth - _LevelPainter.right);
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              onHorizontalDragUpdate: (d) => _shift((d.delta.dx / plotW * _window).round()),
              onTapDown: (d) => setState(() => _scrubX = d.localPosition.dx < plotW ? d.localPosition.dx : null),
              child: RepaintBoundary(
                child: CustomPaint(
                  size: Size(box.maxWidth, 240),
                  painter: _LevelPainter(h, vFrom, vTo, widget.targetPct, widget.week, _offset == 0, _scrubX, cs,
                      context.battery, tt.labelSmall!),
                ),
              ),
            );
          }),
        ),
      ),
      Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text('Wischen zum Verschieben · Tippen zeigt den Wert',
            style: tt.labelSmall?.copyWith(color: cs.onSurfaceVariant), textAlign: TextAlign.center),
      ),
    ]);
  }
}

class _LevelPainter extends CustomPainter {
  _LevelPainter(this.h, this.vFrom, this.vTo, this.target, this.week, this.atNow, this.scrubX, this.cs, this.bc, this.label);
  final History h;
  final int vFrom, vTo, target;
  final bool week, atNow;
  final double? scrubX;
  final ColorScheme cs;
  final BatteryColors bc;
  final TextStyle label;

  static const right = 36.0, bottom = 20.0;

  @override
  void paint(Canvas canvas, Size size) {
    final ls = label.copyWith(color: cs.onSurfaceVariant);
    final plot = Rect.fromLTWH(0, 8, size.width - right, size.height - bottom - 8);
    final span = (vTo - vFrom).toDouble();
    double x(int t) => plot.left + (t - vFrom) / span * plot.width;
    double y(double pct) => plot.bottom - pct / 100 * plot.height;

    // Waagerechte Hilfslinien alle 10 %, beschriftet alle 20 %.
    final minor = Paint()
      ..color = cs.outlineVariant.withValues(alpha: 0.35)
      ..strokeWidth = 1;
    final major = Paint()
      ..color = cs.outlineVariant.withValues(alpha: 0.8)
      ..strokeWidth = 1;
    for (var v = 0; v <= 100; v += 10) {
      final labelled = v % 20 == 0;
      canvas.drawLine(Offset(plot.left, y(v.toDouble())), Offset(plot.right, y(v.toDouble())), labelled ? major : minor);
      if (labelled) {
        final tp = _label('$v', ls);
        tp.paint(canvas, Offset(plot.right + 6, y(v.toDouble()) - tp.height / 2));
      }
    }

    // Senkrechte Hilfslinien: 24 Std. alle 3 Std. (Mitternacht kräftig), 7 Tage je Tag (12 Uhr fein).
    final startLocal = DateTime.fromMillisecondsSinceEpoch(vFrom);
    var d = DateTime(startLocal.year, startLocal.month, startLocal.day);
    final stepH = week ? 12 : 3;
    while (d.millisecondsSinceEpoch <= vTo) {
      final t = d.millisecondsSinceEpoch;
      if (t >= vFrom) {
        final px = x(t);
        final midnight = d.hour == 0;
        canvas.drawLine(Offset(px, plot.top), Offset(px, plot.bottom), midnight ? major : minor);
        final text = week ? (midnight ? _dayLabel(d) : null) : (midnight ? weekdays[d.weekday - 1] : d.hour.toString().padLeft(2, '0'));
        if (text != null) {
          final tp = _label(text, midnight && !week ? ls.copyWith(fontWeight: FontWeight.w600) : ls);
          final lx = week ? px + 3 : px - tp.width / 2;
          // Rechts Platz für „jetzt“ lassen, sonst überlappen sich die Beschriftungen.
          final limit = atNow ? plot.right - 22 : plot.right + right;
          if (lx >= plot.left - 2 && lx + tp.width <= limit) tp.paint(canvas, Offset(lx, plot.bottom + 5));
        }
      }
      d = DateTime(d.year, d.month, d.day, d.hour + stepH);
    }

    canvas.save();
    canvas.clipRect(plot);

    // Lücken
    for (final g in gapsIn(vFrom, vTo, h.coverage)) {
      paintHatch(canvas, Rect.fromLTRB(x(g.$1), plot.top, x(g.$2), plot.bottom), bc.gap);
    }

    // Ladeziel gestrichelt
    _dashedH(canvas, plot.left, plot.right, y(target.toDouble()), Paint()
      ..color = cs.outline
      ..strokeWidth = 1);

    // Linie: Segmente je Span (innerhalb eines Spans immer verbunden, Lücken nur zwischen Spans).
    final bucket = week ? 1200000 : 180000;
    final pts = h.levels.where((p) => p.bucketTs + bucket >= vFrom - bucket && p.bucketTs <= vTo + bucket).toList()
      ..sort((a, b) => a.bucketTs.compareTo(b.bucketTs));
    final bySpan = <int, List<LevelPoint>>{};
    for (final p in pts) {
      bySpan.putIfAbsent(p.span, () => []).add(p);
    }
    final line = Paint()
      ..strokeWidth = 2.5
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;
    final fill = Paint()..color = bc.charge.withValues(alpha: 0.14);
    for (final list in bySpan.values) {
      for (var i = 0; i < list.length; i++) {
        final a = list[i];
        final ax = x(a.bucketTs + bucket ~/ 2), ay = y(a.levelPct);
        if (list.length == 1) canvas.drawCircle(Offset(ax, ay), 3, Paint()..color = a.plugged ? bc.charge : cs.primary);
        if (i + 1 >= list.length) continue;
        final b = list[i + 1];
        final bx = x(b.bucketTs + bucket ~/ 2), by = y(b.levelPct);
        final charging = a.plugged && b.plugged;
        if (charging) {
          canvas.drawPath(
            Path()
              ..moveTo(ax, plot.bottom)
              ..lineTo(ax, ay)
              ..lineTo(bx, by)
              ..lineTo(bx, plot.bottom)
              ..close(),
            fill,
          );
        }
        canvas.drawLine(Offset(ax, ay), Offset(bx, by), line..color = charging ? bc.charge : cs.primary);
      }
    }
    canvas.restore();

    canvas.drawLine(Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), Paint()
      ..color = cs.outline
      ..strokeWidth = 1);
    if (atNow) {
      final now = _label('jetzt', ls);
      now.paint(canvas, Offset(plot.right - now.width / 2, plot.bottom + 5));
    }

    // Lupe: Wert an der getippten Stelle
    final sx = scrubX;
    if (sx != null && sx >= plot.left && sx <= plot.right) {
      final t = vFrom + ((sx - plot.left) / plot.width * span).round();
      LevelPoint? best;
      for (final p in pts) {
        final c = p.bucketTs + bucket ~/ 2;
        if ((c - t).abs() <= 3 * bucket && (best == null || (c - t).abs() < (best.bucketTs + bucket ~/ 2 - t).abs())) best = p;
      }
      final at = DateTime.fromMillisecondsSinceEpoch(t);
      _dashedV(canvas, sx, plot.top, plot.bottom, Paint()
        ..color = cs.onSurface
        ..strokeWidth = 1);
      String text;
      if (best == null) {
        text = '${weekdays[at.weekday - 1]} ${fmtClock(at)} · keine Daten';
      } else {
        final p = best;
        canvas.drawCircle(Offset(sx, y(p.levelPct)), 5, Paint()..color = p.plugged ? bc.charge : cs.primary);
        canvas.drawCircle(Offset(sx, y(p.levelPct)), 5, Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2
          ..color = cs.surface);
        final w = p.plugged && p.powerAvgMw != null && p.powerAvgMw! > 0 ? ' · ${fmtWatts(p.powerAvgMw!.round())}' : '';
        text = '${weekdays[at.weekday - 1]} ${fmtClock(at)} · ${p.levelPct.round()}$nnbsp%$w';
      }
      _paintReadout(canvas, plot, sx, text, label, cs);
    }
  }

  @override
  bool shouldRepaint(_LevelPainter o) =>
      !identical(o.h, h) || o.vFrom != vFrom || o.vTo != vTo || o.target != target || o.week != week || o.scrubX != scrubX || o.cs != cs;
}

/// Graph 2: ein Ladevorgang über der Zeit. Oben die Ladeleistung als Fläche (W), darunter der Akkustand (%),
/// beide mit derselben Zeitachse. Markiert, wann das Ladeziel erreicht wurde. Tippen/Wischen zeigt Werte.
class PowerChart extends StatefulWidget {
  const PowerChart({super.key, required this.session, required this.targetPct});
  final ChargeSessionInfo session;
  final int targetPct;

  @override
  State<PowerChart> createState() => _PowerChartState();
}

class _PowerChartState extends State<PowerChart> {
  double? _scrubX;

  @override
  void didUpdateWidget(PowerChart old) {
    super.didUpdateWidget(old);
    if (old.session.id != widget.session.id) _scrubX = null;
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.session;
    final peak = s.smoothPeakMw ?? s.peakMw;
    return Semantics(
      label: 'Ladeleistung des Ladevorgangs von ${fmtClockUtc(s.startTs)}${peak != null ? ', maximal ${(peak / 1000).round()} Watt' : ''}.',
      excludeSemantics: true,
      child: SizedBox(
        height: 268,
        child: LayoutBuilder(builder: (context, box) {
          void scrub(Offset p) => setState(() => _scrubX = _safeClamp(p.dx, 0, box.maxWidth - _PowerPainter.right));
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: (d) => scrub(d.localPosition),
            onHorizontalDragStart: (d) => scrub(d.localPosition),
            onHorizontalDragUpdate: (d) => scrub(d.localPosition),
            child: RepaintBoundary(
              child: CustomPaint(
                size: Size(box.maxWidth, 268),
                painter: _PowerPainter(s, widget.targetPct, _scrubX, Theme.of(context).colorScheme, context.battery,
                    Theme.of(context).textTheme.labelSmall!),
              ),
            ),
          );
        }),
      ),
    );
  }
}

double niceWatts(double w) {
  for (final n in const [5, 10, 15, 20, 25, 30, 40, 50, 65, 80, 100, 120, 150]) {
    if (w <= n) return n.toDouble();
  }
  return 150;
}

/// Achsenschritt für Watt: 3–5 Linien.
double _wattStep(double top) {
  for (final s in const [1.0, 2.0, 2.5, 5.0, 10.0, 20.0, 25.0, 50.0]) {
    if (top / s <= 5) return s;
  }
  return 50;
}

String _elapsed(int minutes) {
  if (minutes == 0) return '0';
  if (minutes < 60) return '$minutes${nnbsp}Min.';
  final h = minutes ~/ 60, m = minutes % 60;
  return m == 0 ? '$h${nnbsp}Std.' : '$h:${m.toString().padLeft(2, '0')}${nnbsp}Std.';
}

class _PowerPainter extends CustomPainter {
  _PowerPainter(this.s, this.target, this.scrubX, this.cs, this.bc, this.label);
  final ChargeSessionInfo s;
  final int target;
  final double? scrubX;
  final ColorScheme cs;
  final BatteryColors bc;
  final TextStyle label;

  static const right = 40.0;

  @override
  void paint(Canvas canvas, Size size) {
    final ls = label.copyWith(color: cs.onSurfaceVariant);
    final pts = [...s.points]..sort((a, b) => a.tOffS.compareTo(b.tOffS));
    if (pts.isEmpty) return;
    final w = size.width - right;
    final topPlot = Rect.fromLTWH(0, 30, w, 140);
    final lvPlot = Rect.fromLTWH(0, topPlot.bottom + 14, w, 64);
    final total = math.max(60, pts.last.tOffS).toDouble();
    double x(num t) => t / total * w;

    // Leistung glätten: gleitender Median über 3 Punkte (einzelne Messspitzen verfälschen sonst die Form).
    final raw = pts.map((p) => p.powerMw).toList();
    final watts = <double?>[];
    for (var i = 0; i < raw.length; i++) {
      final win = [for (var j = i - 1; j <= i + 1; j++) if (j >= 0 && j < raw.length && raw[j] != null && raw[j]! >= 0) raw[j]!]..sort();
      watts.add(raw[i] == null || raw[i]! < 0 || win.isEmpty ? null : win[win.length ~/ 2] / 1000.0);
    }
    var maxW = 5.0;
    for (final v in watts) {
      if (v != null) maxW = math.max(maxW, v);
    }
    final top = niceWatts(maxW * 1.08);
    double yW(double v) => topPlot.bottom - (v.clamp(0, top) / top) * topPlot.height;
    double yL(double pct) => lvPlot.bottom - pct / 100 * lvPlot.height;

    final grid = Paint()
      ..color = cs.outlineVariant.withValues(alpha: 0.6)
      ..strokeWidth = 1;
    final axis = Paint()
      ..color = cs.outline
      ..strokeWidth = 1;

    // Watt-Gitter
    final ws = _wattStep(top);
    for (var v = 0.0; v <= top + 0.01; v += ws) {
      canvas.drawLine(Offset(0, yW(v)), Offset(w, yW(v)), v == 0 ? axis : grid);
      final t = v == 0 ? '0' : '${v == v.roundToDouble() ? v.round() : v.toString().replaceAll('.', ',')}${nnbsp}W';
      final tp = _label(t, ls);
      tp.paint(canvas, Offset(w + 6, yW(v) - tp.height / 2));
    }
    // Akkustand-Gitter
    for (final v in const [0, 50, 100]) {
      canvas.drawLine(Offset(0, yL(v.toDouble())), Offset(w, yL(v.toDouble())), v == 0 ? axis : grid);
      final tp = _label('$v$nnbsp%', ls);
      tp.paint(canvas, Offset(w + 6, yL(v.toDouble()) - tp.height / 2));
    }

    // Zeitachse: 3–6 Markierungen in runden Schritten
    final minutes = total / 60;
    final stepMin = const [5, 10, 15, 20, 30, 60, 90, 120, 180, 240].firstWhere((m) => minutes / m <= 5, orElse: () => 360);
    for (var m = 0; m <= minutes; m += stepMin) {
      final px = x(m * 60);
      canvas.drawLine(Offset(px, topPlot.top), Offset(px, topPlot.bottom), grid..color = cs.outlineVariant.withValues(alpha: 0.3));
      canvas.drawLine(Offset(px, lvPlot.top), Offset(px, lvPlot.bottom), grid);
      grid.color = cs.outlineVariant.withValues(alpha: 0.6);
      final tp = _label(_elapsed(m), ls);
      final lx = _safeClamp(px - tp.width / 2, 0, w - tp.width);
      tp.paint(canvas, Offset(lx, lvPlot.bottom + 5));
    }

    // Leistung als Fläche (Segmente ohne Lücken)
    final strokeW = Paint()
      ..color = bc.charge
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke
      ..strokeJoin = StrokeJoin.round;
    final fillW = Paint()
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [bc.charge.withValues(alpha: 0.35), bc.charge.withValues(alpha: 0.04)],
      ).createShader(topPlot);
    var seg = <Offset>[];
    void flush() {
      if (seg.length >= 2) {
        final line = Path()..moveTo(seg.first.dx, seg.first.dy);
        for (final o in seg.skip(1)) {
          line.lineTo(o.dx, o.dy);
        }
        final area = Path.from(line)
          ..lineTo(seg.last.dx, topPlot.bottom)
          ..lineTo(seg.first.dx, topPlot.bottom)
          ..close();
        canvas.drawPath(area, fillW);
        canvas.drawPath(line, strokeW);
      } else if (seg.length == 1) {
        canvas.drawCircle(seg.first, 2.5, Paint()..color = bc.charge);
      }
      seg = <Offset>[];
    }

    for (var i = 0; i < pts.length; i++) {
      final v = watts[i];
      if (v == null) {
        flush();
        continue;
      }
      seg.add(Offset(x(pts[i].tOffS), yW(v)));
    }
    flush();

    // Akkustand-Linie
    final lvPath = Path();
    for (var i = 0; i < pts.length; i++) {
      final o = Offset(x(pts[i].tOffS), yL(pts[i].levelPm / 10));
      i == 0 ? lvPath.moveTo(o.dx, o.dy) : lvPath.lineTo(o.dx, o.dy);
    }
    canvas.drawPath(lvPath, Paint()
      ..color = cs.primary
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke
      ..strokeJoin = StrokeJoin.round);

    // Ladeziel: gestrichelt im Akkustand; senkrecht markiert, wann es erreicht wurde.
    final dash = Paint()
      ..color = cs.outline
      ..strokeWidth = 1;
    _dashedH(canvas, 0, w, yL(target.toDouble()), dash);
    final reached = pts.where((p) => p.levelPm >= target * 10).firstOrNull;
    if (reached != null && pts.first.levelPm < target * 10) {
      final px = x(reached.tOffS);
      _dashedV(canvas, px, topPlot.top, lvPlot.bottom, dash);
      final tp = _label('${fmtPct(target)} nach ${_elapsed((reached.tOffS / 60).round())}', ls);
      final lx = (px + 4 + tp.width > w) ? px - 4 - tp.width : px + 4;
      tp.paint(canvas, Offset(lx, lvPlot.top - tp.height - 1));
    }

    // Lupe
    final sx = scrubX;
    if (sx != null) {
      final t = sx / w * total;
      var bi = 0;
      for (var i = 1; i < pts.length; i++) {
        if ((pts[i].tOffS - t).abs() < (pts[bi].tOffS - t).abs()) bi = i;
      }
      final p = pts[bi];
      final px = x(p.tOffS);
      _dashedV(canvas, px, topPlot.top, lvPlot.bottom, Paint()
        ..color = cs.onSurface
        ..strokeWidth = 1);
      final v = watts[bi];
      if (v != null) canvas.drawCircle(Offset(px, yW(v)), 4.5, Paint()..color = bc.charge);
      canvas.drawCircle(Offset(px, yL(p.levelPm / 10)), 4.5, Paint()..color = cs.primary);
      final clock = fmtClockUtc(s.startTs + p.tOffS * 1000);
      final text = '$clock (+${_elapsed((p.tOffS / 60).round())}) · ${v == null ? '–' : fmtWatts((v * 1000).round())} · ${fmtPct(p.levelPm ~/ 10)}';
      _paintReadout(canvas, Rect.fromLTWH(0, 0, w, topPlot.bottom), px, text, label, cs);
    } else {
      final tp = _label('Ladeleistung', ls.copyWith(fontWeight: FontWeight.w600));
      tp.paint(canvas, Offset(0, 6));
      final hint = _label('Tippen oder wischen für Werte', ls);
      hint.paint(canvas, Offset(w - hint.width, 6));
    }
  }

  @override
  bool shouldRepaint(_PowerPainter o) => !identical(o.s, s) || o.target != target || o.scrubX != scrubX || o.cs != cs;
}

/// Graph 4: Kapazität über die Zeit (Punkte = Messungen beim Laden), Trendlinie und 80-%-Grenze.
class HealthChart extends StatelessWidget {
  const HealthChart({super.key, required this.points, required this.trend});
  final List<HealthPoint> points;
  final HealthTrend trend;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Kapazitätsverlauf: ${points.length} Messungen, aktuell etwa ${trend.nowMah.round()} Milliamperestunden.',
      excludeSemantics: true,
      child: RepaintBoundary(
        child: SizedBox(
          height: 150,
          width: double.infinity,
          child: CustomPaint(
            painter: _HealthPainter(points, trend, Theme.of(context).colorScheme, context.battery,
                Theme.of(context).textTheme.labelSmall!),
          ),
        ),
      ),
    );
  }
}

class _HealthPainter extends CustomPainter {
  _HealthPainter(this.pts, this.trend, this.cs, this.bc, this.label);
  final List<HealthPoint> pts;
  final HealthTrend trend;
  final ColorScheme cs;
  final BatteryColors bc;
  final TextStyle label;

  @override
  void paint(Canvas canvas, Size size) {
    if (pts.isEmpty) return;
    final ls = label.copyWith(color: cs.onSurfaceVariant);
    const right = 44.0, bottom = 20.0;
    final plot = Rect.fromLTWH(0, 8, size.width - right, size.height - bottom - 8);
    final sorted = [...pts]..sort((a, b) => a.ts.compareTo(b.ts));
    final ref = trend.designMah ?? sorted.first.capacityMah;
    final t0 = sorted.first.ts;
    final t1 = math.max(sorted.last.ts, t0 + 7 * _dayMs);
    var lo = 0.75 * ref, hi = 1.05 * ref;
    for (final p in sorted) {
      lo = math.min(lo, p.capacityMah * 0.98);
      hi = math.max(hi, p.capacityMah * 1.02);
    }
    double x(int t) => plot.left + (t - t0) / (t1 - t0) * plot.width;
    double y(double mah) => plot.bottom - (mah - lo) / (hi - lo) * plot.height;

    final grid = Paint()
      ..color = cs.outlineVariant.withValues(alpha: 0.6)
      ..strokeWidth = 1;
    for (final f in const [0.8, 0.9, 1.0]) {
      final yy = y(ref * f);
      if (yy < plot.top || yy > plot.bottom) continue;
      if (f == 0.8) {
        _dashedH(canvas, plot.left, plot.right, yy, Paint()
          ..color = bc.hot
          ..strokeWidth = 1);
      } else {
        canvas.drawLine(Offset(plot.left, yy), Offset(plot.right, yy), grid);
      }
      final tp = _label('${(f * 100).round()}$nnbsp%', f == 0.8 ? ls.copyWith(color: bc.hot) : ls);
      tp.paint(canvas, Offset(plot.right + 6, yy - tp.height / 2));
    }
    canvas.drawLine(Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), Paint()
      ..color = cs.outline
      ..strokeWidth = 1);

    // Monatsbeschriftung (bei kurzer Dauer: Tage)
    final spanDays = (t1 - t0) / _dayMs;
    final start = DateTime.fromMillisecondsSinceEpoch(t0);
    if (spanDays > 45) {
      var d = DateTime(start.year, start.month + 1);
      while (d.millisecondsSinceEpoch <= t1) {
        final px = x(d.millisecondsSinceEpoch);
        canvas.drawLine(Offset(px, plot.bottom), Offset(px, plot.bottom + 3), grid);
        const months = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];
        final tp = _label(months[d.month - 1], ls);
        if (px + tp.width < plot.right) tp.paint(canvas, Offset(px + 2, plot.bottom + 5));
        d = DateTime(d.year, d.month + 1);
      }
    } else {
      final a = _label(_dayLabel(start), ls);
      a.paint(canvas, Offset(plot.left, plot.bottom + 5));
      final b = _label(_dayLabel(DateTime.fromMillisecondsSinceEpoch(sorted.last.ts)), ls);
      b.paint(canvas, Offset(plot.right - b.width, plot.bottom + 5));
    }

    // Trendlinie
    final slope = trend.slopeMahPerDay;
    if (slope != null) {
      final last = sorted.last.ts;
      double at(int t) => trend.nowMah + slope * (t - last) / _dayMs;
      canvas.drawLine(Offset(x(t0), y(at(t0))), Offset(x(t1), y(at(t1))), Paint()
        ..color = cs.primary.withValues(alpha: 0.8)
        ..strokeWidth = 2);
    }
    // Messpunkte
    final dot = Paint()..color = cs.primary.withValues(alpha: 0.45);
    for (final p in sorted) {
      canvas.drawCircle(Offset(x(p.ts), y(p.capacityMah)), 3, dot);
    }
  }

  @override
  bool shouldRepaint(_HealthPainter o) => !identical(o.pts, pts) || o.cs != cs;
}

/// Graph 3: Entladerate – zwei direkt beschriftete Balken (Bildschirm an / aus).
class DrainBars extends StatelessWidget {
  const DrainBars({super.key, required this.drain});
  final DrainStats drain;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    if (drain.onlyAverage) {
      return _row(context, 'Ø zwischen Ladevorgängen', drain.avgPctPerH, drain.avgPctPerH ?? 1, filled: true);
    }
    final maxV = math.max(drain.onPctPerH ?? 0, drain.offPctPerH ?? 0);
    final scale = maxV <= 0 ? 1.0 : maxV;
    final standby = drain.offPctPerH;
    return Semantics(
      label: 'Entladerate: Bildschirm an ${drain.onPctPerH?.toStringAsFixed(1) ?? 'unbekannt'} Prozent pro Stunde, '
          'Bildschirm aus ${drain.offPctPerH?.toStringAsFixed(1) ?? 'unbekannt'} Prozent pro Stunde.',
      excludeSemantics: true,
      child: Column(children: [
        _row(context, 'Bildschirm an', drain.onPctPerH, scale, filled: true),
        const SizedBox(height: 8),
        _row(context, 'Bildschirm aus', drain.offPctPerH, scale, filled: false),
        if (standby != null && standby > 0.05) ...[
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: Text('≈ ${_standby(standby)} Standby', style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
          ),
        ],
      ]),
    );
  }

  static String _standby(double rate) {
    final h = 100 / rate;
    return h >= 48 ? '${(h / 24).round()} Tage' : '${h.round()} Std.';
  }

  Widget _row(BuildContext context, String label, double? v, double scale, {required bool filled}) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return SizedBox(
      height: 28,
      child: Row(children: [
        SizedBox(width: 120, child: Text(label, style: tt.bodyMedium)),
        Expanded(
          child: LayoutBuilder(builder: (c, box) {
            final w = v == null ? 0.0 : (v / scale).clamp(0.02, 1.0) * box.maxWidth;
            return Align(
              alignment: Alignment.centerLeft,
              child: Container(
                width: w,
                height: 12,
                decoration: BoxDecoration(
                  color: filled ? cs.primary : cs.primaryContainer,
                  border: filled ? null : Border.all(color: cs.primary, width: 1.5),
                  borderRadius: BorderRadius.circular(6),
                ),
              ),
            );
          }),
        ),
        SizedBox(
          width: 70,
          child: Text(v == null ? '–' : fmtRate(v), textAlign: TextAlign.right, style: tt.bodyMedium),
        ),
      ]),
    );
  }
}
