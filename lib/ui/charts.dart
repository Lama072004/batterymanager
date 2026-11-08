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

/// Graph 1: Akkustand-Verlauf (24 Std. / 7 Tage).
class LevelChart extends StatelessWidget {
  const LevelChart({super.key, required this.history, required this.targetPct, required this.week});
  final History history;
  final int targetPct;
  final bool week;

  @override
  Widget build(BuildContext context) {
    final pts = history.levels;
    final last = pts.isEmpty ? null : pts.last;
    final semantic = last == null
        ? 'Akkustand-Verlauf: noch keine Daten.'
        : 'Akkustand-Verlauf der letzten ${week ? '7 Tage' : '24 Stunden'}: zuletzt ${last.levelPct.round()} Prozent.';
    return Semantics(
      label: semantic,
      excludeSemantics: true,
      child: RepaintBoundary(
        child: SizedBox(
          height: 160,
          width: double.infinity,
          child: CustomPaint(
            painter: _LevelPainter(history, targetPct, week, Theme.of(context).colorScheme, context.battery,
                Theme.of(context).textTheme.labelSmall!),
          ),
        ),
      ),
    );
  }
}

class _LevelPainter extends CustomPainter {
  _LevelPainter(this.h, this.target, this.week, this.cs, this.bc, this.label);
  final History h;
  final int target;
  final bool week;
  final ColorScheme cs;
  final BatteryColors bc;
  final TextStyle label;

  @override
  void paint(Canvas canvas, Size size) {
    final ls = label.copyWith(color: cs.onSurfaceVariant);
    const right = 30.0, bottom = 18.0;
    final plot = Rect.fromLTWH(0, 6, size.width - right, size.height - bottom - 6);
    final span = (h.to - h.from).toDouble();
    double x(int t) => plot.left + (t - h.from) / span * plot.width;
    double y(double pct) => plot.bottom - pct / 100 * plot.height;

    // Gitter bei 50 %, Achsenlinie unten
    final grid = Paint()
      ..color = cs.outlineVariant
      ..strokeWidth = 1;
    canvas.drawLine(Offset(plot.left, y(50)), Offset(plot.right, y(50)), grid..color = cs.outlineVariant.withValues(alpha: 0.5));
    canvas.drawLine(Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), grid..color = cs.outlineVariant);
    for (final (v, t) in [(100, '100'), (50, '50'), (0, '0')]) {
      final tp = _label(t, ls);
      tp.paint(canvas, Offset(plot.right + 6, y(v.toDouble()) - tp.height / 2));
    }

    // Lücken
    for (final g in gapsIn(h.from, h.to, h.coverage)) {
      paintHatch(canvas, Rect.fromLTRB(x(g.$1), plot.top, x(g.$2), plot.bottom), bc.gap);
    }

    // Ladeziel gestrichelt
    final dash = Paint()
      ..color = cs.outline
      ..strokeWidth = 1;
    for (var dx = plot.left; dx < plot.right; dx += 8) {
      canvas.drawLine(Offset(dx, y(target.toDouble())), Offset(math.min(dx + 4, plot.right), y(target.toDouble())), dash);
    }

    // Linie: Segmente je Span, Bruch bei Lücken > 3 Buckets
    final bucket = week ? 1200000 : 180000;
    final pts = [...h.levels]..sort((a, b) => a.bucketTs.compareTo(b.bucketTs));
    final bySpan = <int, List<LevelPoint>>{};
    for (final p in pts) {
      bySpan.putIfAbsent(p.span, () => []).add(p);
    }
    final line = Paint()
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;
    final fill = Paint()..color = bc.charge.withValues(alpha: 0.12);
    for (final list in bySpan.values) {
      for (var i = 0; i < list.length; i++) {
        final a = list[i];
        final ax = x(a.bucketTs + bucket ~/ 2), ay = y(a.levelPct);
        final isolated = (i == 0 || list[i].bucketTs - list[i - 1].bucketTs > 3 * bucket) &&
            (i == list.length - 1 || list[i + 1].bucketTs - a.bucketTs > 3 * bucket);
        if (isolated) {
          canvas.drawCircle(Offset(ax, ay), 3, Paint()..color = a.plugged ? bc.charge : cs.primary);
        }
        if (i + 1 >= list.length) continue;
        final b = list[i + 1];
        if (b.bucketTs - a.bucketTs > 3 * bucket) continue;
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

    // x-Beschriftung
    final from = DateTime.fromMillisecondsSinceEpoch(h.from);
    if (week) {
      var d = DateTime(from.year, from.month, from.day + 1);
      while (d.millisecondsSinceEpoch < h.to - 3 * 3600 * 1000) {
        final tp = _label(weekdays[d.weekday - 1], ls);
        final px = x(d.millisecondsSinceEpoch);
        canvas.drawLine(Offset(px, plot.bottom), Offset(px, plot.bottom + 3), grid);
        tp.paint(canvas, Offset(px + 3, plot.bottom + 4));
        d = DateTime(d.year, d.month, d.day + 1);
      }
    } else {
      var d = DateTime(from.year, from.month, from.day, (from.hour ~/ 6 + 1) * 6);
      while (d.millisecondsSinceEpoch < h.to - 2 * 3600 * 1000) {
        final tp = _label(d.hour.toString().padLeft(2, '0'), ls);
        final px = x(d.millisecondsSinceEpoch);
        canvas.drawLine(Offset(px, plot.bottom), Offset(px, plot.bottom + 3), grid);
        tp.paint(canvas, Offset(px - tp.width / 2, plot.bottom + 4));
        d = d.add(const Duration(hours: 6));
      }
    }
    final now = _label('jetzt', ls);
    now.paint(canvas, Offset(plot.right - now.width, plot.bottom + 4));
  }

  @override
  bool shouldRepaint(_LevelPainter o) =>
      !identical(o.h, h) || o.target != target || o.week != week || o.cs != cs;
}

/// Graph 2: Ladeleistung über dem Akkustand (x = %, y = W) für einen Ladevorgang.
class PowerChart extends StatelessWidget {
  const PowerChart({super.key, required this.sessions, required this.selected});
  final List<ChargeSessionInfo> sessions;
  final int selected;

