## Skeptic Final Position — Batterymanager

**Überarbeitete Empfehlung:** Der konvergierte Plan trägt: ein nativer Dienst als einziger Schreiber, Rohwerte mit Normalisierung beim Lesen, Reihenfolge Android (a) → (b) → Linux → Windows. Offen ist nur die Reihenfolge innerhalb von Android. Vor der ersten UI-Zeile läuft ein Phase-0-Spike (Logger-APK ohne UI) auf mindestens 2 Geräten. Er belegt Einheit und Vorzeichen, misst die Startlatenz von (b) und prüft die Energie-Gates. Was der Spike nicht belegt, zeigt die UI als „geschätzt“ oder „n/v“.

**Zugeständnisse:**
- **1 %/Tag zurückgezogen:** Tuners 5/25 mAh plus harte Zähler-Gates prüfen strenger.
- **Kein C++-Helper, Tray optional:** derselbe Host, eine Implementierung weniger.
- **(b) als Lade-FGS:** akzeptiert, wenn die Ausnahme Pflicht ist und der Einstellungstext ehrlich bleibt.
- **Bis zu 1 min Jitter-Verlust beim Laden:** tolerierbar, weil Zustandswechsel sofort committet werden.
- **Android-Health:** nur Enum und gekennzeichnete Kapazitätsschätzung, kein echter SoH-Wert.

**Non-negotiables:**
1. **Rohwerte mit Qualitätsflag pro Sample** (OK, ESTIMATED, NA, OUT_OF_RANGE, SIGN_CONFLICT, GAP). Normalisierung nur per View aus `device_profile`. Nie „0“ statt „n/v“.
2. **Phase-0-Gates S1–S4 vor dem UI-Bau bestanden.**
3. **Zustandswechsel sofort committen, Alarm idempotent:** erst anzeigen, dann Flag committen, feste Notification-ID pro Sitzung. Lücken markieren, nie interpolieren.
4. **Identität vor der Erstinstallation fixieren:** `applicationId`, Release-Keystore, Kanal-IDs samt Importance, `user_version`-Schema, `allowBackup=false`, kein `INTERNET` im Release.
5. **Ausfälle sichtbar machen:** Banner mit Deep-Link bei Heartbeat-Lücke, fehlender Berechtigung oder Ausnahme und stummem Kanal. `ForegroundServiceStartNotAllowedException` fangen.

### Implementierungshinweise

**Phase 0: Logger-APK** (Pixel und Samsung, möglichst auch Xiaomi)
- **S1, Messwerte (48 h):** Rohquellen plus `elapsedRealtime` in fünf Szenarien: Schnelllader, PC-USB, Laden beim Spielen, kabellos, Idle bei ausgeschaltetem Bildschirm. Ergebnis: Einheit, Vorzeichen, Aktualisierungsrate von `CURRENT_NOW`, Abweichung ∫I·dt gegenüber ΔCHARGE_COUNTER.
- **S2, Latenz (b):** mindestens 10 Einsteckvorgänge pro Gerät bei 15/50/85/92 %, Zeitstempel für Einstecken, `onStartJob` und FGS. Median > 30 min oder > 20 % ohne Start → (b) gilt als „experimentell“.
- **S3, Energie (24 h (a), Alltag):** 0 eigene Wakelocks/Alarme/Jobs, CPU ≤ 2 s/h, 0 Notification-Updates bei ausgeschaltetem Bildschirm, UID ≤ 25 mAh. Außerdem `BATTERY_CHANGED` und Commits pro Tag zählen.
- **S4, Startwege:** `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` und Job → FGS auf Android 14, 15 und 16 prüfen. 72-h-Test gegen OEM-Kills, mit und ohne Ausnahme.

**Plausibilität** (bei Verstoß: Flag setzen, Wert n/v)

| Größe | Regel |
|---|---|
| % | 0 ≤ level ≤ scale, scale > 0 |
| Spannung | 3,0–4,6 V (1 Zelle), 6,0–9,2 V (2 seriell) |
| Temperatur | −20…+70 °C; Warnung ab 45 °C, Entwarnung unter 42 °C |
| Strom | 0/`MIN_VALUE` → n/v; normalisiert \|I\| ≤ 15 A |
| Leistung | \|P\| ≤ 150 W (Handy), ≤ 250 W (Laptop) |
| Sprung | > 6 %/min (Laden), > 2 %/min (Entladen) → Segmentbruch |
| Zeit | Δelapsed ≤ 0 oder neuer Boot → Segmentbruch |
| Konsistenz | ∫I·dt weicht über 10 min > 30 % von ΔCHARGE_COUNTER ab → Profil „unsicher“ |
| Kapazität | Vollschätzung außerhalb 1.500–8.000 mAh → verwerfen; klärt zuerst die Zählereinheit |

