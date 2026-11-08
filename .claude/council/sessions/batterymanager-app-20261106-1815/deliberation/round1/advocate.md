## Advocate Position — Batterymanager (IA, Lade-Benachrichtigung, Onboarding)

**Kernempfehlung:** Im Alltag ist der Batterymanager eine Benachrichtigung, keine App. Die Lade-Benachrichtigung ist das Hauptprodukt und muss ohne App-Öffnen drei Fragen beantworten: Wie schnell? Wann fertig? Wann abstecken? Die App selbst kommt mit zwei Screens aus (scrollende Übersicht + Einstellungen), dazu ein einmaliger Onboarding-Screen.

**Hauptargument:**
Mathias steckt abends sein Handy ein. Er öffnet nichts, er zieht die Statusleiste herunter und will wissen: Lädt es schnell? Wann ist es bei 80 %? Muss ich gleich abstecken? Health, Zyklen und Graphen sind Neugier für einmal pro Woche. Daraus folgt: (1) Die Benachrichtigung braucht ein lückenloses Zustandsmodell, von „Leistung wird ermittelt“ bis zur Zusammenfassung nach dem Abstecken, und fällt nur zweimal auf: still aufpoppen beim Einstecken, alarmieren beim Ziel. (2) Die App ist eine Lese-Oberfläche: Hero-Zone mit %, Watt und Restzeit, darunter Sekundärwerte, darunter die drei Graphen mit *einem* 24h/7d-Schalter. Eine Bottom-Navigation für zwei Ziele wäre Ballast. (3) Ehrlichkeit vor Präzision: „ca.“, grobe Rundung und „wird berechnet …“ schlagen eine springende Minutenzahl. (4) Die Dauerdienst-Wahl ist die einzige echte Entscheidung des Nutzers und wird in Konsequenzen beschrieben, nicht in Technikbegriffen. Benachrichtigungs-Aktionen laufen nativ, ohne Flutter-UI: die schnellste und zugleich stromsparendste UX.

**Risiken, wenn ignoriert:**
- Benachrichtigungs-Lärm (Ton/Vibration bei Updates, zwei Benachrichtigungen gleichzeitig) → Nutzer schaltet den Kanal ab → Kernfunktion tot.
- Springende Restzeit und unerklärte Graph-Lücken (Modus „Nur beim Laden“) wirken wie Bugs → Misstrauen gegenüber *allen* Werten.
- Unklare Dauerdienst-Wahl oder unbemerkt abgelehnte Benachrichtigungs-Berechtigung (Android 13+) → „Popup kommt nicht“ wird als Defekt erlebt.

**Abhängigkeiten zu anderen Council-Mitgliedern:**
- **Pathfinder:** Drei Kanäle (Anhang B), Heads-up nur beim ersten Post, danach `setOnlyAlertOnce`. Beim Laden *wird* die Dienst-Benachrichtigung zur Live-Benachrichtigung (gleiche ID, nie zwei) – geht das trotz Kanalwechsel? Android 16+: `ProgressStyle`/Live Update mit Ziel-% als Punkt auf dem Balken. Aktionen per nativem BroadcastReceiver. Desktop: Toast bzw. libnotify mit `replaces_id`.
- **Tuner:** Gemessener Mehrverbrauch des Dauerdiensts (nur dann steht eine Zahl im Onboarding). Update-Takt, Vorschlag: bei Δ ≥ 0,5 W oder Δ 1 %, höchstens alle 15 s.
- **Architect:** Restzeit-Schätzung mit Konfidenz („ca. X“ vs. „wird berechnet“). Explizite Lücken (Abdeckungsintervalle), Ladevorgang als Entität (Start/Ende %, Ø/max. W), Verfügbarkeits-Flags je Wert und Plattform.
- **Artisan:** Hero-Typografie mit tabellarischen Ziffern, Lücken-Darstellung, „Bildschirm an/aus“ doppelt kodiert (Farbe + Muster), Warnfarben aus M3-Rollen.
- **Skeptic:** Vom System beendeten Dienst erkennen → sichtbarer Hinweis. Unplausible Messwerte (Vorzeichen/Einheit) ausblenden statt falsch anzeigen.

---

## Anhang (Skill: interaction-design)

### A — Screen-Inventar

