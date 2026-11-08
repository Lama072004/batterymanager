## Artisan Position — Visuelles System für batterymanager

**Kernempfehlung:** Ein bewusst kleines Design-System auf Flutter-M3-Bordmitteln: `DynamicColorBuilder` liefert das ColorScheme (Android 12+: Wallpaper-Farben, Windows/Linux: Systemakzent, sonst Seed `#4A6FA5`). Dazu kommt **eine** `ThemeExtension` mit vier harmonisierten Zustandsfarben, Systemschrift mit Tabellenziffern und keinerlei Daueranimation. Farbe signalisiert nur Zustandswechsel (Laden, Ziel, Hitze), sie dient nicht als Dekoration.

**Hauptargument:**
Die App wird nur kurz geöffnet. Visuell ist sie in erster Linie eine **Zahl**, nämlich Akku-%, Watt und Restzeit. Die Hierarchie muss deshalb in drei Sekunden lesbar sein: zuerst der Akkustand, dann der Zustand mit Leistung, dann die Detailwerte. Größe und Gewicht tragen diese Hierarchie, Farbe kommt erst danach. Material You gibt jedem Gerät eine andere Primärfarbe. Feste Hexwerte für „lädt“ oder „heiß“ würden deshalb auf manchen Paletten unlesbar oder fremd wirken. Ich leite diese Farben aus HCT-Tönen ab: Der Farbton wird per `harmonizeWith(primary)` um höchstens 15° Richtung Primärfarbe verschoben, die Tonwerte ergeben sich aus dem M3-Tonalsystem. Damit liegt der Kontrast **konstruktionsbedingt** bei mindestens 4,5:1. Stromsparen ist auch eine Designfrage. Unbestimmte Spinner, Shimmer, pulsierende Blitz-Icons, Hochzähl-Animationen und Blur halten den 60-fps-Ticker wach. Sie sind deshalb verboten, nicht bloß unerwünscht. Das ist der eigentliche Beitrag des Designs zum Stromsparen. Echtes Schwarz bringt dagegen kaum etwas.

**Risiken, wenn ignoriert:**
- **Kontrastbruch unter fremden Paletten:** Ein festes Grün `#4CAF50` für „lädt“ liegt auf hellem Surface bei etwa 2,8:1 und verfehlt damit AA. Auf grünen Wallpapers verschmilzt es zudem mit der Primärfarbe.
- **Stromverbrauch durch Bewegung:** Ein einziger `CircularProgressIndicator()` ohne Wert oder ein „atmendes“ Lade-Icon erzeugt Dauer-Frames und widerspricht der obersten Projektpriorität.
- **Zappelnde Zahlen und Token-Wildwuchs:** Ohne Tabellenziffern springt die Breite von „18,4 W“ bei jedem Update. Ohne zentrale Tokens entstehen pro Screen eigene Abstände und Farben, und Hell/Dunkel driftet auseinander.

**Abhängigkeiten zu anderen Domänen:**
- **Pathfinder:** Native Notification (monochromes Small Icon, `setColor` mit `system_accent1_600` ab API 31), adaptives und monochromes Launcher-Icon, Launch-Theme ohne weißen Blitz, Edge-to-Edge. Zu prüfen: `dynamic_color` unter Windows und Linux.
- **Tuner:** UI-Updates ereignisgetrieben mit höchstens 1 Hz und nur bei sichtbarer App. Entscheidung CustomPainter oder fl_chart (ich bevorzuge CustomPainter). Downsampling auf höchstens 1 Punkt pro Pixel.
- **Architect:** Das Datenmodell braucht Phasen-Flag (lädt/entlädt), Bildschirmzustand, Lücken-Marker, Sitzungsgrenzen und Verfügbarkeits-Flags je Messwert. Die Chart-Kodierung baut darauf auf.
- **Advocate:** Navigation (ein Scroll-Screen oder Tabs), deutsche Zahlen- und Einheitenformate, Notification-Texte, Hitzeschwellen. Zustände nie nur über Farbe zeigen, immer auch Icon und Text.
- **Skeptic:** Grenzfälle der Harmonisierung (Primärfarbe ≈ Lade-Grün), Tabellenziffern-Support von Linux-Fonts, OEMs, die `setColor` ignorieren.

