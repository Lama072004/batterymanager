## Skeptic Position — Batterymanager (Round 1)

**Kernempfehlung:** Das größte Risiko ist kein Absturz. Es ist eine App, die plausibel aussehende, aber falsche Zahlen zeigt und dabei selbst Akku verbraucht. Deshalb kommt zuerst eine validierte Mess-Pipeline: Rohwerte speichern, pro Gerät normalisieren, Plausibilität prüfen, Qualitätsflag pro Sample. Dazu ein messbares Eigenverbrauchs-Budget. Erst danach folgen ETA, Graphen und Desktop.

**Hauptargument (Pre-mortem, Mai 2027):** „Beim Laden zeigt die App −3,2 W. Die Restzeit springt zwischen 12 min und 3 h. Der 80-%-Alarm kam nie, weil das Pixel bei 80 % pausiert hat. HyperOS hat den Dienst nach zwei Tagen beendet, und die App verbraucht 4 %/Tag.“ Jeder dieser Punkte ist dokumentiert oder typisch:
- **`CURRENT_NOW`:** Laut Doku µA, beim Laden positiv. Real auch mA, umgekehrtes Vorzeichen, `0` oder `Integer.MIN_VALUE` (= „nicht unterstützt“).
- **Dual-Cell-Geräte:** Manche melden Zellspannung zum Pack-Strom → Watt um Faktor 2 falsch.
- **„Health %“ und Kapazität:** `BATTERY_PROPERTY_STATE_OF_HEALTH` ist System-API, Design-Kapazität nur per Reflection auf `PowerProfile` (oft Platzhalter), `EXTRA_CYCLE_COUNT` erst ab API 34, oft −1. Machbar ist nur Health-Enum plus gekennzeichnete Schätzung.
- **Modus (b) ist schwächer als gedacht:** `ACTION_POWER_CONNECTED` ist seit API 26 nicht im Manifest empfangbar. Jobs mit `requiresCharging` unterliegen Standby-Buckets, also kein garantierter Startzeitpunkt. Einen Foreground-Service (FGS) aus einem Job zu starten, ist ab Android 12 ohne Akku-Optimierungs-Ausnahme verboten. `SCREEN_ON/OFF` gibt es nur dynamisch: **Graph 3 („Bildschirm an vs. aus“) ist in (b) technisch nicht erhebbar.**

**Risiken, wenn ignoriert:**
- **Falsche Messwerte — Kritisch (Vertrauen):** Fehler wandern in Benachrichtigung, Graph 2/3 und ETA. Speichert man nur normalisierte Werte, bleibt der Verlauf dauerhaft falsch.
- **„Stromspar“-Versprechen gebrochen — Hoch:** Polling mit Wakelock beim Entladen, Notification-Updates im Sekundentakt (ohne `setOnlyAlertOnce` poppt jedes Update neu auf), Flutter-Engine im Dienst, ein DB-Write pro Sample. Ohne Budget fällt das niemandem auf; Vorschlag: ≤ 1 %/Tag Mehrverbrauch im Dauerdienst, geprüft per `dumpsys batterystats`.
- **Scope — Hoch:** 3 Plattformen × 2 Modi × Tray/Autostart. GNOME hat ohne Erweiterung kein Tray; Windows-Toasts aus unpaketierten Apps brauchen AUMID und Verknüpfung. Minimal glaubwürdiger Umfang:
  - **Phase 1:** Android, (a) vollständig; (b) als dünne Trigger-Variante derselben nativen Pipeline.
  - **Phase 2:** Linux (sysfs).
  - **Phase 3:** Windows.
  - **Desktop v1 ohne Tray:** Aufzeichnung nur, solange die App läuft (Autostart minimiert).

**Abhängigkeiten:**
- **Pathfinder:** FGS-Typ `specialUse` statt `dataSync` (Android 15: 6 h/24 h, kein Start aus `BOOT_COMPLETED`). Neustart bei `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` und App-Öffnen. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Sideload erlaubt) plus OEM-Hinweise. **Notification-Kanäle sofort endgültig festlegen**, da Importance nachträglich nicht änderbar: Dienst (MIN), Laden live (einmal heads-up, dann still), Alarm (HIGH mit Ton).
- **Tuner:** Budget und Messmethode für den Eigenverbrauch. Beim Entladen nur ereignisgesteuert (`BATTERY_CHANGED`, Screen-Broadcasts), keine eigenen Wakeups. Strom nur beim Laden pollen; Benachrichtigung höchstens alle 5–10 s und nur bei geändertem gerundetem Wert aktualisieren.
- **Architect:** Normalisierung mit Geräteprofil (Einheit, Vorzeichen, Dual-Cell, Override), angewendet **beim Lesen**, damit Korrekturen rückwirkend greifen. Nur ein Schreiber (nativer Dienst). Zeit als UTC + `elapsedRealtime` + `BOOT_COUNT`. Schemaversion ab v1, `allowBackup=false`. **Release-Keystore und endgültige `applicationId` jetzt festlegen**: Aktuell `com.example.*` mit Debug-Key; ein späterer Wechsel heißt Neuinstallation und Datenverlust.
- **Advocate/Artisan:** Ehrliche Zustände statt Scheinwerten: „n/v“ statt 0 W, „Ladepause (Limit/adaptiv)“ statt ETA, „Lücke – App war beendet“, gerundete ETA, Leistung als „am Akku (netto)“ statt Ladegeräteleistung.

