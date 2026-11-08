## Artisan Final Position — Visuelles System für batterymanager

**Überarbeitete Empfehlung:** Das kleine M3-System aus Round 1 bleibt: ein Farbpfad für alle Plattformen (`DynamicColorBuilder` → Seed → `fromSeed`), eine `ThemeExtension` `BatteryColors`, Systemschrift mit Tabellenziffern, keine Daueranimation. Round 2 macht das System vor allem **ehrlicher**: Lücken werden nie überbrückt (Schraffur statt gepunktetem Verbinder). Modus (b) „Nur beim Laden“ bekommt eigene Chart-Varianten mit gleicher Kachelhöhe. Der Hauptscreen folgt Advocates IA. Desktop-Feinschliff kommt in Phase 3/4, ohne den Codepfad zu verzweigen.

**Zugeständnisse:**
- **Gepunkteter Lücken-Verbinder gestrichen.** Stattdessen: Schraffur (Advocate) plus isolierte Ankerpunkte. Ein Verbinder ist visuell eine Interpolation, und Skeptics „nie interpolieren“ gilt auch für Pixel.
- **`setChronometerCountDown` gestrichen.** Ein Sekunden-Countdown suggeriert eine Präzision, die die ETA nicht hat. Advocates gerundetes „ca.“ gewinnt.
- **Werteraster mit 2 statt 3 Spalten**, Formate „Std./Min.“ (Advocate). Bei 200 % Textgröße bricht es auf eine Spalte um.
- **Graph 3 zeigt in (b) nur einen Ø-Balken**, ohne Bildschirm-Split. Die Kachel bleibt gleich hoch, damit ein Moduswechsel das Layout nicht springen lässt.
- **Desktop später:** Zweispalten-Layout (≥ 840 dp), Tray und `.ico` kommen erst in Phase 3 (Linux) bzw. 4 (Windows). Das Breakpoint-Token gibt es ab Phase 1.
- **Hero linksbündig:** eine Fluchtlinie für Hero, Raster und Chart-Titel. Funktioniert unverändert in der 360-dp-Desktop-Spalte.

**Non-negotiables:**
1. **Kein Dauer-Ticker:** keine unbestimmten Indikatoren, kein Shimmer, kein Pulsieren, kein Einblenden von Charts. Zahlen wechseln in 0 ms. Bei `disableAnimations` gilt für alles 0 ms.
2. **Lücken exklusiv kodiert:** Die Linie bricht ab, das Band wird schraffiert. Schraffur bedeutet nur „keine Daten“. Ein hohler Punkt heißt „Aufzeichnung beginnt“, nie „Ladebeginn“.
3. **Farben nur aus Tokens** (`ColorScheme` + `BatteryColors`, harmonisiert): Text ≥ 4,5:1, Linien und Schraffur ≥ 3:1, hell wie dunkel. Keine Hexwerte in Widgets.
4. **Zustand nie nur über Farbe:** Laden = Linie plus Fläche. Warnung = Icon + Text. In der Benachrichtigung steht der Zustand im Titel, `setColor` bleibt konstant, nie `setColorized`.
5. **Zahlen immer tabellarisch und im Format `de_DE`**, mit U+202F vor der Einheit. Unplausible Werte werden ausgeblendet, unkalibrierte bekommen „ca.“. Rohwerte erscheinen nie.

### Implementierungshinweise

