import 'package:flutter/material.dart';

import '../core/controller.dart';
import '../core/format.dart';
import '../core/model.dart';
import '../ui/charts.dart';
import '../ui/theme.dart';
import 'settings.dart';

/// Einziger Haupt-Screen: Banner · Hero · Werte · drei Graphen (design.md, Advocate/Artisan).
class OverviewScreen extends StatelessWidget {
  const OverviewScreen({super.key, required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final live = c.live;
        final tt = Theme.of(context).textTheme;
        return Scaffold(
          body: SafeArea(
            child: live == null
                ? const SizedBox.shrink()
                : ListView(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                    children: [
                      Row(children: [
                        Expanded(child: Text('Batterymanager', style: tt.titleLarge)),
                        IconButton(
                          tooltip: 'Einstellungen',
                          icon: const Icon(Icons.settings_outlined),
                          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => SettingsScreen(c: c))),
                        ),
                      ]),
                      _Banner(c: c),
                      const SizedBox(height: 8),
                      _Hero(c: c, live: live),
                      const SizedBox(height: 28),
                      _Values(live: live),
                      const SizedBox(height: 28),
                      Align(
                        alignment: Alignment.centerLeft,
                        child: SegmentedButton<bool>(
                          showSelectedIcon: false,
                          segments: const [
                            ButtonSegment(value: false, label: Text('24 Std.')),
                            ButtonSegment(value: true, label: Text('7 Tage')),
                          ],
                          selected: {c.week},
                          onSelectionChanged: (s) => c.setWeek(s.first),
                        ),
                      ),
                      const SizedBox(height: 16),
                      _LevelSection(c: c),
                      const SizedBox(height: 24),
                      _PowerSection(c: c),
                      const SizedBox(height: 24),
                      _DrainSection(c: c),
                    ],
                  ),
          ),
        );
      },
    );
  }
}

// ---------------- Banner (höchstens einer, Rangfolge laut Design) ----------------

class _Banner extends StatelessWidget {
  const _Banner({required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final s = c.state;
    final live = c.live;
    String? text;
    String? action;
    VoidCallback? onTap;
    if (c.stale) {
      text = 'Der Hintergrunddienst antwortet nicht in der erwarteten Version. Bitte App neu installieren.';
    } else if (!c.status.notificationsEnabled) {
      text = 'Benachrichtigungen sind aus – ohne sie gibt es keine Lade-Info.';
      action = 'Einschalten';
      onTap = () async {
        final ok = await c.backend.requestNotificationPermission();
        if (!ok) await c.backend.openNotificationSettings();
        await c.refreshStatus();
      };
    } else if (s != null && s.onboarded && s.mode == AppState.modeAlways && !(live?.serviceRunning ?? true)) {
      text = 'Android hat den Hintergrunddienst beendet. Es fehlen Daten.';
      action = 'Neu starten';
      onTap = () async {
        await c.backend.startService();
        await c.refreshAll();
      };
    } else if (s != null && s.mode == AppState.modeChargingOnly && !c.status.ignoringBatteryOptimizations) {
      text = '„Nur beim Laden“ braucht die Ausnahme von der Akku-Optimierung. Bis dahin gibt es Lade-Info nur bei geöffneter App.';
      action = 'Erlauben';
      onTap = () async {
        await c.backend.requestIgnoreBatteryOptimizations();
        await c.refreshStatus();
      };
    } else if (live != null && live.charging && live.alarmMuted) {
      text = 'Ladeziel-Alarm ist für diesen Ladevorgang stumm.';
    }
    if (text == null) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
        decoration: BoxDecoration(color: cs.surfaceContainerHigh, borderRadius: BorderRadius.circular(12)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(Icons.info_outline, size: 20, color: cs.onSurfaceVariant),
            const SizedBox(width: 12),
            Expanded(child: Text(text, style: Theme.of(context).textTheme.bodyMedium)),
          ]),
          if (action != null)
            Align(alignment: Alignment.centerRight, child: TextButton(onPressed: onTap, child: Text(action))),
        ]),
      ),
    );
  }
}

// ---------------- Hero ----------------

class _Hero extends StatelessWidget {
  const _Hero({required this.c, required this.live});
  final AppController c;
  final Live live;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final bc = context.battery;
    final heroScale = MediaQuery.textScalerOf(context).clamp(maxScaleFactor: 1.5);
    final w = live.powerMw;
    final ca = live.estimated ? 'ca. ' : '';