---

### Anhang A — Failure Mode Analysis

S = Schwere, W = Wahrscheinlichkeit.

| # | Komponente | Fehlermodus | S | W | Kaskade | Mitigation | Signal |
|---|---|---|---|---|---|---|---|
| 1 | `CURRENT_NOW` | µA/mA verwechselt | Hoch | Hoch | W, mA, Graph 2/3, ETA | Einheit **pro Gerät** lernen, nicht pro Sample (Plausibilität \|P\| ≤ ~150 W, Abgleich mit ΔCHARGE_COUNTER bzw. Δ%), Override | Anteil verworfener Samples |
| 2 | `CURRENT_NOW` | Vorzeichen invertiert / 0 / MIN_VALUE | Hoch | Mittel | Laden wird als Entladen angezeigt | Vorzeichen aus Status DISCHARGING lernen; 0/MIN → „n/v“, nie als 0 plotten | Zähler für Vorzeichenkonflikte |
| 3 | Spannung | Dual-Cell, mV/µV | Mittel | Mittel | Watt × 2 bzw. ÷ 2 | Bereichsprüfung 3–4,6 V oder 6–9,2 V, Override | V außerhalb des Bereichs |
| 4 | FGS | OEM-Kill (HyperOS, Samsung „Tief schlafend“, Huawei, OnePlus) | Kritisch | Hoch | Popup, Verlauf und **Alarm** fallen aus | Onboarding-Check Akku-Optimierung, OEM-Anleitung, Heartbeat, Lücke markieren statt interpolieren | Heartbeat-Alter beim App-Start |
| 5 | FGS-Start | Android-12/14/15-Restriktionen | Hoch | Mittel | Kein Dienst nach Reboot/Update | `specialUse`, Starts aus erlaubten Broadcasts; `ForegroundServiceStartNotAllowedException` fangen → (b) + Banner | Exception-Log |
| 6 | Notification | `POST_NOTIFICATIONS` abgelehnt, Kanal stumm | Hoch | Mittel | Popup und Alarm unsichtbar | `areNotificationsEnabled` und Kanal-Wichtigkeit prüfen, In-App-Banner, Deep-Link | Banner-Status |
| 7 | Modus (b) | Job verzögert/fällt aus, kein Screen-Signal | Hoch | Hoch | Graph 3 leer, Alarm verpasst | In den Einstellungen als „best effort“ kennzeichnen; Graph 3 nur in (a); Snapshot beim Öffnen | Latenz Einstecken → Job |
| 8 | ETA | CC/CV, adaptive Pause, PD-Hochlauf, Kabelwechsel | Mittel | Hoch | Flackern, Ziel nie erreicht | 60–90 s Aufwärmphase, EWMA, CV-Abfall aus Historie, Pause-Erkennung (eingesteckt, I≈0, % 10 min konstant), 5-min-Stufen | Soll/Ist-Abweichung am Ende |
| 9 | Ziel-Alarm | 79↔80-Flattern, Einstecken über Ziel, Nicht stören (DND) | Mittel | Mittel | Mehrfachalarm oder gar keiner | Einmal pro Ladevorgang, Hysterese, eigener Kanal | Alarme pro Ladevorgang |
| 10 | SQLite | Korruption beim Akku-Tod (0 %); Wachstum | Hoch | Niedrig | Verlauf weg; Speicher, Akku | WAL + `synchronous=NORMAL`, gebündelte Writes (≤ 1/min oder bei Statuswechsel), `quick_check` beim Start, Rohdaten 14 Tage, Aggregate 1 Jahr | Integritätsprüfung, DB-Größe |
| 12 | Zeit | NTP-/Handsprung, Zeitzone, DST, Reboot | Mittel | Mittel | Δt ≤ 0 → Raten werden ∞ | Raten nur aus Δ`elapsedRealtime` innerhalb eines Boots; Δt ≤ 0 → Segmentbruch | Zähler für Δt < 0 |
| 13 | Eigenverbrauch | Wakelocks, Notification-Spam, Flutter im Hintergrund | Kritisch | Mittel | Kernversprechen gebrochen | Keine Wakelocks, kein `FlutterEngine` im Dienst, Update-Drossel; Abnahmetest mit batterystats | mAh-Anteil der App |
| 14 | Desktop-Quelle | Kein Akku / 2 Akkus / `BATTERY_UNKNOWN_RATE` / `power_now` fehlt / HID-Mäuse in `power_supply` | Mittel | Hoch | Absturz bzw. falsche Watt | Zustand „Kein Akku“; aggregieren; W aus V×I oder ΔEnergie; Filter `type=Battery`, `scope≠Device`; relative Kapazität erkennen | – |