  @override
  Widget build(BuildContext context) {
    final s = sessions.isEmpty ? null : sessions[selected];
    final peak = s?.peakMw;
    return Semantics(
      label: s == null
          ? 'Ladeleistung: noch kein Ladevorgang.'
          : 'Ladeleistung des Ladevorgangs von ${fmtClockUtc(s.startTs)}${peak != null ? ', maximal ${(peak / 1000).round()} Watt' : ''}.',
      excludeSemantics: true,
      child: RepaintBoundary(
        child: SizedBox(
          height: 140,
          width: double.infinity,
          child: CustomPaint(
            painter: _PowerPainter(sessions, selected, Theme.of(context).colorScheme, context.battery,
                Theme.of(context).textTheme.labelSmall!),
          ),
        ),
      ),
    );
  }
}

double niceWatts(double w) {
  for (final n in const [5, 10, 15, 25, 45, 65, 100, 150]) {
    if (w <= n) return n.toDouble();
  }
  return 150;
}

class _PowerPainter extends CustomPainter {
  _PowerPainter(this.sessions, this.selected, this.cs, this.bc, this.label);
  final List<ChargeSessionInfo> sessions;
  final int selected;
  final ColorScheme cs;
  final BatteryColors bc;
  final TextStyle label;

  @override
  void paint(Canvas canvas, Size size) {
    final ls = label.copyWith(color: cs.onSurfaceVariant);
    const right = 34.0, bottom = 18.0;
    final plot = Rect.fromLTWH(0, 6, size.width - right, size.height - bottom - 6);
    var maxW = 5.0;
    for (final s in sessions) {
      for (final p in s.points) {
        if (p.powerMw != null) maxW = math.max(maxW, p.powerMw! / 1000);
      }
    }
    final top = niceWatts(maxW);
    double x(double pct) => plot.left + pct / 100 * plot.width;
    double y(double w) => plot.bottom - (w.clamp(0, top) / top) * plot.height;

    final grid = Paint()
      ..color = cs.outlineVariant
      ..strokeWidth = 1;
    canvas.drawLine(Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), grid);
    for (final (v, t) in [(top, '${top.round()}${nnbsp}W'), (0.0, '0')]) {
      final tp = _label(t, ls);
      tp.paint(canvas, Offset(plot.right + 6, y(v) - tp.height / 2));
    }
    for (final (pct, t) in [(0.0, '0$nnbsp%'), (50.0, '50$nnbsp%'), (100.0, '100$nnbsp%')]) {
      final tp = _label(t, ls);
      final dx = (x(pct) - tp.width / 2).clamp(0.0, plot.right - tp.width);
      tp.paint(canvas, Offset(dx, plot.bottom + 4));
    }

    void drawSession(ChargeSessionInfo s, Paint p, {bool marker = false}) {
      Offset? prev;
      for (final pt in s.points) {
        final w = pt.powerMw;
        if (w == null || w < 0) {
          prev = null;
          continue;
        }
        final o = Offset(x(pt.levelPm / 10), y(w / 1000));
        if (prev != null && (o.dx - prev.dx).abs() < plot.width * 0.2) canvas.drawLine(prev, o, p);
        prev = o;
      }
      if (marker && !s.startKnown && s.points.isNotEmpty && s.points.first.powerMw != null) {
        final f = s.points.first;
        final o = Offset(x(f.levelPm / 10), y(f.powerMw! / 1000));
        canvas.drawCircle(o, 4, Paint()..color = cs.surfaceContainerLow);
        canvas.drawCircle(o, 4, Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2
          ..color = bc.charge);
      }
    }

    for (var i = 0; i < sessions.length && i <= 3; i++) {
      if (i == selected) continue;
      drawSession(sessions[i], Paint()
        ..color = cs.outlineVariant
        ..strokeWidth = 1.5);
    }
    if (sessions.isNotEmpty) {
      drawSession(
          sessions[selected],
          Paint()
            ..color = bc.charge
            ..strokeWidth = 2
            ..strokeCap = StrokeCap.round,
          marker: true);
    }
  }

  @override
  bool shouldRepaint(_PowerPainter o) => !identical(o.sessions, sessions) || o.selected != selected || o.cs != cs;
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
