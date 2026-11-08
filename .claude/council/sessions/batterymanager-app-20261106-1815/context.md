# Shared Council Context — Batterymanager

## PROJECT_CONTEXT
- workspace: D:\Mathias Lampert\Flutterapps\batterymanager (Windows 11 dev machine, kein git repo, keine CLAUDE.md)
- tech_stack: Flutter 3.35.6 (stable), Dart 3.9.2, frisches `flutter create`-Template. Abhängigkeiten nur cupertino_icons, flutter_lints. lib/main.dart = Counter-Demo.
- Plattform-Ordner: android/ (Kotlin DSL Gradle, namespace com.example.batterymanager, Java 11), windows/, linux/. Kein iOS/macOS/web.
- key_dirs: android/, lib/, linux/, test/, windows/

## Die Idee
Flutter-App "batterymanager": zeigt alle Infos zu Batteriezustand (Health, Temperatur, Spannung, Kapazität, Zyklen wo verfügbar), Ladegeschwindigkeit (Watt) und Batterieverbrauch, dargestellt über nützliche Graphen. Bei jedem Ladevorgang erscheint ein "Popup" mit aktueller Ladeleistung in Watt und voraussichtlicher Zeit bis voll bzw. bis zu einer einstellbaren Ziel-Prozentzahl.
GANZ WICHTIG: Die App selbst soll wenig bis gar keinen Stromverbrauch haben.
UI: minimalistisch/simplistisch, Dark + Light Mode mit automatischer Erkennung des System-Themes. UI-Sprache Deutsch.

## Entscheidungen aus dem Interview (vom Nutzer festgelegt — nicht neu verhandeln)
1. Plattformen: Android (Hauptziel), Windows und Linux (Laptop-Akku).
2. Lade-"Popup" = Live-Benachrichtigung (aufpoppend, Watt, Restzeit, Fortschritt, live aktualisiert). Kein Overlay-Fenster.
3. Ziel-%: App kann Laden nicht stoppen → Restzeit bis Ziel-% + Alarm (Ton/Vibration/Benachrichtigung "Jetzt abstecken").
4. Verbrauch = Entladerate (%/h, mA bzw. W), Bildschirm an vs. aus. Kein Pro-App-Verbrauch, keine Usage-Access-Berechtigung.
5. Hintergrundbetrieb EINSTELLBAR: (a) Dauerdienst (Android Foreground-Service mit stummer Mini-Benachrichtigung) → sofortiges Popup + lückenloser Verlauf; (b) ohne Dauerdienst → System-Job bei Ladebeginn, verzögert, Lücken im Verlauf.
6. Graphen (genau 3): Akku-% Verlauf (24h/7d, Lade-/Entladephasen markiert), Ladeleistung (W) je Ladevorgang, Verbrauch/Entladerate (Bildschirm an vs. aus). Temperatur nur als Wert (+ Hitzewarnung), kein Graph.
7. Verteilung: privat/Sideload (kein Play Store, keine Werbung, kein Tracking, kein Netzwerk nötig).
8. Design: minimalistisch, Material You (dynamic color, Android 12+), Desktop-Fallback Akzent-/Standardfarbe; Hell/Dunkel folgt automatisch dem System.

## Offene Fragen für die Deliberation
- Android-Hintergrundarchitektur: FGS-Typ (Android 14+), dynamischer Receiver für ACTION_BATTERY_CHANGED, Fallback via JobScheduler/WorkManager requiresCharging; saubere Umsetzung des "einstellbar".
- Flutter-Engine im Hintergrund vermeiden? Hintergrundlogik nativ (Kotlin / C++ / Rust?), Flutter nur UI?
- Sampling-Strategie: Intervall Laden vs. Entladen, Screen-off, Batching von Writes.
- Datenhaltung: SQLite/drift vs. Ringpuffer-Datei, Retention, Downsampling.
- Restzeit-Schätzung: CC/CV-Ladekurve, Glättung, Lernen aus früheren Ladevorgängen.
- Messwert-Fallen: CURRENT_NOW Vorzeichen/Einheit (µA vs mA) je Hersteller, fehlende Werte.
- Desktop: Windows (IOCTL_BATTERY_QUERY_STATUS / WMI BatteryStatus, RegisterPowerSettingNotification), Linux (/sys/class/power_supply, UPower D-Bus). Desktop-Hintergrund = Tray/Autostart? Sparsamkeit dort.
- Chart-Library (fl_chart vs. CustomPainter), minimalistisch und performant.
- Material You auf Desktop, Dark/Light automatisch.
- Phasing: Android zuerst, Desktop danach?

## Council-Mitglieder dieser Session
Pathfinder (Mobile/Native), Tuner (Performance/Stromverbrauch), Architect (System/Datenmodell), Artisan (Visual Design), Advocate (UX), Skeptic (Risiken).