**Kaskadendiagramm**
```
[Normalisierung falsch]
 ├── Live-Benachrichtigung (W, ETA) — falsch
 ├── Graph 2/3 — falsch; korrigierbar nur mit gespeicherten Rohwerten
 └── Ziel-Alarm — unberührt (hängt nur an %)  ← bewusst entkoppeln
[Dienst gekillt]
 ├── Verlauf — Lücke (NICHT als „0 Verbrauch“ interpolieren)
 ├── Popup — fehlt
 └── Ziel-Alarm — fehlt (kritisch; beim nächsten Start melden)
```

**Rollback-Checkliste**
- [ ] Feature-Flags = Einstellungen: Dauerdienst, Strom-Einheit/Vorzeichen/Dual-Cell manuell, ETA aus.
- [ ] DB: Migrationen über `user_version`, Kopie vor jeder Migration.
- [ ] APK: immer derselbe Release-Keystore; Downgrade nur bei schemakompatiblem Stand.

### Anhang B — Edge Cases

| Kat. | Edge Case | Erwartetes Verhalten | P |
|---|---|---|---|
| Input | `CURRENT_NOW` dauerhaft 0/MIN | W und mA „n/v“, ETA nur aus %-Steigung | P0 |
| Input | µA-Gerät im Leerlauf (8000 µA) | Nicht als 8 A deuten; Einheit ist pro Gerät festgelegt | P0 |
| Input | Eingesteckt, aber Gaming → Netto-I < 0 | „Entlädt trotz Ladegerät (−x W)“, keine ETA | P0 |
| Input | Temperatur 0 / > 80 °C (Einheit Zehntel °C); `EXTRA_SCALE` ≠ 100 | Plausibel −20…70 °C, Warnung ab 45 °C mit Hysterese; % = level·100/scale | P1 |
| Input | Kabellos: Fehllage → An/Aus-Flattern | Ladevorgänge mit < 2 min Lücke zusammenführen, kein erneutes Popup | P1 |
| State | Erststart ohne Daten | Leere Graphen mit Erklärung | P1 |
| State | Einstecken bei ≥ Ziel-% | Einmaliger Hinweis „Ziel bereits erreicht“ | P0 |
| State | OEM-Limit unter dem Ziel (Pixel 80, Samsung 85, Lenovo 60) | Zustand „Ladepause/Limit“, ETA ausblenden, Ziel als unerreichbar markieren | P0 |
| State | 100 %, Strom > 0 (Nachladen) | „Voll“; der Ladevorgang endet erst beim Abstecken | P2 |
| State | Neustart oder Akku leer mitten im Ladevorgang | Ladevorgang sauber schließen; zusammenführen bei Lücke < 5 min und stetigem % | P1 |
| Concurrency | Dienst schreibt, während die UI liest | WAL, ein einziger Schreiber | P0 |
| Concurrency | Moduswechsel (a)↔(b) während des Ladens | Keine doppelte Session; Job ohne Wirkung, wenn der Dienst läuft | P1 |
| Concurrency | `BATTERY_CHANGED`-Salve | Ereignisse bündeln, nur ein Write | P1 |
| Concurrency | App-Update während des Ladens | Dienst über `MY_PACKAGE_REPLACED` neu starten | P1 |
| Concurrency | Desktop: Autostart + manuell gestartet | Sperre gegen zweite Instanz | P1 |
| Temporal | DST: Tag hat 23/25 h | Speichern in UTC, Tagesgrenzen lokal | P1 |
| Temporal | Uhr per Hand verstellt / NTP-Sprung | Raten aus `elapsedRealtime` | P0 |
| Temporal | Zeitzonenwechsel auf Reisen | Anzeige in der aktuellen Zeitzone | P2 |
| Temporal | Ladevorgang < 1 min (Wackelkontakt) | Nicht als Ladevorgang zählen | P1 |
| Temporal | Laptop dauerhaft eingesteckt (> 24 h) | Ladevorgang begrenzen, Downsampling | P2 |
| Permission | `POST_NOTIFICATIONS` abgelehnt | Dienst läuft weiter, Banner in der App | P0 |
| Permission | Nur Alarm-Kanal stummgeschaltet | Warnung in der App | P1 |
| Permission | Akku-Optimierung wieder aktiv / Samsung „schlafend“ | Heartbeat-Lücke erkennen, Hinweis | P0 |
| Permission | Force-Stop durch den Nutzer | Kein Start nach Boot bis zum nächsten Öffnen; Lücke markieren | P1 |
| Permission | Windows: Fokus-Assistent; Linux: kein Notification-Daemon | Status in der App statt Toast | P2 |