---

## Anhang: Design System Architecture (Skill-Output)

### Schritt 1: Ausgangslage
Es gibt keine Tokens: nur `ColorScheme.fromSeed(deepPurple)`, kein `darkTheme`, kein `themeMode`. Das Android-Launch-Theme nutzt `Theme.Light`/`Theme.Black`, dadurch flackert es beim Start. Der Windows-Runner setzt die dunkle Titelleiste bereits selbst (`UpdateTheme`). Neu nötig ist nur `dynamic_color`. Eigene Fonts oder `google_fonts` (Netzwerk!) kommen nicht infrage.

### Primitive Tokens
| Kategorie | Token | Wert |
|---|---|---|
| Farbquelle | seed (Fallback) | `#4A6FA5` Stahlblau, Farbton ≈215°, bewusst weit entfernt von Grün und Rot |
| Farbquelle | src-charge | `#2E9E4F` (Farbton ≈140°) |
| Farbquelle | src-warm | `#E8A13A` (Farbton ≈70°) |
| Abstand | space-xs/sm/md/lg/xl | 4 / 8 / 16 / 24 / 32 dp |
| Radius | radius-sm/md/full | 8 / 12 / 999 dp |
| Linie | stroke-grid / stroke-data / bar-progress | 1 / 2 / 8 dp |
| Dauer | dur-0 / dur-short / dur-medium | 0 / 150 / 250 ms |
| Kurve | ease | `Easing.standard` |

Kein Shadow-Token: Ebenen entstehen über `surfaceContainer*`, nicht über Schatten.

### Semantische Tokens (Hell/Dunkel automatisch aus dem Scheme)
| Token | Quelle | Verwendung |
|---|---|---|
| surface | `surface` | Hintergrund |
| surface-tile | `surfaceContainerLow` | Chart-Kacheln |
| text-value | `onSurface` | Messwerte |
| text-label | `onSurfaceVariant` | Labels, Einheiten, Achsen |
| line-grid | `outlineVariant` | Gitter, Lücken-Verbinder |
| accent | `primary` | Akku-Pegel und Linie beim Entladen, aktive Bedienelemente |
| state-charge | `BatteryColors.charge` | lädt: Watt-Zeile, Ladesegmente, Fortschritt |
| state-target | `chargeContainer`/`onChargeContainer` | Ziel erreicht (Banner) |
| state-warm | `BatteryColors.warm` | Temperatur ab Warnschwelle 1 |
| state-hot | `error` | Temperatur ab Schwelle 2 (Advocate/Pathfinder) |

„Entlädt“ ist der Normalzustand und bekommt deshalb keine Signalfarbe: neutraler Text, Pegel in `primary`. Kontrast: Rolle Ton 40 auf Surface Ton 98 ergibt ≈6,1:1, Ton 80 auf Ton 6 ergibt ≈10,9:1. Container-Text (Ton 10/90) ist unkritisch.

### Typografie (M3-Rollen, Systemschrift, Ziffern immer `tabularFigures`)
| Rolle | Stil | Zweck |
|---|---|---|
| hero | displayLarge 57/64, w400 | „78“; das „%“ in headlineSmall, `text-label` |
| hero-sub | headlineSmall 24/32 | „Lädt · 18,4 W“ in `state-charge` |
| metric | titleLarge 22/28 | Werteraster |
| label | labelMedium 12/16 | Bezeichnungen |
| section | titleSmall 14/20 | Abschnittstitel |
| axis | labelSmall 11/16 | Achsen |

Format: `NumberFormat('de_DE')`, schmales geschütztes Leerzeichen (U+202F) vor Einheiten: „18,4 W“, „78 %“, „31,2 °C“, „1 h 23 min“.