    String sub;
    IconData icon;
    Color color;
    if (live.charging) {
      icon = Icons.bolt;
      color = bc.charge;
      sub = switch (live.etaState) {
        'computing' => 'Lädt · Leistung wird ermittelt …',
        'deficit' => 'Eingesteckt, entlädt trotzdem${w != null ? ' (${fmtWatts(w)})' : ''}',
        'pause' => 'Ladepause',
        'full' => 'Voll geladen',
        _ => w != null && w > 0 ? 'Lädt mit $ca${fmtWatts(w)}' : 'Lädt',
      };
    } else {
      icon = Icons.south_east;
      color = cs.primary;
      final rate = c.history?.drain.avgPctPerH;
      sub = rate != null ? 'Entlädt · ${fmtRate(rate)}' : 'Entlädt';
    }

    final etaLines = <String>[];
    if (live.charging) {
      final step = live.etaState == 'ok' ? 5 : 15;
      final pre = live.etaState == 'ok' ? '' : 'ca. ';
      if (live.etaState == 'ok' || live.etaState == 'approx') {
        if (live.etaTargetS != null && live.pct < live.targetPct) {
          etaLines.add('${fmtPct(live.targetPct)} in $pre${fmtDuration(live.etaTargetS!, step: step)}');
        }
        if (live.etaFullS != null && live.pct < 100) etaLines.add('Voll in $pre${fmtDuration(live.etaFullS!, step: step)}');
      } else if (live.etaState == 'computing') {
        etaLines.add('Restzeit wird berechnet …');
      } else if (live.etaState == 'none') {
        etaLines.add('Restzeit: —');
      } else if (live.etaState == 'pause') {
        etaLines.add('Gerät pausiert das Laden (Limit/adaptiv).');
      } else if (live.etaState == 'full' && live.fullSinceUtc != null) {
        etaLines.add('Seit ${fmtClockUtc(live.fullSinceUtc!)} bei 100$nnbsp%');
      }
      if (!live.startKnown && live.sessionStartUtc != null) etaLines.add('Erfasst seit ${fmtClockUtc(live.sessionStartUtc!)}');
    }

    final heat = live.tempDc != null && live.tempDc! >= (c.state?.heatWarnDc ?? 450);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Semantics(
        label: 'Akkustand ${live.pct} Prozent',
        excludeSemantics: true,
        child: MediaQuery(
          data: MediaQuery.of(context).copyWith(textScaler: heroScale),
          child: Text.rich(TextSpan(children: [
            TextSpan(text: '${live.pct}', style: tt.displayLarge),
            TextSpan(text: '$nnbsp%', style: tt.headlineSmall?.copyWith(color: cs.onSurfaceVariant)),
          ])),
        ),
      ),
      const SizedBox(height: 4),
      Row(children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(width: 6),
        Flexible(child: Text(sub, style: tt.titleMedium?.copyWith(color: live.charging ? bc.charge : cs.onSurface))),
      ]),
      if (heat) ...[
        const SizedBox(height: 4),
        Row(children: [
          Icon(Icons.warning_amber_rounded, size: 18, color: bc.hot),
          const SizedBox(width: 6),
          Text('Akku warm: ${fmtTemp(live.tempDc!)}', style: tt.bodyMedium?.copyWith(color: bc.hot)),
        ]),
      ],
      const SizedBox(height: 16),
      _TargetBar(pct: live.pct, target: live.targetPct, charging: live.charging),
      const SizedBox(height: 10),
      for (final (i, l) in etaLines.indexed)
        Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Text(l, style: i == 0 ? tt.titleMedium : tt.bodyMedium?.copyWith(color: cs.onSurfaceVariant)),
        ),
      const SizedBox(height: 8),
      ActionChip(
        avatar: Icon(Icons.flag_outlined, size: 18, color: cs.primary),
        label: Text('Ladeziel ${fmtPct(live.targetPct)}'),
        onPressed: () => showTargetSheet(context, c),
      ),
    ]);
  }
}

class _TargetBar extends StatelessWidget {
  const _TargetBar({required this.pct, required this.target, required this.charging});
  final int pct, target;
  final bool charging;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final fill = charging ? context.battery.charge : cs.primary;
    return Semantics(
      label: 'Fortschritt $pct Prozent, Ladeziel $target Prozent',
      excludeSemantics: true,
      child: SizedBox(
        height: 14,
        width: double.infinity,
        child: CustomPaint(painter: _TargetBarPainter(pct, target, fill, cs.surfaceContainerHighest, cs.onSurface)),
      ),
    );
  }
}

class _TargetBarPainter extends CustomPainter {
  _TargetBarPainter(this.pct, this.target, this.fill, this.track, this.marker);
  final int pct, target;
  final Color fill, track, marker;