**Geräteprofil**
- **Schlüssel:** Hersteller + Modell + Build-Fingerprint. Neuer Fingerprint (OTA) → neu lernen; das alte Profil bleibt mit `valid_from` erhalten.
- **Einheit:** Verhältnis ΔCHARGE_COUNTER zu ∫I·dt über ≥ 10 min: ≈ 1 → µA, ≈ 1000 → mA. Ohne Zähler: Median |I| beim Laden < 20.000 → mA.
- **Vorzeichen:** Mehrheitsvotum (≥ 80 %), nur aus Samples mit `plugged=0`, weil Laden unter Last netto negativ sein kann.
- **Dual-Cell:** Spannungsmedian > 5,5 V → seriell; Faktor 2 im Konsistenzcheck → Flag.
- **Bis zur Festlegung:** Watt wird als „geschätzt“ angezeigt. Danach gilt: > 20 % Vorzeichenkonflikte in 24 h → neu lernen und Banner.
- **Override:** Einheit, Vorzeichen, Zellen und Kapazität lassen sich manuell setzen und wirken rückwirkend. Dazu eine Diagnoseansicht mit Rohwerten.

**Neustart, Kill, Uhrzeit**
- **Pro Zeile:** `utc_ms`, `elapsed_ms`, `boot_count`, `tz_offset`. Raten nur aus Δelapsed innerhalb eines Boots.
- **Uhrsprung:** ΔUTC und Δelapsed weichen um > 2 min ab → Marker „Uhr verstellt“, keine Rate über diese Stelle.
- **Offene Sitzung beim Dienststart:** fortsetzen bei gleichem Boot, Lücke < 5 min und weiterhin eingesteckt. Sonst am letzten Commit schließen (`end_reason`).
- **Datenbank:** `quick_check` beim Start; bei Fehler Datei als `.corrupt` beiseitelegen, neu anlegen, Banner zeigen. Vor jeder Migration eine Kopie.
- **Direct Boot:** Zwischen Boot und Entsperren keine Aufzeichnung; die Lücke wird markiert.

**Top-10-Edge-Cases**

| Fall | Verhalten |
|---|---|
| `CURRENT_NOW` dauerhaft 0/MIN | W und mA n/v; ETA aus %-Steigung, „geschätzt“ |
| Eingesteckt, netto Entladung | „Entlädt trotz Ladegerät (−x W)“, keine ETA |
| OEM-Limit/adaptive Pause (\|I\| < 50 mA, % 10 min konstant) | „Ladepause“, keine ETA, Ziel „nicht erreichbar“ |
| Einstecken bei ≥ Ziel | einmalig „Ziel bereits erreicht“, kein Alarm |
| Wackelkontakt/kabellos < 2 min | eine Sitzung, kein neues Popup |
| 79↔80-Flattern | ein Alarm pro Sitzung, 2 % Hysterese |
| Dienst tot, Ziel überschritten | beim Neustart einmalig „Ziel während Ausfall überschritten (x %)“ |
| Uhr/Sommerzeit/Zeitzone | Raten unverändert, Tagesgrenzen lokal |
| Moduswechsel beim Laden | offene Sitzung wird übernommen, nie doppelt |
| OEM-Rekalibrierung | Segmentbruch, keine Rate über den Sprung |

**Akzeptanzkriterien (PRD)**
- **AK1:** Vorzeichen in 5/5 S1-Szenarien korrekt; Leistung über 10 min innerhalb ±20 % der ΔCHARGE_COUNTER-Referenz.
- **AK2:** Bei fehlender Quelle nie „0 W“ oder „0 mA“.
- **AK3:** S3-Gates auf 2 Geräten bestanden; Ziel ≤ 5 mAh/24 h.
- **AK4:** (a): Popup ≤ 5 s nach dem Einstecken, genau ein Heads-up, danach ≤ 1 Update pro 5 s und nur bei geändertem Rundungswert.
- **AK5:** 20 Testladungen ohne verpassten oder doppelten Ziel-Alarm, auch mit Kill oder Reboot dazwischen.
- **AK6:** Nach 3 Lernsitzungen liegt die ETA bei halber Restzeit in ≥ 80 % der Sitzungen innerhalb von max(±20 %, ±5 min).
- **AK7:** Reboot, Update, Force-Stop, Uhr ±1 h, Sommerzeitwechsel: keine negativen oder ∞-Raten, keine Doppelsitzung, Lücken markiert.
- **AK8:** (b) zeigt die gemessene Median-Latenz an und verspricht nie „sofort“ oder „lückenlos“.
