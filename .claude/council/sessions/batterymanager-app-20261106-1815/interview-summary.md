# Interview Summary: Batterymanager Flutter-App

## Core Intent
Eine minimalistische, extrem stromsparende Flutter-App für Android, Windows und Linux, die Akkuzustand, Ladeleistung (Watt) und Verbrauch anzeigt, drei Verlaufsgraphen bietet und bei jedem Ladevorgang eine Live-Benachrichtigung mit Watt und Restzeit (bis voll oder bis einstellbarem Ziel-%) zeigt.

## Key Decisions Made
- Plattformen: Android (Hauptziel, volle Daten), Windows und Linux (Laptop-Akku).
- Lade-Popup = Live-Benachrichtigung (Android: aktualisierte Notification; Desktop: Toast/Tray), kein Overlay-Fenster.
- Ziel-%: App kann Laden nicht stoppen → Restzeit bis Ziel-% + Alarm (Ton/Vibration/Benachrichtigung "Jetzt abstecken").
- Verbrauch = Entladerate (%/h, mA bzw. W), Bildschirm an vs. aus. Kein Pro-App-Verbrauch, keine Usage-Access-Berechtigung.
- Hintergrundbetrieb einstellbar: (a) Dauerdienst mit stummer Mini-Benachrichtigung → sofortiges Popup, lückenloser Verlauf; (b) ohne Dauerdienst → System-Job bei Ladebeginn, verzögert, Lücken.
- Graphen: Akku-% Verlauf, Ladeleistung (W) je Ladevorgang, Verbrauch/Entladerate. Temperatur nur als Wert (+ Warnung).
- Verteilung: privat/Sideload. Kein Tracking, keine Werbung, kein Netzwerk nötig.
- Design: minimalistisch, Material You (dynamic color), Hell/Dunkel automatisch nach System.
- Sprache: Deutsch (UI).
- OBERSTE Priorität: minimaler Eigenverbrauch der App.

## Open Questions for Deliberation
- Android-Hintergrundarchitektur: Foreground-Service-Typ (Android 14+ `specialUse`?), Receiver für ACTION_BATTERY_CHANGED, Fallback via WorkManager/JobScheduler `requiresCharging`; wie wird "einstellbar" sauber umgesetzt?
- Flutter-Engine im Hintergrund vermeiden? (Dart-Isolate im Service kostet RAM/Strom) → Hintergrundlogik nativ in Kotlin, Flutter nur für UI?
- Sampling-Strategie: Intervall beim Laden vs. Entladen, Screen-off-Verhalten, Batching von DB-Writes.
- Datenhaltung: Format/DB (SQLite/drift vs. eigene Ringpuffer-Datei), Aufbewahrungsdauer, Downsampling.
- Restzeit-Schätzung: CC/CV-Ladekurve (Abflachen ab ~80%), Glättung, Lernen aus vergangenen Ladevorgängen.
- Messwert-Fallen: CURRENT_NOW Vorzeichen/Einheit (µA vs mA) je Hersteller, fehlende Werte, Spannung in mV.
- Desktop: Windows (IOCTL_BATTERY / WMI BatteryStatus ChargeRate/DischargeRate, Power-Setting-Notifications), Linux (/sys/class/power_supply, UPower D-Bus). Desktop-Hintergrund = Tray-/Autostart-Prozess? Sparsamkeit dort.
- Chart-Library (fl_chart vs. eigene CustomPainter) — Bundle-Größe/Performance, minimalistisch.
- Material You auf Desktop (Akzentfarbe Windows/Linux), Dark/Light automatisch.
- Scope/Phasing: Android zuerst, Desktop danach?

## Perspective Relevance Scores
| Perspective | Score (0-5) | Rationale |
|-------------|-------------|-----------|
| Pathfinder | 5 | Native Akku-APIs auf 3 Plattformen, Android-Hintergrundregeln, Notifications |
| Tuner | 5 | Minimaler Eigenverbrauch ist die Kernanforderung |
| Architect | 4 | Plattform-Kanäle, nativer Hintergrunddienst vs. Flutter-UI, Datenmodell |
| Artisan | 4 | Minimalistisches Design, Material You, Dark/Light, Graphen-Look |
| Advocate | 4 | Notification-UX, Einstellungen (Dauerdienst ja/nein), Lesbarkeit der Graphen |
| Skeptic | 3 | OEM-Akku-Killer, ungenaue Messwerte, FGS-Restriktionen Android 14/15 |
| Strategist | 3 | 3 Plattformen → Phasing/MVP |
| Alchemist | 2 | Lokale Zeitreihen, Downsampling, Restzeit-Schätzung |
| Scout | 2 | Präzedenzfälle (AccuBattery, Ampere) |
| Sentinel | 2 | Fuel-Gauge-/Ladekurven-Wissen |
| Craftsman | 2 | Tests für Plattformcode |
| Guardian | 1 | Privat, keine Daten verlassen das Gerät |
| Operator | 1 | Nur Sideload-Builds |
| Herald | 0 | Keine Monetarisierung |
| Chronicler, Oracle, Cipher, Warden, Prover | 0 | Nicht relevant |