  @override
  void paint(Canvas canvas, Size size) {
    final r = RRect.fromRectAndRadius(Rect.fromLTWH(0, 3, size.width, 8), const Radius.circular(4));
    canvas.drawRRect(r, Paint()..color = track);
    canvas.save();
    canvas.clipRRect(r);
    canvas.drawRect(Rect.fromLTWH(0, 3, size.width * pct / 100, 8), Paint()..color = fill);
    canvas.restore();
    final x = size.width * target / 100;
    canvas.drawLine(Offset(x, 0), Offset(x, 14), Paint()
      ..color = marker
      ..strokeWidth = 2);
  }

  @override
  bool shouldRepaint(_TargetBarPainter o) => o.pct != pct || o.target != target || o.fill != fill || o.track != track;
}

// ---------------- Werte-Raster ----------------

class _Values extends StatelessWidget {
  const _Values({required this.live});
  final Live live;

  @override
  Widget build(BuildContext context) {
    final cells = <(String, String)>[
      if (live.tempDc != null) (fmtTemp(live.tempDc!), 'Temperatur'),
      if (healthLabel(live.health).isNotEmpty) (healthLabel(live.health), 'Zustand'),
      if (live.voltageMv != null) (fmtVolt(live.voltageMv!), 'Spannung'),
      if (live.currentMa != null) (fmtMa(live.currentMa!.abs()), live.currentMa! >= 0 ? 'Strom (in den Akku)' : 'Strom (aus dem Akku)'),
      if (live.capacityMah != null) ('ca. ${fmtMah(live.capacityMah!)}', 'Kapazität'),
      if (live.cycleCount != null) ('${live.cycleCount}', 'Ladezyklen'),
    ];
    if (cells.isEmpty) return const SizedBox.shrink();
    final oneColumn = MediaQuery.textScalerOf(context).scale(10) >= 20;
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    Widget cell((String, String) v) => Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(v.$1, style: tt.titleMedium),
            Text(v.$2, style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
          ]),
        );
    if (oneColumn) return Column(crossAxisAlignment: CrossAxisAlignment.start, children: cells.map(cell).toList());
    final rows = <Widget>[];
    for (var i = 0; i < cells.length; i += 2) {
      rows.add(Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: cell(cells[i])),
        Expanded(child: i + 1 < cells.length ? cell(cells[i + 1]) : const SizedBox.shrink()),
      ]));
    }
    return Column(children: rows);
  }
}

// ---------------- Graph-Abschnitte ----------------

class _Section extends StatelessWidget {
  const _Section({required this.title, this.trailing, required this.child, this.footer});
  final String title;
  final Widget? trailing;
  final Widget child;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text(title, style: tt.titleSmall)),
        if (trailing != null) trailing!,
      ]),
      const SizedBox(height: 8),
      Card(child: Padding(padding: const EdgeInsets.fromLTRB(12, 12, 8, 8), child: child)),
      if (footer != null) Padding(padding: const EdgeInsets.only(top: 6), child: footer),
    ]);
  }
}

Widget _empty(BuildContext context, String text) => SizedBox(
      height: 80,
      child: Center(
        child: Text(text,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ),
    );

Widget _footnote(BuildContext context, String text) => Text(text,
    style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant));

class _LevelSection extends StatelessWidget {
  const _LevelSection({required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final h = c.history;
    final chargingOnly = c.state?.mode == AppState.modeChargingOnly;
    final hasGap = h != null && h.levels.isNotEmpty && gapsIn(h.from, h.to, h.coverage).isNotEmpty;
    return _Section(
      title: 'Akkustand',
      footer: hasGap
          ? _footnote(
              context,
              chargingOnly
                  ? '▨ Im Modus „Nur beim Laden“ wird beim Entladen nicht aufgezeichnet.'
                  : '▨ Keine Daten – der Dienst lief in dieser Zeit nicht.')
          : null,
      child: h == null || h.levels.isEmpty
          ? _empty(context, 'Der Verlauf füllt sich ab jetzt. Schau in ein paar Stunden wieder vorbei.')
          : LevelChart(history: h, targetPct: c.live?.targetPct ?? 80, week: c.week),
    );
  }
}

class _PowerSection extends StatelessWidget {
  const _PowerSection({required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final sessions = c.history?.sessions ?? const [];
    final i = c.selectedSession;
    final s = sessions.isEmpty ? null : sessions[i];
    final calibrating = (c.state?.profileSource ?? 0) == 0 && s != null && s.points.every((p) => p.powerMw == null);
    final tt = Theme.of(context).textTheme;
    final nav = s == null
        ? null
        : Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(
              visualDensity: VisualDensity.compact,
              tooltip: 'Älterer Ladevorgang',
              onPressed: i + 1 < sessions.length ? () => c.selectSession(i + 1) : null,
              icon: const Icon(Icons.chevron_left),
            ),
            Text(_sessionLabel(s), style: tt.labelMedium),
            IconButton(
              visualDensity: VisualDensity.compact,
              tooltip: 'Neuerer Ladevorgang',
              onPressed: i > 0 ? () => c.selectSession(i - 1) : null,
              icon: const Icon(Icons.chevron_right),
            ),
          ]);
    String? summary;
    if (s != null) {
      final end = s.endLevelPm;
      final from = s.startKnown ? fmtPct(s.startLevelPm ~/ 10) : 'ab ${fmtPct(s.startLevelPm ~/ 10)}';
      final avg = s.avgW;
      summary = [
        if (end != null) '$from → ${fmtPct(end ~/ 10)}' else '$from → läuft',
        if (avg != null) 'Ø ${fmtWatts((avg * 1000).round())}',
        if (s.peakMw != null) 'max. ${fmtWatts(s.peakMw!)}',
      ].join(' · ');
    }
    return _Section(
      title: 'Ladeleistung',
      trailing: nav,
      footer: summary == null ? null : Text(summary, style: tt.bodyMedium),
      child: s == null
          ? _empty(
              context,
              c.state?.mode == AppState.modeChargingOnly
                  ? 'Noch kein Ladevorgang aufgezeichnet. Die Aufzeichnung startet meist 15–20 Min. nach dem Einstecken.'
                  : 'Noch kein Ladevorgang aufgezeichnet. Steck dein Gerät ein.')
          : calibrating
              ? _empty(context, 'Leistung wird kalibriert …')
              : PowerChart(sessions: sessions, selected: i),
    );
  }

