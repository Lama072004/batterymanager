# Phase-0-Spike: Ergebnisse

Gerät: _(Hersteller, Modell, Android-Version, Build-Fingerprint)_ · Datum: _____

## S1: Messwerte
| Frage | Ergebnis |
|---|---|
| Einheit CURRENT_NOW (µA / mA) | |
| Vorzeichen beim Laden (+ / −) | |
| Aktualisierungsrate CURRENT_NOW | |
| ΔCHARGE_COUNTER vs. ∫I·dt (Abweichung in %) | |
| cycle_count / current_avg / energy_counter verfügbar? | |
| Spannung: Zellen (1 / 2) | |
| Besonderheiten je Szenario (Schnelllader, PC-USB, Last, kabellos, Idle) | |

→ Daraus resultierendes `device_profile`: i_to_ma = __, sign = __, cells = __
→ Mitschnitte als Fixtures übernommen: `spec/engine/fixtures/rec_*.json` ☐

## S2: Startverzögerung „Nur beim Laden“
| Einstecken bei % | Einsteckzeit | Jobstart | Latenz (min) |
|---|---|---|---|
| | | | |

Median: ___ min · ohne Start: ___ von ___ → Status: ☐ ok ☐ experimentell

## S3: Eigenverbrauch (24 h, Release)
| Kriterium | Ziel | Grenze | Gemessen |
|---|---|---|---|
| UID-Schätzung beim Entladen | ≤ 5 mAh | 25 mAh | |
| Eigene Wakeup-Alarme, WakeLocks, Jobs | 0 | 0 | |
| CPU-Zeit beim Entladen (cpu_ms) | ≤ 24 s | 48 s | |
| libflutter in :monitor | nein | nein | |
| PSS :monitor | ≤ 25 MB | 40 MB | |

## S4/S5: Startwege
| Weg | Ergebnis |
|---|---|
| BOOT_COMPLETED → Logger | |
| MY_PACKAGE_REPLACED → Logger | |
| Job → FGS ohne Akku-Ausnahme | |
| Job → FGS mit Akku-Ausnahme | |
| S5 Kanalwechsel per Re-Post (Phase 1) | offen |