**Theme** (`lib/ui/theme/{tokens,battery_colors,app_theme}.dart`):
```dart
const kSeed = Color(0xFF4A6FA5);
const _tnum = [FontFeature.tabularFigures()];

Widget app() => DynamicColorBuilder(builder: (l, d) => MaterialApp(
  themeMode: ThemeMode.system, locale: const Locale('de', 'DE'),
  theme: buildTheme(l?.primary ?? kSeed, Brightness.light),
  darkTheme: buildTheme(d?.primary ?? kSeed, Brightness.dark),
  home: const OverviewScreen()));

// Immer aus dem Seed neu erzeugen: vollständige surfaceContainer*-Rollen,
// derselbe Pfad für Wallpaper (Android 12+), Desktop-Akzent und Fallback.
ThemeData buildTheme(Color seed, Brightness b) {
  final s = ColorScheme.fromSeed(seedColor: seed, brightness: b);
  final base = ThemeData(colorScheme: s);
  TextStyle? n(TextStyle? t) => t?.copyWith(fontFeatures: _tnum);
  final tt = base.textTheme;
  return base.copyWith(
    textTheme: tt.copyWith(displayLarge: n(tt.displayLarge),
      headlineSmall: n(tt.headlineSmall), titleLarge: n(tt.titleLarge),
      titleMedium: n(tt.titleMedium), bodyMedium: n(tt.bodyMedium),
      labelMedium: n(tt.labelMedium), labelSmall: n(tt.labelSmall)),
    extensions: [BatteryColors.from(s)],
    cardTheme: CardThemeData(elevation: 0, margin: EdgeInsets.zero,
      color: s.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))));
}

@immutable
class BatteryColors extends ThemeExtension<BatteryColors> {
  const BatteryColors({required this.charge, required this.onCharge,
    required this.chargeContainer, required this.onChargeContainer,
    required this.warm, required this.hot, required this.gap});
  final Color charge, onCharge, chargeContainer, onChargeContainer, warm, hot, gap;

  factory BatteryColors.from(ColorScheme s) {
    ColorScheme role(int src) => ColorScheme.fromSeed(
      seedColor: Color(src).harmonizeWith(s.primary), // dynamic_color, ≤15°
      brightness: s.brightness,
      dynamicSchemeVariant: DynamicSchemeVariant.fidelity); // Ton 40/80
    final c = role(0xFF2E9E4F), w = role(0xFFE8A13A);
    return BatteryColors(charge: c.primary, onCharge: c.onPrimary,
      chargeContainer: c.primaryContainer, onChargeContainer: c.onPrimaryContainer,
      warm: w.primary, hot: s.error, gap: s.outline);
  }
  @override BatteryColors copyWith({/* je Feld */}) => this;
  @override BatteryColors lerp(BatteryColors? o, double t) => o == null ? this
    : BatteryColors(charge: Color.lerp(charge, o.charge, t)!, /* … alle Felder */);
}
```
Der Hero ist auf `textScaler.clamp(maxScaleFactor: 1.5)` begrenzt, alle anderen Texte skalieren frei. Liegt die Primärfarbe nahe am Lade-Grün, unterscheidet die Flächenfüllung die Phasen, nicht der Farbton. `dynamic_color` unter Linux (GTK-Akzent) wird in Phase 3 geprüft, sonst gilt `kSeed`.

**Hauptscreen** (Laden, Modus a):
```
┌──────────────────────────────────────┐
│ Batterymanager                    ⚙  │ titleLarge
│ ╭──────────────────────────────────╮ │ Banner-Slot, nur bei Bedarf
│ │ (i) Benachrichtigungen sind aus. │ │ surfaceContainerHigh,
│ │                       Einschalten│ │ Icon + Text + TextButton
│ ╰──────────────────────────────────╯ │
│ 64 %                                 │ hero; „%“ in text-label
│ ↯ Lädt · 18,4 W                      │ hero-sub, charge + Icon
│ ! Akku warm: 41 °C                   │ nur bei Hitze: warm/hot + Icon
│ ▕██████████████████░░░░┆░░░░░▏       │ 8 dp, Ziel-Marke ┆
│ 80 % in ca. 25 Min.                  │ titleMedium
│ Voll in ca. 1 Std. 10 Min.           │ bodyMedium, text-label
│ ( Ladeziel 80 % )                    │ AssistChip → Bottom-Sheet
│                                      │ space-xl
│ 31,2 °C            Gut               │ metric
│ Temperatur         Zustand           │ label
│ 4,21 V             1.840 mA          │
│ Spannung           Strom             │
│ ca. 4.410 mAh      412               │ fehlt der Wert: Zelle entfällt
│ Kapazität          Zyklen            │
│                                      │
│ [ 24 Std. │ 7 Tage ]                 │ gilt für alle 3 Graphen
│ Akkustand                            │ titleSmall
│ ╭──────────────────────────────────╮ │ surfaceContainerLow, r 12
│ │100                      ┄┄ 80 %  │ │
│ │ ‾‾╲___ ▨▨▨▨▨ ○╱‾‾‾‾╲__   •       │ │ ▨ Lücke, ○ Aufzeichnung ab
│ │  0  06     12     18    jetzt    │ │
│ ╰──────────────────────────────────╯ │
│ ▨ Keine Daten – Dienst war aus. Ändern│ nur bei Lücke im Fenster
│ Ladeleistung       ‹ Heute 14:02 ›   │
│ ╭──────────────────────────────────╮ │
│ │25 W ‾‾‾‾‾‾‾‾‾‾‾‾╲___             │ │
│ │ 0 %        50 %          100 %   │ │
│ ╰──────────────────────────────────╯ │
│ 23 → 81 % · Ø 15,2 W · max. 25 W     │
│ Entladerate                          │
│ Bildschirm an   ██████████  9,1 %/h  │
│ Bildschirm aus  ▭           0,8 %/h  │
│ ≈ 5 Tage Standby                     │
└──────────────────────────────────────┘
```
Entladen, Modus (b): Der hero-sub lautet „Entlädt · Ø 6 %/h seit 08:10“.

