# Round 2: Challenge — Brief des Conductors

## Konsens aus Round 1 (nicht neu verhandeln)
- Hintergrundlogik nativ, KEINE Flutter-Engine im Android-Hintergrund. Flutter = reine UI, Pigeon-Vertrag.
- Event-getrieben: beim Entladen kein eigenes Polling/Wakelock/Alarm; CURRENT_NOW nur beim Laden pollen.
- Android FGS-Typ `specialUse` (nicht dataSync). Ziel-%-Alarm am EXTRA_LEVEL-Übergang.
- Charts per CustomPainter, keine Dauer-Animationen, UI-Updates ≤ 1 Hz nur bei sichtbarer App.
- Material You via DynamicColorBuilder + ThemeExtension Zustandsfarben, ThemeMode.system.
- Release-applicationId + Keystore + Notification-Kanäle sofort endgültig festlegen.
- Messwerte normalisieren (µA/mA, Vorzeichen, Dual-Cell) mit Geräteprofil/Plausibilität; ehrliche Zustände ("n/v", "ca.", Lücken).

## Tension A — Datengrenze & Prozessmodell: Architect ↔ Pathfinder
- Architect: Engine schreibt nur ein append-only Binär-Journal (24-Byte-Records); Flutter/Dart besitzt allein die drift-SQLite-Historie und importiert idempotent beim Öffnen. Keine geteilte SQLite-Datei (zwei Schreiber, Migrationen über zwei Runtimes, zwei SQLite-Libs im selben Prozess = Lock-/Korruptionsfalle).
- Pathfinder (+ Tuner): gemeinsame SQLite-Datei (WAL), nativer Dienst im eigenen Prozess `:monitor` ist einziger Schreiber, UI liest direkt ohne Dauer-IPC; Settings per Intent an den Dienst.
- Skeptic (Kontext): Rohwerte speichern, Normalisierung beim LESEN anwenden, damit Korrekturen rückwirkend greifen.
- Conductor-Hinweise: Wenn der Dienst in `:monitor` läuft, liegen die SQLite-Libs in verschiedenen Prozessen — trifft das Korruptionsargument dann noch zu? `sqflite` nutzt auf Android die Framework-SQLite. Wo findet die Normalisierung statt, wenn Rohwerte gespeichert werden sollen? Wie liest die Engine ihre eigene Historie (z.B. gelerntes Ladeprofil für die ETA)?

## Tension B — Desktop-Host, Phasing & Energiebudget: Tuner ↔ Skeptic
- Tuner: Desktop-Hintergrund als kleiner C++-Helper (kein Flutter-Prozess im Hintergrund). Budget ≤ 5 mAh/24 h (~0,1 %/Tag), harte Grenze 0,2 %/Tag. Flush-Batching: bei Prozess-Kill gehen max. ~15 min Samples verloren.
- Skeptic: Phasing Android (a) voll → (b) dünn → Linux (sysfs) → Windows. Desktop v1 ohne Tray: Aufzeichnung nur solange App läuft (Autostart minimiert). GNOME hat ohne Erweiterung kein Tray; Windows-Toasts unpaketiert brauchen AUMID. Budget ≤ 1 %/Tag.
- Architect/Pathfinder (Kontext): Desktop = reines Dart-Engine im Flutter-Prozess mit Tray (Fenster versteckt, keine Frames); headless `dart compile exe` als späterer Erweiterungspunkt.
- Frage: Welcher Desktop-Host in welcher Phase? Welche EINE Budgetzahl (Ziel + harte Grenze) und wie gemessen? Ist 15 min Datenverlust bei Kill akzeptabel?

## Tension C — Was liefert Modus (b) "ohne Dauerdienst" wirklich?: Pathfinder ↔ Skeptic
- Pathfinder: JobScheduler mit `requiresCharging` (nicht WorkManager); beim Laden greifen Quota-Limits nicht (außer Bucket "restricted"), Takt 5–15 min realistisch.
- Skeptic: Kein garantierter Startzeitpunkt (Standby-Buckets); FGS-Start aus Job ab Android 12 ohne Akku-Optimierungs-Ausnahme verboten; SCREEN_ON/OFF nur dynamisch → Graph 3 (Bildschirm an/aus) in (b) nicht erhebbar.
- Conductor-Hinweis/Drittoption: Sideload erlaubt `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Die Akku-Optimierungs-Ausnahme ist eine FGS-Background-Start-Ausnahme. Könnte Modus (b) = "Job bei Ladebeginn startet einen NUR-WÄHREND-DES-LADENS-FGS, der beim Abstecken endet" sein (keine Dauer-Benachrichtigung beim Entladen, aber Live-Benachrichtigung beim Laden)? Welche Startlatenz ist realistisch? Was zeigt Graph 3 in (b) (z.B. nur Ø-Entladerate zwischen Ladevorgängen)?
- Frage: Exakte, ehrliche Spezifikation von Modus (b): Trigger, Latenz, Update-Takt, was in Benachrichtigung und Graphen fehlt, welche Berechtigungen.