### Komponenten-Tokens und Theme-Skizze
```dart
const kSeed = Color(0xFF4A6FA5);

DynamicColorBuilder(builder: (light, dark) => MaterialApp(
  themeMode: ThemeMode.system,
  theme: buildTheme(light ?? ColorScheme.fromSeed(seedColor: kSeed)),
  darkTheme: buildTheme(dark ?? ColorScheme.fromSeed(
      seedColor: kSeed, brightness: Brightness.dark)),
));

ThemeData buildTheme(ColorScheme s) => ThemeData(
  colorScheme: s,
  extensions: [BatteryColors.from(s)],
  cardTheme: CardThemeData(elevation: 0, color: s.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
);

class BatteryColors extends ThemeExtension<BatteryColors> {
  // charge, onCharge, chargeContainer, onChargeContainer, warm, hot
  factory BatteryColors.from(ColorScheme s) {
    ColorScheme role(int src) => ColorScheme.fromSeed(
        seedColor: Color(src).harmonizeWith(s.primary), // dynamic_color
        brightness: s.brightness,
        dynamicSchemeVariant: DynamicSchemeVariant.fidelity); // Sättigung bleibt, Kontrastkurve ≥4,5:1
    final c = role(0xFF2E9E4F), w = role(0xFFE8A13A);
    return BatteryColors(charge: c.primary, onCharge: c.onPrimary,
        chargeContainer: c.primaryContainer, onChargeContainer: c.onPrimaryContainer,
        warm: w.primary, hot: s.error);
  }
  // copyWith/lerp ...
}
```
Fehlen im dynamischen Scheme `surfaceContainer*`-Rollen, werden sie aus `fromSeed(seedColor: dyn.primary)` ergänzt. Dateien: `lib/ui/theme/{tokens.dart, battery_colors.dart, app_theme.dart}`. Style Dictionary wäre Overkill. Nativ (Kotlin `colors.xml`) werden nur Seed und src-charge gespiegelt.

### Dunkelmodus: M3-Surface statt reinem Schwarz
Standard ist das M3-Dunkel (Surface Ton 6). Die App ist nur Sekunden pro Tag sichtbar. Der OLED-Unterschied zwischen #000 und Ton 6 ist dagegen marginal, ein Hintergrunddienst fällt viel stärker ins Gewicht. Für reines Schwarz müsste man die tonalen Ebenen opfern, außerdem verschmiert es beim Scrollen. Optional in V2: Schalter „Reines Schwarz“ (`surface` = #000, Container-Töne bleiben).

### Layout: Hauptscreen (kompakt) und Einstellungen
```
┌─────────────────────────────────┐   ┌─────────────────────────────────┐
│ Akku                         ⚙  │   │ ←  Einstellungen                │
│                                 │   │ Ladeziel                  80 %  │
│              78 %               │   │ 50 ━━━━━━━━━━━━●━━━━━ 100       │
│        ⚡ Lädt · 18,4 W         │   │ Alarm bei Ziel             [●]  │
│ ▕██████████████████░░░┆░░░▏     │   │ Hintergrund                     │
│ Ziel 80 % in 6 min · voll 1 h 2 │   │ ◉ Dauerdienst · lückenlos       │
│                                 │   │ ○ Sparsam · Verlauf mit Lücken  │
│ 31,2 °C      4,21 V    1.840 mA │   │ Hitzewarnung ab          42 °C  │
│ Temperatur   Spannung  Strom    │   └─────────────────────────────────┘
│ Gut          412       4.410 mAh│
│ Zustand      Zyklen    Kapazität│   Nicht verfügbare Werte entfallen,
│                                 │   es bleibt kein „–“ stehen.
│ Verlauf              [24 h│7 T] │
│ ╭─────────────────────────────╮ │   Breit (≥840 dp, Desktop):
│ │ ‾‾╲__/‾‾‾‾╲___/‾‾  ┄┄ 80 %  │ │   links 360 dp Hero + Werte,
│ ╰─────────────────────────────╯ │   rechts die 3 Charts gestapelt.
│ Ladeleistung · heute 14:02    › │
│ ╭─────────────────────────────╮ │
│ │ ‾‾‾‾‾‾‾‾‾‾╲___              │ │
│ ╰─────────────────────────────╯ │
│ Entladerate                     │
│ Bildschirm an  ████████ 9,1 %/h │
│ Bildschirm aus █ 0,8 %/h        │
└─────────────────────────────────┘
```
Das Werteraster hat keine Karten. Gruppiert wird nur über Nähe (space-md in einer Gruppe, space-xl zwischen Sektionen). Charts werden per Sliver lazy gebaut und erst gezeichnet, wenn sie sichtbar sind.

