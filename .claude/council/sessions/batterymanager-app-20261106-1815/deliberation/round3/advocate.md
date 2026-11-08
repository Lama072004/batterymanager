## Advocate Final Position — Batterymanager (Lade-Benachrichtigung, Onboarding, Screens)

**Überarbeitete Empfehlung:** Die Lade-Benachrichtigung bleibt das Hauptprodukt: *eine* Benachrichtigung mit fester ID, deren Kanal mit dem Zustand wechselt und die nur beim Ladeziel klingelt. „Immer aktiv“ ist vorausgewählt. „Nur beim Laden“ ist eine ehrliche Option ohne Dauer-Benachrichtigung und wird nie als „sofort“, „lückenlos“ oder „spart Akku“ beschrieben.

**Zugeständnisse:**
- Updates höchstens alle 10 s, bei Bildschirm aus eingefroren. Das bleibt unsichtbar, weil beim Einschalten sofort aktualisiert wird. Deshalb kein Countdown, „Voll“ steht als Uhrzeit da.
- Zustand 0 zeigt keinen Akkustand, weil er beim Entladen nie aktualisiert wird.
- In (b) akzeptiere ich 15–20 Min. Latenz, nur einen Ø-Verbrauch und gestrichelte Lücken, weil all das offen benannt wird.
- Eine Zahl zum Mehrverbrauch erscheint erst nach der Messung. Ohne Tray (GNOME) laufen Infos nur über Benachrichtigungen.

**Non-negotiables:**
1. Nie zwei App-Benachrichtigungen gleichzeitig. Ton und Vibration gibt es nur beim Ladeziel-Alarm.
2. Watt, Restzeit, Ziel ändern und „Alarm aus“ funktionieren direkt in der Benachrichtigung, ohne die App zu öffnen.
3. Die Restzeit steht immer mit „ca.“ oder als „wird berechnet“. Jede Lücke ist sichtbar und erklärt, unplausible Werte werden ausgeblendet.
4. Vor jeder Berechtigung steht eine Begründung in einem Satz. Eine Ablehnung führt zu einem Banner mit Deep-Link.
5. Die A11y-Checkliste aus R1 ist Abnahmekriterium.

---

### Lade-Benachrichtigung, Modus (a)

**Kanäle:** `monitor` (MIN, stumm) · `charging` (HIGH, ohne Ton) · `heat` (HIGH, ohne Ton) · `target_alarm` (HIGH, Ton + Vibration). Eine ID für alle Zustände, Kanalwechsel per Re-Post. Der Spike prüft, ob das an der FGS-Benachrichtigung greift; sonst kommt der Alarm als zweite Benachrichtigung, die beim nächsten Update verschwindet.

| # | Zustand | Titel | Text | Aktionen | Kanal | Signal |
|---|---|---|---|---|---|---|
| 0 | Bereit | `Batterymanager ist bereit` | `Lade-Info erscheint beim Einstecken.` | – | monitor | keins |
| 0h | Warm beim Entladen | `Akku warm: 45 °C` | `Gerät abkühlen lassen.` | `Verstanden` | heat | Heads-up 1× |
| 1 | Eingesteckt (~30 s) | `Lädt · 42 %` | `Ladeleistung wird ermittelt …` | `Ziel 80 %` · `Alarm aus` | charging | Heads-up 1×, still |
| 2 | Lädt | `Lädt mit 18 W · 42 %` | `80 % in ca. 25 Min. · Voll ca. 23:25` | wie 1 | charging | still |
| 2b | Langsam / Defizit | `Lädt langsam · 4,5 W` / `Eingesteckt, entlädt trotzdem` | Restzeit / `Ladegerät zu schwach.` | wie 1 | charging | still |
| 2d | Warm (≥ 40 °C) | `Akku warm: 41 °C · 18 W` | wie 2 | wie 1 | heat | Heads-up 1× |
| 3 | Ziel erreicht | `Ladeziel 80 % erreicht` | `Jetzt abstecken, um den Akku zu schonen.` | `Verstanden` | target_alarm | Ton 1×, Erinnerung nach 10 Min. |
| 4 | Voll | `Voll geladen` | `Seit 23:25 bei 100 %` | – | charging | still |
| 5 | Abgesteckt | `Ladevorgang beendet` | `23 % → 81 % in 52 Min. · Ø 15 W, max. 25 W` | Tippen → Graph 2 | charging | still, nach 15 Min. → 0 |

Nach `Verstanden` wechselt sie still zurück auf `charging`, Titel `Über Ladeziel · 83 %`. Der Balken zeigt den Akkustand mit Marke am Ziel (ab Android 16 `ProgressStyle` mit Chip `18 W`). `Ziel 80 %` schaltet 80 → 85 → 90 → 100 durch, `Alarm aus` gilt nur für diesen Vorgang.

**Unterschiede in (b):**
- 0 und 0h entfallen.
- 1 und 2 kommen verspätet, mit `Erfasst seit 22:15 · 80 % in ca. 20 Min.`
- Neuer Zustand **3x**: Das Ziel ist beim Start schon überschritten. Titel `Ladeziel 80 % überschritten · 86 %`, Text `Die Aufzeichnung startete erst nach dem Einstecken. Jetzt abstecken.` Kanal target_alarm, Ton 1×.
- 5 zeigt `Erfasst ab 57 % → 81 % · Beginn nicht erfasst`. Die Benachrichtigung bleibt per `DETACH` stehen und lässt sich wegwischen.

