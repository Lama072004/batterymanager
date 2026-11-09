import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../core/controller.dart';
import '../core/format.dart';
import '../core/model.dart';
import 'onboarding.dart';
import 'overview.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final s = c.state ?? const AppState();
        final cs = Theme.of(context).colorScheme;
        final tt = Theme.of(context).textTheme;
        Widget header(String t) => Padding(
              padding: const EdgeInsets.fromLTRB(16, 24, 16, 4),
              child: Text(t, style: tt.titleSmall?.copyWith(color: cs.primary)),
            );
        Widget ok(bool v) => Icon(v ? Icons.check_circle_outline : Icons.error_outline, color: v ? cs.primary : cs.error);

        return Scaffold(
          appBar: AppBar(title: const Text('Einstellungen')),
          body: ListView(
            padding: const EdgeInsets.only(bottom: 32),
            children: [
              header('Hintergrundbetrieb'),
              RadioGroup<int>(
                groupValue: s.mode,
                onChanged: (v) => _setMode(context, v!),
                child: Column(children: [
                  RadioListTile<int>(
                    value: AppState.modeAlways,
                    title: const Text('Immer aktiv (empfohlen)'),
                    subtitle: Text(ModeTexts.always),
                    isThreeLine: true,
                  ),
                  RadioListTile<int>(
                    value: AppState.modeChargingOnly,
                    title: const Text('Nur beim Laden'),
                    subtitle: Text(ModeTexts.chargingOnly),
                    isThreeLine: true,
                  ),
                ]),
              ),
              header('Ladeziel'),
              ListTile(
                title: const Text('Ladeziel'),
                subtitle: Text(fmtPct(s.targetPct)),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => showTargetSheet(context, c),
              ),
              SwitchListTile(
                title: const Text('Alarm, wenn erreicht'),
                subtitle: const Text('Ton und Vibration, Erinnerung nach 10 Min.'),
                value: s.alarm,
                onChanged: (v) => c.putSettings({'alarm': v}),
              ),
              header('Benachrichtigungen'),
              SwitchListTile(
                title: const Text('Zusammenfassung nach dem Laden'),
                subtitle: const Text('z. B. „23 % → 81 % in 52 Min. · Ø 15 W“'),
                value: s.summary,
                onChanged: (v) => c.putSettings({'summary': v}),
              ),
              SwitchListTile(
                title: const Text('Watt in der Statusleiste'),
                subtitle: const Text('Ab Android 16: Ladeleistung dauerhaft oben neben der Uhr. '
                    'Aus: oben nur das Symbol – Watt und Restzeit beim Herunterziehen der Benachrichtigung.'),
                value: s.liveChip,
                onChanged: (v) => c.putSettings({'liveChip': v}),
              ),
              ListTile(
                title: const Text('Hitzewarnung ab'),
                trailing: Wrap(spacing: 4, children: [
                  for (final dc in const [400, 450, 500])
                    ChoiceChip(
                      label: Text(fmtTemp(dc).replaceAll(',0', '')),
                      selected: s.heatWarnDc == dc,
                      onSelected: (_) => c.putSettings({'heatWarnDc': dc}),
                    ),
                ]),
              ),
              ListTile(
                title: const Text('Android-Benachrichtigungskanäle'),
                trailing: const Icon(Icons.open_in_new),
                onTap: c.backend.openNotificationSettings,
              ),
              header('Status'),
              ListTile(
                leading: ok(c.status.notificationsEnabled),
                title: const Text('Benachrichtigungen'),
                subtitle: Text(c.status.notificationsEnabled ? 'Erlaubt' : 'Aus – keine Lade-Info'),
                onTap: c.backend.openNotificationSettings,
              ),
              ListTile(
                leading: ok(c.status.ignoringBatteryOptimizations),
                title: const Text('Ausnahme von der Akku-Optimierung'),
                subtitle: Text(c.status.ignoringBatteryOptimizations
                    ? 'Erteilt'
                    : s.mode == AppState.modeChargingOnly
                        ? 'Fehlt – „Nur beim Laden“ startet nicht'
                        : 'Empfohlen, damit Android den Dienst nicht beendet'),
                onTap: () async {
                  await c.backend.requestIgnoreBatteryOptimizations();
                  await c.refreshStatus();
                },
              ),
              ListTile(
                leading: ok(c.live?.serviceRunning ?? false),
                title: const Text('Hintergrunddienst'),
                subtitle: Text((c.live?.serviceRunning ?? false)
                    ? 'Läuft'
                    : s.mode == AppState.modeChargingOnly
                        ? 'Startet beim nächsten Laden'
                        : 'Läuft nicht'),
              ),
              header('Eigenverbrauch'),
              ListTile(
                title: const Text('CPU-Zeit des Dienstes'),
                subtitle: Text('${(s.cpuMs / 1000).toStringAsFixed(1).replaceAll('.', ',')}${nnbsp}s seit Dienststart · '
                    '${s.flushes} Schreibvorgänge · ${s.notifUpdates} Benachrichtigungs-Updates'),
              ),
              header('Diagnose'),
              ListTile(
                title: const Text('Messwerte des Geräts'),
                subtitle: Text('Strom in ${s.iToMa == 0 ? 'nicht unterstützt' : s.iToMa >= 1 ? 'mA' : 'µA'} · '
                    'Vorzeichen ${s.sign > 0 ? 'normal' : 'umgekehrt'} · ${s.cells} Zelle${s.cells > 1 ? 'n' : ''} · '
                    '${switch (s.profileSource) { 2 => 'manuell', 1 => 'automatisch erkannt', _ => 'noch nicht bestätigt (Werte geschätzt)' }}'),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Wrap(spacing: 8, runSpacing: 8, children: [
                  OutlinedButton(
                    onPressed: () => c.setProfile({'sign': -s.sign, 'iToMa': s.iToMa, 'cells': s.cells}),
                    child: const Text('Vorzeichen umkehren'),
                  ),
                  OutlinedButton(
                    onPressed: () => c.setProfile({'iToMa': s.iToMa >= 1 ? 0.001 : 1.0, 'sign': s.sign, 'cells': s.cells}),
                    child: Text(s.iToMa >= 1 ? 'Einheit: µA' : 'Einheit: mA'),
                  ),
                  OutlinedButton(
                    onPressed: () => c.setProfile({'auto': true}),
                    child: const Text('Automatisch erkennen'),
                  ),
                ]),
              ),
              ListTile(
                title: const Text('Ladelimit des Geräts'),
                subtitle: Text(s.deviceLimitPct != null
                    ? 'ca. ${fmtPct(s.deviceLimitPct!)} – erkannt, weil das Gerät dort ≥ 30 Min. angehalten hat'
                    : 'Nicht erkannt. Wird gelernt, sobald das Gerät beim Laden ≥ 30 Min. auf einem Stand hält.'),
                trailing: s.deviceLimitPct == null ? null : TextButton(onPressed: c.resetLimit, child: const Text('Vergessen')),
              ),
              const SizedBox(height: 8),
              ListTile(
                title: Text('Alle Daten löschen', style: TextStyle(color: cs.error)),
                subtitle: const Text('Verlauf, Ladevorgänge und gelernte Ladekurve'),
                onTap: () => _confirmClear(context),
              ),
              if (kDebugMode)
                ListTile(
                  title: const Text('Demo-Daten laden (nur Debug)'),
                  subtitle: const Text('Ersetzt den Verlauf durch 7 Tage Beispieldaten'),
                  onTap: c.seedDemo,
                ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _setMode(BuildContext context, int mode) async {
    if (mode == AppState.modeChargingOnly && !c.status.ignoringBatteryOptimizations) {
      await c.backend.requestIgnoreBatteryOptimizations();
    }
    await c.putSettings({'mode': mode});
    await c.refreshStatus();
  }

  Future<void> _confirmClear(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Alle Daten löschen?'),
        content: const Text('Verlauf, Ladevorgänge und die gelernte Ladekurve werden gelöscht. Das lässt sich nicht rückgängig machen.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Abbrechen')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Löschen')),
        ],
      ),
    );
    if (ok == true) await c.clearHistory();
  }
}
