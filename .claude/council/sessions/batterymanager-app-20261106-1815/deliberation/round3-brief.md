# Round 3: Converge — Was in Round 2 passiert ist

## Tension A — Datengrenze (Architect ↔ Pathfinder): GELÖST, beide "Modify"
- Architect gibt das Binär-Journal auf (Korruptionsargument entfällt mit separatem `:monitor`-Prozess + sqflite = Android-Framework-SQLite → nur EINE SQLite-Bibliothek).
- Ergebnis: EINE geteilte SQLite-Datei (WAL). Einziger Schreiber + Migrator ist der native Dienst im Prozess `:monitor` (Desktop: die Dart-Engine). Schema liegt als versionierte SQL-Dateien vor.
- Gespeichert werden ROHWERTE + `device_profile` (Einheit/Vorzeichen/Dual-Cell/Override) + `charge_profile` (gelernte Ladekurve für ETA). Normalisierung beim LESEN per SQL-View (Korrekturen wirken rückwirkend).
- Flutter-UI liest read-only via `sqflite` (kein drift, kein Journal). Settings an den Dienst per `ContentProvider.call()`. Live-Daten per `Messenger`-Binding nur solange UI sichtbar.

## Tension B — Desktop-Host, Phasing, Budget (Tuner ↔ Skeptic): GELÖST, beide "Modify"
- Kein C++-Helper. Desktop-Host = Dart-Engine im versteckten Flutter-Prozess, Tray optional (GNOME ohne Tray-Erweiterung beachten). Headless `dart compile exe` nur, falls das Mess-Gate reißt.
- Phasing: (1) Android Modus (a) vollständig → (2) Android Modus (b) → (3) Linux (sysfs/UPower) → (4) Windows.
- Energiebudget Android Modus (a): Ziel ≤ 5 mAh/24 h, harte Grenze 25 mAh/24 h. Abnahme über exakt messbare Zähler (batterystats: Wakeups, CPU-Zeit, Wakelock-Zeit, Prozess-Uptime) — 0,1 % direkt ist nicht messbar. Desktop: Ziel ≤ 10 mW, harte Grenze 30 mW Mehrverbrauch.
- Flush: beim Entladen pro Ereignis; beim Laden Batch alle 60 s. Zustandswechsel (Einstecken/Abstecken/Ziel erreicht/voll) werden IMMER sofort geschrieben; Verlust bei Kill nur für Jitter-Samples.

## Tension C — Modus (b) (Pathfinder ↔ Skeptic): GELÖST, beide "Modify"
- Modus (b) heißt "Nur beim Laden": JobScheduler-Job (`requiresCharging`) startet — dank Akku-Optimierungs-Ausnahme (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, beim Sideload erlaubt) — einen FGS, der NUR während des Ladens läuft und beim Abstecken endet. Keine Dauer-Benachrichtigung beim Entladen.
- Ehrliche Einschränkung: Startlatenz voraussichtlich 15–20 min nach dem Einstecken (AOSP `battery_charged_delay_ms`; aus Code abgeleitet, NICHT gemessen → Spike muss belegen).
- Job-Tick-Pfad und `setWindow`-Alarme gestrichen.
- Graph 3 in (b): nur Ø-Entladerate zwischen Ladevorgängen, kein Bildschirm-an/aus-Split; Graph 1 hat Lücken beim Entladen.

## Unverändert gültiger Konsens aus Round 1
Native Hintergrundlogik ohne Flutter-Engine; event-getrieben; FGS-Typ `specialUse`; CustomPainter-Charts ohne Animation; UI ≤ 1 Hz nur sichtbar; Material You + ThemeExtension; ehrliche Zustände; applicationId/Keystore/Notification-Kanäle sofort festlegen; Benachrichtigungs-Aktionen nativ.