| Screen | Inhalt | Zugang |
|---|---|---|
| **Onboarding** (1×) | Dauerdienst-Wahl, Benachrichtigungs-Berechtigung | Erster Start |
| **Übersicht** (scrollend) | 1. Hero: `64 %` groß · `Lädt · 18 W` bzw. `Entlädt · 6 %/h` · Restzeit · Chip `Ladeziel 80 %` · 2. Werte-Raster (2 Spalten): Temperatur, Zustand, Spannung, Strom, Kapazität, Zyklen · 3. `[24 Std. │ 7 Tage]` + 3 Graph-Karten | Start |
| **Einstellungen** | Hintergrundbetrieb, Ladeziel & Alarm, Zusammenfassung nach dem Laden an/aus, Hitzewarnung ab __ °C, Über/Diagnose | Zahnrad oben rechts |

**Primär:** Akkustand, Leistung in W (Laden) bzw. %/h (Entladen), Restzeit. **Sekundär:** Temperatur (bei Hitze ein Warnbanner im Hero), Zustand, Spannung, Strom (mA), Kapazität, Zyklen. Werte, die das Gerät nicht meldet: Kachel ausblenden. Geschätzte Werte: „ca.“ plus Info „geschätzt aus Ladevorgängen“. Desktop ab 840 dp: zwei Spalten (links Hero + Werte, rechts Graphen).

### B — State-Matrix Lade-Benachrichtigung (Android)

Kanäle: `Laden` (hoch, einmal Heads-up, dann still) · `Ladeziel-Alarm` (hoch, Ton + Vibration) · `Hintergrunddienst` (minimal, stumm).

| # | Zustand | Auslöser | Titel | Text | Aktionen | Signal |
|---|---|---|---|---|---|---|
| 0 | Dienst aktiv | Dauerdienst, nicht am Laden | `Akku 64 %` | `Verbrauch ca. 6 %/h` | – | keins, minimiert |
| 1 | Eingesteckt | Netzteil angesteckt | `Lädt · 42 %` | `Ladeleistung wird ermittelt …` | `Ziel 80 %` · `Kein Alarm` | Heads-up, ohne Ton |
| 2 | Lädt | Messwert stabil (ca. 30 s) | `Lädt mit 18 W · 42 %` | `80 % in ca. 25 Min. · Voll in ca. 1 Std. 10 Min.` | wie 1 | Update, still |
| 2b | Langsam/Defizit | < 5 W bzw. Strom negativ | `Lädt langsam · 4,5 W` / `Eingesteckt, entlädt trotzdem` | `Voll in ca. 4 Std.` / `Ladegerät zu schwach für die aktuelle Nutzung` | wie 1 | still |
| 2c | Warm | ≥ 40 °C (einstellbar) | `Lädt mit 18 W · Akku warm: 41 °C` | Restzeit wie 2 | wie 1 | einmal Heads-up |
| 3 | Ziel erreicht | % ≥ Ziel | `Ladeziel 80 % erreicht` | `Jetzt abstecken, um den Akku zu schonen.` | `Verstanden` | Ton + Vibration einmal, Erinnerung nach 10 Min., falls noch eingesteckt |
| 4 | Voll | 100 % | `Voll geladen` | `Seit 14:32 bei 100 %` | `Verstanden` | still (Alarm nur bei Ziel 100 %) |
| 5 | Abgesteckt | Netzteil getrennt | `Ladevorgang beendet` | `23 % → 81 % in 52 Min. · Ø 15 W, max. 25 W` | Tippen → Ladeleistungs-Graph | still, verschwindet nach 15 Min. |

Der Fortschrittsbalken zeigt in den Zuständen 1–4 den Akkustand mit einer Marke beim Ziel-%. `Ziel 80 %` schaltet durch (80 → 85 → 90 → 100 → 80), das Label zeigt immer den *aktuellen* Wert. `Kein Alarm` gilt nur für diesen Ladevorgang. Beides wirkt ohne App-Start. Im Modus „Nur beim Laden“ kommt Zustand 1 verspätet; der Text lautet dann `Lädt seit ca. 3 Min.` statt einer erfundenen Restzeit.

```
[0 Dienst] →einstecken→ [1 Ermittle] →stabil→ [2 Lädt] ⇄ [2b / 2c]
[2] →Ziel→ [3 Ziel erreicht] →100 %→ [4 Voll]
[1–4] →abstecken→ [5 Zusammenfassung] →15 Min. / wegwischen→ [0]
```

