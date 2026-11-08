# Engine-Spezifikation (v1)

Verbindliche Spezifikation der Hintergrund-Engine im Prozess `:monitor`. Sie wird in Kotlin implementiert. Die Golden-Fixtures in `fixtures/*.json` sind Teil der Spezifikation: Jede Implementierung muss sie erfüllen. Quelle der Entscheidungen ist `.claude/council/sessions/batterymanager-app-20261106-1815/design.md`.

## 1. Grundregeln

1. **Rohwerte speichern, beim Lesen normalisieren.** Die Engine schreibt Gerätewerte unverändert in `sample.*_raw`. Normalisiert wird nur über die Views (`sample_n`, `point_n`, `session_n`) mit `device_profile`. Der Live-Pfad (Benachrichtigung, Messenger) nutzt dieselbe Formel mit derselben Profilzeile.
2. **Fehlende oder unplausible Werte sind `null` (n/v), nie 0.**
3. **Raten nur aus `elapsed_ms` innerhalb eines Spans.** Wanduhrzeit (`ts_utc`) dient nur der Anzeige und den Tagesgrenzen.
4. **Lücken sind explizit:** Sie stecken im Abstand zwischen Spans und werden nie interpoliert.
5. **Entkopplung:** Der Ziel-Alarm hängt nur am Ladestand, das Ladeprofil ist einheitenfrei (s/%). Ein falsches Geräteprofil verdirbt weder den Alarm noch die Lernkurve.
6. **Keine eigene Weckquelle beim Entladen.** Die Engine reagiert nur auf `ACTION_BATTERY_CHANGED`, `SCREEN_ON` und `SCREEN_OFF`.

## 2. Einheiten

| Größe | Rohquelle (Android) | Gespeichert | Normalisiert |
|---|---|---|---|
| Ladestand | `EXTRA_LEVEL / EXTRA_SCALE` | `level_pm` = round(1000 · level / scale) | ‰ |
| Strom | `BATTERY_PROPERTY_CURRENT_NOW` | `current_raw` (`Integer.MIN_VALUE` → `null`) | mA = raw · `i_to_ma` · `sign` |
| Spannung | `EXTRA_VOLTAGE` | `voltage_raw` | mV = raw · `v_to_mv` |
| Leistung | aus I · U | `power_raw` nur bei direkter Lieferung | mW = `sign` · f · I_raw · U_raw, f = `i_to_ma` · `v_to_mv` · `cells` / 1000 |
| Ladezähler | `BATTERY_PROPERTY_CHARGE_COUNTER` | `counter_raw` | nur zur Profil-Erkennung |
| Temperatur | `EXTRA_TEMPERATURE` | `temp_dc` (Zehntel-°C) | Zehntel-°C |

**Vorzeichen-Konvention (normalisiert):** positiv bedeutet in den Akku. Die angezeigte Leistung ist netto am Akku, nicht die Leistung des Ladegeräts.

**Rundung:** `ROUND` halb von null weg, danach `CAST AS INTEGER`.

## 3. Plausibilität (normalisiert → sonst `null`)

| Größe | Bereich |
|---|---|
| Strom | −15.000 … +15.000 mA; `i_to_ma = 0` heißt „nicht unterstützt“ |
| Spannung | 2.500 … 10.000 mV (außerhalb wird auch die Leistung `null`) |
| Leistung | −150.000 … +150.000 mW |
| Temperatur | −200 … +700 (Zehntel-°C) |
| Ladestand | 0 ≤ level ≤ scale, scale > 0 (sonst wird das Sample verworfen) |

**Qualitäts-Bits `sample.q`:**

| Bit | Name | Bedeutung |
|---|---|---|
| 1 | OUT_OF_RANGE | ein Rohwert verletzt eine Regel beim Schreiben |
| 2 | WARMUP | in den ersten 90 s einer Sitzung |
| 4 | PAUSE | Ladepause erkannt |
| 8 | SINGLE_POINT | isolierter Punkt (z. B. Snapshot im Modus „Nur beim Laden“) |
| 16 | SIGN_CONFLICT | Vorzeichen widerspricht dem Profil |
| 32 | ESTIMATED | Profil noch nicht bestätigt |

## 4. Sampling (Tuner, verbindlich)

| Zustand | Auslöser | Strom lesen | Flush |
|---|---|---|---|
| Laden, Bildschirm an | `BATTERY_CHANGED` + Handler-Tick 10 s | ja | Batch alle 60 s |
| Laden, Bildschirm aus | `BATTERY_CHANGED` + Handler-Tick 30 s (`uptimeMillis`, weckt nicht) | ja | Batch alle 60 s |
| Entladen | nur `BATTERY_CHANGED`, `SCREEN_ON/OFF` | je Ereignis | pro Ereignis |

**Sofort-Flush bei:** `SESSION_START`, `SESSION_END`, `TARGET_REACHED`, voll, `onTrimMemory`.