  static String _sessionLabel(ChargeSessionInfo s) {
    final d = DateTime.fromMillisecondsSinceEpoch(s.startTs);
    final today = DateTime.now();
    final sameDay = d.year == today.year && d.month == today.month && d.day == today.day;
    return '${sameDay ? 'Heute' : weekdays[d.weekday - 1]} ${fmtClock(d)}';
  }
}

class _DrainSection extends StatelessWidget {
  const _DrainSection({required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final d = c.history?.drain ?? const DrainStats();
    final chargingOnly = c.state?.mode == AppState.modeChargingOnly;
    return _Section(
      title: chargingOnly ? 'Verbrauch (Durchschnitt)' : 'Entladerate',
      footer: chargingOnly ? _footnote(context, 'Ohne Aufteilung Bildschirm an/aus (nur mit „Immer aktiv“).') : null,
      child: d.isEmpty
          ? _empty(context, 'Dein Verbrauch erscheint nach einigen Stunden ohne Ladegerät.')
          : Padding(padding: const EdgeInsets.only(right: 4, bottom: 4), child: DrainBars(drain: d)),
    );
  }
}

// ---------------- Ladeziel-Sheet ----------------

Future<void> showTargetSheet(BuildContext context, AppController c) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    sheetAnimationStyle: AnimationStyle.noAnimation,
    builder: (_) => _TargetSheet(c: c),
  );
}

class _TargetSheet extends StatefulWidget {
  const _TargetSheet({required this.c});
  final AppController c;
  @override
  State<_TargetSheet> createState() => _TargetSheetState();
}

class _TargetSheetState extends State<_TargetSheet> {
  late int target = widget.c.state?.targetPct ?? 80;
  late bool alarm = widget.c.state?.alarm ?? true;

  Future<void> _set({int? t, bool? a}) async {
    setState(() {
      if (t != null) target = t;
      if (a != null) alarm = a;
    });
    await widget.c.putSettings({if (t != null) 'targetPct': t, if (a != null) 'alarm': a});
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Ladeziel', style: tt.titleLarge),
          const SizedBox(height: 4),
          Text('Der Batterymanager kann das Laden nicht stoppen – er sagt dir, wann du abstecken solltest.',
              style: tt.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
          const SizedBox(height: 16),
          Wrap(spacing: 8, children: [
            for (final p in const [80, 85, 90, 100])
              ChoiceChip(label: Text(fmtPct(p)), selected: target == p, onSelected: (_) => _set(t: p)),
          ]),
          const SizedBox(height: 12),
          Text('Eigener Wert: ${fmtPct(target)}', style: tt.bodyMedium),
          Slider(
            value: target.toDouble(),
            min: 50,
            max: 100,
            divisions: 50,
            label: fmtPct(target),
            onChanged: (v) => setState(() => target = v.round()),
            onChangeEnd: (v) => _set(t: v.round()),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Alarm, wenn erreicht'),
            subtitle: const Text('Ton und Vibration, einmal pro Ladevorgang'),
            value: alarm,
            onChanged: (v) => _set(a: v),
          ),
        ]),
      ),
    );
  }
}