**Desktop:** Kein live tickernder Toast. Ereignis-Toasts für 1 (nach der Ermittlung: `Lädt mit 45 W · 80 % in ca. 40 Min.`), 3 und 5. Live-Werte im Tray-Tooltip. Linux: libnotify mit `replaces_id`, sonst identisch.

### C — Onboarding (1 Screen) und Einstellungen

> **Wie soll der Batterymanager arbeiten?**
> ◉ **Immer bereit:** Lade-Info sofort beim Einstecken, Verlauf ohne Lücken. Dafür zeigt Android dauerhaft eine kleine, stumme Benachrichtigung. *(Mehrverbrauch: [Messwert von Tuner])*
> ○ **Nur beim Laden:** Keine Dauer-Benachrichtigung. Die Lade-Info erscheint bis zu einige Minuten später, der Verlauf hat Lücken.
> *Du kannst das jederzeit in den Einstellungen ändern.*
> **[Weiter]** → System-Dialog für Benachrichtigungen (Android 13+)

Bei Ablehnung zeigt die Übersicht den Banner `Benachrichtigungen sind aus – ohne sie gibt es keine Lade-Info.` [`Einschalten`]. Bei „Immer bereit“ folgt optional die Akku-Optimierungs-Ausnahme: `Damit Android den Dienst nicht beendet.` Die Einstellungen zeigen dieselben zwei Optionen wortgleich. Vorausgewählt ist „Immer bereit“, sofern Tuner den Mehrverbrauch als vernachlässigbar bestätigt.

### D — Ladeziel, Restzeit, Graphen

- **Ladeziel:** Chip im Hero → Bottom-Sheet mit SegmentedButton `80 │ 85 │ 90 │ 100 %`, Schalter `Alarm, wenn erreicht`, aufklappbar `Eigener Wert` (Slider 50–100 %, 5er-Schritte). Das ist die einzige Bearbeitungsstelle in der App; die Einstellungen verlinken dorthin.
- **Restzeit:** Immer „ca.“, gerundet (unter 10 Min. auf 1 Min., unter 1 Std. auf 5, darüber auf 10), geglättet. Bei niedriger Konfidenz `Restzeit wird berechnet …`. Formate: `1 Std. 10 Min.`, `18,4 W` (Benachrichtigung ab 10 W ganze Watt, weniger Flackern), `4.320 mAh`, `37,5 °C`.
- **Graphen:** Tippen/Ziehen zeigt Wert + Uhrzeit (Desktop: Hover, Pfeiltasten). Ladeleistung: Auswahl `‹ Heute 14:02 (23 → 81 %) ›`, darunter Ø/max./Dauer. Verbrauch: beschriftete Balken Bildschirm an/aus. Lücken nicht interpoliert, sondern schraffiert, mit Fußnote `Keine Daten – Hintergrunddienst war aus.` [`Ändern`].
- **Leer-Zustände:** `Der Verlauf füllt sich ab jetzt. Schau in ein paar Stunden wieder vorbei.` · `Noch kein Ladevorgang aufgezeichnet. Steck dein Gerät ein.` · `Dein Verbrauch erscheint nach einigen Stunden ohne Ladegerät.`

### E — Barrierefreiheit (Checkliste)

- [ ] Hero als *eine* Semantics-Einheit: „Akku 64 Prozent, lädt mit 18 Watt, 80 Prozent in etwa 25 Minuten“; Einheiten ausgeschrieben.
- [ ] Live-Region nur bei Zustandswechseln (eingesteckt, Ziel erreicht, abgesteckt), nicht bei jedem Watt-Update.
- [ ] Jeder Graph mit Textzusammenfassung: „24 Stunden: Minimum 18 % um 7 Uhr, 2 Ladevorgänge, jetzt 64 %“.
- [ ] Warnungen als Icon + Text + Farbe. Graph-Linien ≥ 3:1, Text ≥ 4,5:1, hell und dunkel, auch mit Dynamic Color.
- [ ] Textskalierung bis 200 %: Werte-Raster bricht auf eine Spalte um, keine festen Höhen.
- [ ] Touch-Ziele ≥ 48 dp; Desktop komplett per Tastatur (Tab = visuelle Reihenfolge, Pfeiltasten, Esc schließt Sheet).
- [ ] „Animationen entfernen“ respektieren; Zahlenwechsel max. 200 ms Überblendung.
- [ ] Benachrichtigungs-Aktionen mit Inhaltsbeschreibung („Ladeziel 80 Prozent, tippen zum Ändern“).
