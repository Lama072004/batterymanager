## Skeptic Response to Pathfinder — Tension C (Was liefert Modus (b) wirklich?)

**Their position:** Pathfinder baut (b) auf JobScheduler (`requiresCharging`, persisted): Der Job misst, aktualisiert die Benachrichtigung und plant sich im 5–15-min-Takt neu. Beim Laden greifen keine Quoten (außer „restricted“), der erste Job kommt Sekunden bis wenige Minuten nach dem Einstecken. Ziel-Alarm per inexaktem `AlarmManager`-Fenster, Entladung grob ohne Bildschirm-Split. Optional startet der Job bei Akku-Optimierungs-Ausnahme einen FGS für die Ladesitzung.

**My response:** Modify. Die Drittoption wird die einzige Live-Variante von (b), mit korrigierter Latenz und ohne Job-Tick-Pfad.

**Reasoning:**
Die Schwachstelle liegt nicht nach dem Start, sondern beim Auslöser. Nach meinem Stand des AOSP-Codes gilt `requiresCharging` für Hintergrund-Jobs erst bei „stabilem Strom“, also offiziell *charging* UND *battery not low*. Das offizielle `ACTION_CHARGING` sendet BatteryStats nach dem ersten Prozentanstieg absichtlich verzögert: per Default um 15 min (`BATTERY_CHARGED_DELAY_MS`), sofort nur ab 90 %. Realistisch sind also 15–20 min nach dem Einstecken; wer bei 8 % einsteckt, wartet zusätzlich bis „not low“ (~20 %). Die Ausnahme ändert daran nichts, die Verzögerung gilt global. Ziel 80 %, eingesteckt bei 70 % am Schnelllader: Das Ziel ist überschritten, bevor der Job läuft. Gemessen ist das nicht; bis der Spike es mit Zeitstempeln (Einstecken → Job) belegt, sind Pathfinders „Sekunden“ genauso unbelegt wie meine 15 min.

Was die Ausnahme repariert: „Nutzer hat Akku-Optimierung abgeschaltet“ steht auf der Liste der Ausnahmen für FGS-Starts aus dem Hintergrund. Die Verschärfungen in Android 15 betreffen `BOOT_COMPLETED`-Starts bestimmter FGS-Typen sowie `SYSTEM_ALERT_WINDOW`, nicht diesen Weg, und `specialUse` hat keinen Timeout. Eine App auf der Whitelist landet außerdem im Bucket EXEMPTED und damit nicht in „restricted“. Dort bräuchten Jobs zusätzlich Idle und liefen etwa 1×/Tag, also nie während der Nutzung.

Was trotzdem bricht:
1. **OEM-Schichten über AOSP:** HyperOS-Autostart, Samsungs „schlafende Apps“ und Huaweis Startverwaltung blockieren Job oder FGS trotz Ausnahme.
2. **Stiller Entzug der Ausnahme:** `ForegroundServiceStartNotAllowedException` fangen, Status anzeigen.
3. **Strengere Job-Quoten in Android 16:** Der Job ist nur Zünder (< 1 s).
4. **Neu-Scharfmachen:** Wird der FGS mitten im Laden gekillt, sieht niemand das Abstecken, und kein Job ist mehr geplant.

Ohne Ausnahme ist Pathfinders Tick-Pfad eine dritte Pipeline mit den schwächsten Garantien, die bei seltener Nutzung nach „restricted“ rutscht. Streichen.

Ehrlich: (b) spart gegenüber (a) nur wenige mAh/Tag, innerhalb von Tuners Budget. Der Gewinn ist der Wegfall der Dauer-Benachrichtigung, und so muss es in den Einstellungen stehen, nicht „spart Akku“.

**Endvorschlag: Modus (b) „Nur beim Laden“**
- **Berechtigungen:** Akku-Optimierungs-Ausnahme, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED` (Pflicht für persistente Jobs). Ohne Ausnahme: nur Daten beim Öffnen plus Banner, kein Tick-Pfad.
- **Trigger:** persistenter Job `requiresCharging` → `startForegroundService` (`specialUse`, `FOREGROUND_SERVICE_IMMEDIATE`) → `jobFinished`. Neu planen beim Abstecken, App-Start und Boot; ein Watchdog-Job (`requiresCharging` + 15 min `minLatency`) prüft, ob der FGS lebt.
- **Latenz (UI-Wert erst nach dem Spike):** typisch 15–20 min nach dem Einstecken, ab 90 % sofort, unter ~20 % erst ab „not low“, bei manchen OEMs nie. Die App zeigt die gemessene Latenz der letzten Ladevorgänge.
- **Während des Ladens:** wie (a): dieselbe Pipeline, dieselben Takte, Ziel-Alarm am `EXTRA_LEVEL`-Übergang. Level beim Start schon über dem Ziel → sofort „Ziel bereits überschritten (x %)“.
- **Ende:** beim Abstecken Snapshot (Level, `CHARGE_COUNTER`, Zeit) committen, `stopSelf`, Job neu planen.
- **Was fehlt:**
  - Benachrichtigung und Leistungskurve der ersten ~15–20 min; echter Startzeitpunkt und Start-% (Sitzung beginnt beim Dienststart, markiert).
  - Der Ziel-Alarm, wenn das Ziel innerhalb der Latenz fällt.
  - Entladeverlauf: nur bekannte Punkte (Abstecken, App-Öffnen); bis zum Dienststart bleibt eine Lücke.
  - Graph 3: nur Ø %/h zwischen Abstecken und letztem App-Öffnen davor, sonst „kein Wert“; kein Bildschirm-Split (UsageStats per Entscheidung 4 ausgeschlossen).
  - Die Hitzewarnung beim Entladen.
- **UI-Text:** „Startet beim Laden mit Verzögerung (meist 15–20 min). Beim Entladen läuft nichts. Verlauf mit Lücken, Verbrauch nur als Durchschnitt.“ Nie „sofort“ oder „lückenlos“.