**Leistung live:** W = I · U mit EMA, α = 0,3.

## 5. Ladesitzung (SessionMachine)

**Zustände:** `IDLE` → `WARMUP` (90 s) → `CHARGING` ⇄ `PAUSE` ⇄ `DEFICIT` → `FULL` → `IDLE`.

| Regel | Wert |
|---|---|
| Sitzungsbeginn | `plug > 0` nach `plug == 0` |
| Wackelkontakt | Abstecken < 120 s → dieselbe Sitzung (kein `SESSION_END`, kein neues Popup) |
| Ende | `plug == 0` für ≥ 120 s → `SESSION_END` mit Zeitpunkt des Absteckens |
| Wiederaufnahme nach Dienststart | gleicher Boot **und** Lücke < 5 min **und** eingesteckt → `SESSION_RESUME`; sonst alte Sitzung am letzten Commit schließen (`SESSION_END`, reason `kill`/`reboot`) und neue mit `startKnown = false` |
| Moduswechsel beim Laden | `SESSION_RESUME`, nie eine zweite Sitzung |
| Ladepause | eingesteckt, \|I\| < 50 mA **und** Ladestand seit ≥ 10 min konstant → `PAUSE_START` |
| Netto-Entladung | eingesteckt **und** Ladestand fällt (≥ 2 Schritte) bzw. I < 0 über ≥ 2 Samples → `DEFICIT_START` |
| Leistungsklasse nach Warmup | 0 unbekannt (ohne Strom), 1 ≤ 7,5 W, 2 ≤ 18 W, 3 > 18 W |
| Segmentbruch | \|Δlevel\| > 6 %/min beim Laden bzw. > 2 %/min beim Entladen → `SEGMENT_BREAK` (reason `jump`), keine Rate über diese Stelle |
| Uhrsprung | \|ΔUTC − Δelapsed\| > 120 s → `CLOCK_JUMP`, keine Rate über diese Stelle. Sommerzeit ändert nur `tz_min` und ist kein Ereignis. |

## 6. Ziel-Alarm (TargetAlarm)

- `TARGET_REACHED`: genau einmal pro Sitzung, beim ersten Übergang `level_pct ≥ targetPct`.
- **Hysterese:** Ein erneuter Alarm in derselben Sitzung setzt voraus, dass der Ladestand zuvor unter `targetPct − 2` gefallen ist.
- Beim Einstecken mit `level_pct ≥ targetPct` folgt einmalig `TARGET_ALREADY_REACHED`, kein Alarm.
- Startet nach einem Ausfall eine neue Sitzung bei `level_pct > targetPct` und die vorige Sitzung hatte das Ziel nicht erreicht, folgt einmalig `TARGET_EXCEEDED_DURING_OUTAGE`.
- **Idempotent:** Erst anzeigen, dann `charge_session.target_reached_ts` committen. Nach einem Neustart wird nicht erneut alarmiert, wenn `target_reached_ts` gesetzt ist.

## 7. Restzeit (Eta)

1. **Warmup (90 s):** Zustand `computing` („wird berechnet“).
2. **Vorrang:** Zustand `pause` oder `deficit` ersetzt jede Zeitangabe.
3. **Live-Rate:** EWMA der Sekunden je Prozentpunkt (α = 0,3). Der Strom dient nur der Interpolation innerhalb eines Prozentpunkts und der Erkennung von CC → CV.
4. **Restzeit** = Σ_{p = level..target−1} `sec_per_pct[plug, klass, p]` · k, mit k = EWMA(Live-Rate / Profil-Rate), begrenzt auf 0,5…2.
   - Fehlt ein Profil-Bucket: bis 80 % die Live-Rate, darüber linear steigend bis zur 3-fachen Live-Rate bei 100 %.
5. **Konfidenz** c = min(1, n̄ / 5) · Abdeckung · (1 − Streuung(k)).
   - c ≥ 0,7: Zustand `ok`, 5-min-Stufen.
   - c ≥ 0,4: Zustand `approx`, „ca.“ in 15-min-Stufen.
   - darunter: Zustand `none`, Anzeige „—“.
   - Ohne Strom (`i_to_ma = 0`): höchstens `approx` mit `estimated = true`, nur aus der %-Steigung.
6. **Hysterese der Anzeige:** Die angezeigte Restzeit ändert sich erst bei |Δ| > max(60 s, 10 %).
7. **Lernen** bei `SESSION_END` in derselben Transaktion:
   - nur lückenlose Prozentschritte ohne Pause/Deficit, EMA α = 0,3 je (plug, klass, pct)
   - `eta_err_s` = Ist − Prognose bei halber Restzeit

## 8. Geräteprofil (ProfileDetector)

