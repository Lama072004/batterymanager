import 'package:flutter/material.dart';

import '../core/controller.dart';
import '../core/model.dart';

/// Ehrliche Texte zur Moduswahl (design.md, Advocate) – gleicher Wortlaut in den Einstellungen.
class ModeTexts {
  static const always = 'Lade-Info sofort beim Einstecken, vollständiger Verlauf, Verbrauch getrennt nach '
      'Bildschirm an und aus. Dafür zeigt Android dauerhaft eine kleine, stumme Benachrichtigung.';
  static const chargingOnly = 'Keine Dauer-Benachrichtigung. Die Lade-Info erscheint meist erst 15–20 Minuten nach dem '
      'Einstecken, ein früh erreichtes Ladeziel kann verpasst werden. Beim Entladen läuft nichts: Der Verlauf hat Lücken, '
      'den Verbrauch gibt es nur als Durchschnitt. Spart kaum Akku.';
}

/// Einmaliger Start-Screen: Moduswahl, dann begründete Berechtigungsabfragen.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.c, required this.onDone});
  final AppController c;
  final VoidCallback onDone;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  int mode = AppState.modeAlways;
  bool busy = false;

  Future<void> _continue() async {
    setState(() => busy = true);
    final c = widget.c;
    // 1. Benachrichtigungen (Begründung steht im Dialog)
    if (mounted &&
        await _explain(
          'Benachrichtigungen erlauben',
          'Damit beim Laden Watt und Restzeit erscheinen, braucht der Batterymanager Benachrichtigungen.',
          'Weiter',
          null,
        ) == true) {
      await c.backend.requestNotificationPermission();
    }
    // 2. Akku-Optimierungs-Ausnahme: in „Immer aktiv“ empfohlen, in „Nur beim Laden“ Pflicht.
    final status = await c.backend.systemStatus();
    if (!status.ignoringBatteryOptimizations && mounted) {
      final required = mode == AppState.modeChargingOnly;
      final r = await _explain(
        'Akku-Optimierung',
        required
            ? 'Erforderlich, sonst startet „Nur beim Laden“ nicht.'
            : 'Empfohlen, damit Android den Dienst nicht beendet. Der Batterymanager selbst verbraucht dadurch nicht mehr Strom.',
        'Erlauben',
        required ? 'Zu „Immer aktiv“ wechseln' : 'Später',
      );
      if (r == true) {
        await c.backend.requestIgnoreBatteryOptimizations();
      } else if (r == false && required) {
        mode = AppState.modeAlways;
      }
    }
    await c.putSettings({'mode': mode, 'onboarded': true});
    await c.refreshAll();
    widget.onDone();
  }

  Future<bool?> _explain(String title, String text, String yes, String? no) => showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => AlertDialog(
          title: Text(title),
          content: Text(text),
          actions: [
            if (no != null) TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(no)),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(yes)),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    Widget option(int value, String title, String text) => Card(
          color: mode == value ? cs.secondaryContainer : cs.surfaceContainerLow,
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => setState(() => mode = value),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(4, 12, 16, 16),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Radio<int>(value: value),
                const SizedBox(width: 4),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Padding(padding: const EdgeInsets.only(top: 12), child: Text(title, style: tt.titleMedium)),
                    const SizedBox(height: 6),
                    Text(text, style: tt.bodyMedium?.copyWith(color: cs.onSurfaceVariant)),
                  ]),
                ),
              ]),
            ),
          ),
        );
    return Scaffold(
      body: SafeArea(
        child: RadioGroup<int>(
          groupValue: mode,
          onChanged: (v) => setState(() => mode = v!),
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 32, 16, 24), children: [
            Icon(Icons.battery_5_bar_outlined, size: 40, color: cs.primary),
            const SizedBox(height: 16),
            Text('Wie soll der Batterymanager arbeiten?', style: tt.headlineSmall),
            const SizedBox(height: 24),
            option(AppState.modeAlways, 'Immer aktiv (empfohlen)', ModeTexts.always),
            const SizedBox(height: 12),
            option(AppState.modeChargingOnly, 'Nur beim Laden', ModeTexts.chargingOnly),
            const SizedBox(height: 16),
            Text('Jederzeit in den Einstellungen änderbar.', style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
            const SizedBox(height: 24),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(onPressed: busy ? null : _continue, child: const Text('Weiter')),
            ),
          ]),
        ),
      ),
    );
  }
}