**Charts.** Für alle drei gilt: `CustomPainter` in einer `RepaintBoundary`, das Fadenkreuz auf einer eigenen Overlay-Ebene, Min/Max-Bucketing auf höchstens 1 Punkt pro Pixel. Lücken kommen aus den Abdeckungsintervallen, sie werden nicht aus Zeitabständen erraten. Der Path-Cache-Schlüssel ist Zeitfenster + `device_profile`-Version, damit eine rückwirkende Normalisierung sofort greift. Jeder Chart hat eine `Semantics`-Textzusammenfassung.

| | 1 Akkustand | 2 Ladeleistung | 3 Entladerate |
|---|---|---|---|
| Form | Linie 2 dp, Höhe 160 dp | Linie 2 dp, Höhe 140 dp | 2 Zeilen à 28 dp, Balken 12 dp, Radius full |
| Achsen | x = Zeit, y = 0–100 fix, Gitter bei 50 | x = Akku-% 0–100, y = 0 bis zum runden Wert (5/10/15/25/45/65/100 W) | direkt beschriftet, Wert rechts |
| Kodierung | Entladen `primary`, Laden `charge` + 12-%-Fläche, Ziel gestrichelt in `outline` | gewählter Vorgang `charge`, bis zu 3 frühere `outlineVariant` 1,5 dp | an: gefüllt `primary`; aus: `primaryContainer` mit 1,5-dp-Kontur in `primary` |
| Lücken | Linie bricht, Band in voller Höhe schraffiert (`gap`, 45°, 1 dp, Abstand 6 dp), isolierte Messpunkte r = 3 dp | Bruch, x-Abschnitt schraffiert | – |
| Modus (b) | Entladephasen = Schraffur zwischen Ankerpunkten (Abstecken, App-Öffnen); Ladesegment startet hohl beim Dienststart; Fußnote: „Im Modus ‚Nur beim Laden‘ wird beim Entladen nicht aufgezeichnet. Ändern“ | Kurve startet hohl, Kopfzeile: „ab 47 → 81 % · ≥ 38 Min.“ | Zeile 1: „Ø zwischen Ladevorgängen“; Zeile 2 als Text: „Bildschirm an/aus nur mit ‚Immer bereit‘ · Ändern“ |
| Sonderfall | – | Profil unbestätigt: keine Kurve, nur „Leistung wird kalibriert“ | „Noch kein Wert“ |

**Benachrichtigung:**
- **Small Icons** (24-dp-Vektor, nur Alpha, 2 dp Strich, 2 dp Rand): `ic_stat_battery` (Umriss, nur Dauerdienst), `ic_stat_charging` (Umriss + Blitz; Zustände 1–2c, 4, 5), `ic_stat_target` (Umriss + Häkchen; Ziel-Alarm).
- **Farbe:** `setColor(R.color.notification_accent)`, in `values-v31` → `@android:color/system_accent1_600`, sonst `#4A6FA5`. Nie colorized, das schließt auch Live Updates unter Android 16 aus.
- **Fortschritt** ist immer bestimmt: `setProgress(100, level, false)`. Ab API 36 `ProgressStyle` mit Segment [0, Ziel] im Akzent, [Ziel, 100] neutral, `Point` am Ziel. In Modus (b) gleiche Optik, nur der Text ist ehrlich formuliert (Advocate).

**App-Icon:** senkrechte Kapsel mit Pol-Nase, zu ⅔ gefüllt, ohne Blitz, aus einer Quell-SVG (`assets/icon/battery.svg`).
- **Android:** adaptiv (Glyphe in der 66-dp-Safe-Zone, Hintergrund Seed Ton 90, Glyphe Ton 30) plus Monochrom-Ebene. Ab API 31: `windowSplashScreenBackground` = Surface hell/`values-night`, Launch-Theme DayNight, damit kein weißer Blitz entsteht.
- **Phase 3:** hicolor-SVG + `battery-symbolic.svg`.
- **Phase 4:** `.ico` 16–256 px, Tray hell/dunkel nach `AppsUseLightTheme`.

Tray-Icons bleiben statisch, ohne Pegelanzeige.