- **Schlüssel:** `Build.FINGERPRINT`. Bei Änderung (OTA) wird neu gelernt; das alte Profil bleibt nur in einer Sicherungskopie.
- **Einheit:** Verhältnis ΔCHARGE_COUNTER / ∫I·dt über ≥ 10 min.
  - ≈ 1 → µA (`i_to_ma = 0,001`)
  - ≈ 1000 → mA (`i_to_ma = 1`)
  - Ohne Zähler: Median |I_raw| beim Laden < 20.000 → mA.
- **Vorzeichen:** Mehrheitsvotum (≥ 80 %) nur aus Samples mit `plug == 0`. Ist I_raw beim Entladen positiv, gilt `sign = −1`.
- **Dual-Cell:** Median der Spannung > 5.500 mV → seriell (`v_to_mv` bleibt, `cells = 1`). Weicht der Konsistenzcheck um Faktor ≈ 2 ab → `cells = 2`.
- **Strom nicht unterstützt:** I_raw ≡ 0 oder `null` über ≥ 10 min beim Laden bei steigendem Ladestand → `i_to_ma = 0`.
- **Bis zur Bestätigung:** `source = 0`, Samples tragen das Bit ESTIMATED, die UI zeigt „geschätzt“. Mehr als 20 % Vorzeichenkonflikte in 24 h → neu lernen und Banner.
- **Manueller Override:** `source = 2`. Er wird nie automatisch überschrieben und wirkt rückwirkend über die Views (`rev` + 1).

## 9. Ereignisse (Fixture-Vokabular)

| Typ | Felder | Bedeutung |
|---|---|---|
| `SESSION_START` | `startKnown?` (Standard true) | neue Ladesitzung |
| `SESSION_RESUME` | | Sitzung nach Dienst- oder Moduswechsel fortgesetzt |
| `SESSION_END` | `reason`: `unplug` \| `kill` \| `reboot` \| `mode` | Sitzung beendet |
| `PAUSE_START` / `PAUSE_END` | | Ladepause |
| `DEFICIT_START` / `DEFICIT_END` | | eingesteckt, aber Netto-Entladung |
| `TARGET_REACHED` | | Ziel-Alarm (einmal pro Sitzung) |
| `TARGET_ALREADY_REACHED` | | beim Einstecken schon über dem Ziel |
| `TARGET_EXCEEDED_DURING_OUTAGE` | `levelPct` | Ziel während eines Ausfalls überschritten |
| `SEGMENT_BREAK` | `reason`: `jump` \| `gap` | keine Rate über diese Stelle |
| `CLOCK_JUMP` | `deltaMs` | Wanduhr verstellt |
| `HEAT_WARN` / `HEAT_CLEAR` | | ab 45 °C (einstellbar 40–50 °C), Entwarnung 3 °C darunter |

## 10. Fixture-Format

```json
{
  "id": "ec06_target_flutter",
  "edgeCase": "EC06",
  "title": "…", "note": "…",
  "profile": {"i_to_ma": 0.001, "v_to_mv": 1, "p_to_mw": 1, "sign": 1, "cells": 1, "p_src": 0},
  "settings": {"targetPct": 80, "alarm": true},
  "samples": [
    {"t": 0, "utc": 1762430400000, "boot": 1, "tz": 60, "span": 1, "mode": 0,
     "level_pm": 780, "status": 2, "plug": 1, "screen": 1,
     "current_raw": 500000, "voltage_raw": 4100, "power_raw": null, "counter_raw": null,
     "temp_dc": 300, "q": 0}
  ],
  "expect": {
    "normalized": [{"i": 0, "current_ma": 500, "voltage_mv": 4100, "power_mw": 2050, "temp_dc": 300}],
    "events": [{"at": 0, "type": "SESSION_START"}, {"at": 2, "type": "TARGET_REACHED"}],
    "eta": [{"at": 5, "state": "approx", "estimated": true}],
    "profileAfter": {"i_to_ma": 0.001, "sign": 1, "cells": 1}
  }
}
```

**Feldbedeutung:**
- `t` ist `elapsed_ms`, `utc` ist `ts_utc`, `at` ist der Sample-Index.
- `expect.normalized` gilt für `profile`, das gerade wirksame Profil.
- `expect.profileAfter` ist das Ergebnis des ProfileDetector oder `null`, wenn die Fixture ihn nicht prüft.
- **Prüfreihenfolge:**
  - Phase 0: `normalized` gegen den View, dazu die Formatvalidierung.
  - Phase 1: `events`, `eta` und `profileAfter` gegen die Kotlin-Engine.
- **Erzeugen:** `tool/gen_fixtures.py`. Es berechnet `normalized` unabhängig und gleicht mit dem View ab. Mitschnitte aus dem Mess-Spike kommen als weitere Fixtures hinzu.
- **Pflicht-Abdeckung:** Für jeden der Edge-Cases `EC01` bis `EC10` gibt es mindestens eine Fixture.