### Chart-Spezifikation (für alle gilt: keine Punkte, kein Einblenden, `RepaintBoundary`)
| | Akku-% Verlauf | Ladeleistung je Vorgang | Entladerate |
|---|---|---|---|
| Form | Linie, 2 dp, runde Enden | Linie, 2 dp | 2 horizontale Balken, 12 dp, Radius full |
| x | Zeit; Ticks 06/12/18 bzw. Mo…So, rechts „jetzt“ | **Akku-%** 0–100 (CC/CV-Knick sichtbar, Vorgänge vergleichbar) | – |
| y | fix 0–100 %, Gitter nur bei 50, Labels 0/50/100 | 0 bis zum nächsten runden Wert (5/10/15/25/45/65/100 W), 2 Gitterlinien | direkt beschriftet |
| Farbe | entladen `primary`, laden `state-charge` mit Fläche darunter in 12 % Alpha | aktueller Vorgang `state-charge`, bis zu 3 frühere in `outlineVariant` | beide `primary`, Unterscheidung über die Beschriftung |
| Extras | Ziel-% als gestrichelte Linie; **Lücken**: Linie bricht ab, gepunkteter Verbinder in `line-grid` | Kopfzeile „23 → 80 % · Ø 15,2 W · max 27 W · 48 min“ | Zusatz „≈ 5 Tage Standby“ |
| Interaktion | Gedrückt halten: Fadenkreuz mit Wert, statisch | ebenso | – |

### Motion
| Ereignis | Bewegung | Dauer |
|---|---|---|
| Fortschrittsbalken (%-Änderung) | Breite | 250 ms, ease |
| Ladebeginn/-ende | Farbwechsel und Crossfade des Icons | 150 ms |
| 24 h/7 T | Crossfade | 150 ms |
| Seitenwechsel | Plattform-Standard (Predictive Back) | System |
| Zahlen, Charts | sofortiger Wechsel | 0 |

Verboten sind: unbestimmte Fortschrittsanzeigen, Shimmer/Skeletons, Pulsieren, Lottie, `BackdropFilter`. Bei `MediaQuery.disableAnimationsOf(context)` gilt für alles Dauer 0.

### Benachrichtigung und Icons
- **Small Icons:** 24-dp-Vektoren, nur Alpha. `ic_stat_charging` ist ein Akku-Umriss mit Blitz, `ic_stat_battery` dient dem stummen Dauerdienst. Akzent per `setColor(system_accent1_600)`, sonst Seed. Der Fortschritt ist immer bestimmt (`setProgress`). Idee für Pathfinder: Android 16 `ProgressStyle` mit Punkt am Ziel-%. Außerdem ein Countdown per `setChronometerCountDown`: Das System tickt dann selbst, ohne die App zu wecken.
- **App-Icon:** Senkrechte Akku-Kapsel mit Pol-Nase, zu etwa ⅔ gefüllt, kein Blitz. Adaptiv: Hintergrund Seed Ton 90, Glyphe Ton 30. Die Monochrom-Ebene (Android 13 Themed Icons) zeigt dieselbe Glyphe. `.ico`/PNG werden aus derselben SVG erzeugt. Das Tray-Icon ist monochrom und kommt in einer Hell- und einer Dunkel-Variante.

### Qualitätschecks
- [x] Jeder Wert ist ein Token, keine Hexwerte in Widgets (außer 3 Farbquellen)
- [x] Zustandsfarben bleiben unter beliebigen Paletten ≥4,5:1 (HCT-Ton)
- [x] Hell/Dunkel kommt aus einer einzigen `buildTheme`-Funktion
- [x] Migration ist klein: nur `main.dart` ersetzen, kein Big-Bang
- [ ] Offen: Tabellenziffern-Support der Linux-Standardfont, `dynamic_color` unter Linux prüfen