### Onboarding

> **Wie soll der Batterymanager arbeiten?**
> ◉ **Immer aktiv** (empfohlen)
> Lade-Info sofort beim Einstecken, vollständiger Verlauf, Verbrauch getrennt nach Bildschirm an und aus. Dafür zeigt Android dauerhaft eine kleine, stumme Benachrichtigung. *Mehrverbrauch: [Messwert]*
> ○ **Nur beim Laden**
> Keine Dauer-Benachrichtigung. Die Lade-Info erscheint meist erst 15–20 Minuten nach dem Einstecken, ein früh erreichtes Ladeziel kann verpasst werden. Beim Entladen läuft nichts: Der Verlauf hat Lücken, den Verbrauch gibt es nur als Durchschnitt. Spart kaum Akku.
> *Jederzeit in den Einstellungen änderbar.* **[Weiter]**

**Reihenfolge der Berechtigungen:**
1. `Damit beim Laden Watt und Restzeit erscheinen, braucht der Batterymanager Benachrichtigungen.` → `POST_NOTIFICATIONS`
2. Akku-Optimierungs-Ausnahme, in (a) `Empfohlen, damit Android den Dienst nicht beendet.` [Erlauben] [Später]; in (b) `Erforderlich, sonst startet „Nur beim Laden“ nicht.` [Erlauben] [Zu „Immer aktiv“ wechseln]
3. Bei Xiaomi, Samsung oder Huawei: Link zur Hersteller-Einstellung.

### Screens

**Übersicht** (scrollend): ein Banner-Platz (Vorrang: Benachrichtigungen aus > Dienst beendet > Ausnahme fehlt > Alarm stumm) · Hero mit `64 %`, `Lädt mit 18 W` bzw. `Entlädt · 6 %/h`, Restzeit und Chip `Ladeziel 80 %` · Werte-Raster (Temperatur, Zustand, Spannung, Strom, Kapazität, Zyklen; fehlende Werte ausgeblendet) · `[24 Std. │ 7 Tage]` und drei Graphen.

**Ladeziel-Sheet:** Auswahl `80 │ 85 │ 90 │ 100 %`, `Eigener Wert` (50–100 %), Schalter `Alarm, wenn erreicht`.

**Einstellungen:**
- *Hintergrundbetrieb:* gleicher Wortlaut wie im Onboarding. In (b) zusätzlich `Startverzögerung zuletzt: 17 · 16 · 19 Min.`
- *Ladeziel:* Wert, Alarm, Erinnerung.
- *Benachrichtigungen:* Zusammenfassung, Hitzewarnung ab `40 °C`, Link zu den Android-Kanälen.
- *Status:* Benachrichtigungen ✓/✗, Akku-Ausnahme ✓/✗.
- *Diagnose:* Messwerte korrigieren (Vorzeichen, Einheit), `Alle Daten löschen` mit Bestätigung.

### Leer-, Lücken- und Fehlerzustände

| Situation | Microcopy |
|---|---|
| Graph 1 leer | `Der Verlauf füllt sich ab jetzt. Schau in ein paar Stunden wieder vorbei.` |
| Graph 2 leer | `Noch kein Ladevorgang aufgezeichnet. Steck dein Gerät ein.` In (b) zusätzlich: `Die Aufzeichnung startet meist 15–20 Min. später.` |
| Graph 3 leer | `Dein Verbrauch erscheint nach einigen Stunden ohne Ladegerät.` |
| Graph 3 in (b) | Titel `Verbrauch (Durchschnitt)`, Fußnote `Ohne Aufteilung Bildschirm an/aus.` [Ändern] |
| Lücke in (b) | gestrichelt dargestellt: `Gestrichelt: nur Anfang und Ende bekannt.` |
| Lücke in (a) | schraffiert dargestellt: `Keine Daten – Android hat den Dienst beendet.` |
| Benachrichtigungen aus | `Benachrichtigungen sind aus – ohne sie gibt es keine Lade-Info.` [Einschalten] |
| Ausnahme fehlt (b) | `„Nur beim Laden“ braucht die Ausnahme von der Akku-Optimierung. Bis dahin gibt es Lade-Info nur bei geöffneter App.` [Erlauben] |
| Dienst beendet | `Android hat den Hintergrunddienst beendet. Seit 14:02 fehlen Daten.` [Neu starten] |
| (b) startet nicht | `„Nur beim Laden“ ist bei den letzten 2 Ladevorgängen nicht gestartet.` [Zu „Immer aktiv“ wechseln] |
| Wert unplausibel | `–`, mit dem Hinweis `Dieses Gerät meldet keinen verlässlichen Wert.` |

### Desktop

- **Onboarding:** keine Moduswahl, nur `Beim Anmelden im Hintergrund starten` (an) mit `Der Verlauf wird nur aufgezeichnet, solange der Batterymanager läuft.`
- **Toasts statt Live-Benachrichtigung:** für 2 (nach der Ermittlung), 2d, 3 und 5; Linux mit `replaces_id`.
- **Tray:** Tooltip `64 % · Lädt mit 45 W · 80 % ca. 22:40`. Ohne Tray: `Kein Tray verfügbar – Infos nur über Benachrichtigungen.`
- **Fenster:** Schließen minimiert unter Windows in den Tray (einmaliger Hinweis). Ab 840 dp zwei Spalten, voll per Tastatur bedienbar. Ohne Akku: `Kein Akku gefunden.`
