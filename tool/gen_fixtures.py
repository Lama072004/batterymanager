"""Erzeugt die Golden-Fixtures in spec/engine/fixtures.

Die Erwartungswerte für `expect.normalized` werden unabhängig von SQL berechnet und anschließend
gegen den echten View `sample_n` (spec/db/001_init.sql, Python-sqlite3) gegengeprüft.
Aufruf (aus dem Projekt-Root):  python tool/gen_fixtures.py
Mitschnitte aus dem Mess-Spike (S1) werden später als zusätzliche Fixtures ergänzt.
"""
import json
import math
import os
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "spec", "engine", "fixtures")
SCHEMA = os.path.join(ROOT, "spec", "db", "001_init.sql")

CHARGING, DISCHARGING, NOT_CHARGING, FULL = 2, 3, 4, 5
AC, USB = 1, 2
T0 = 1_762_430_400_000  # 2025-11-06 12:00:00 UTC (fester Bezug für Fixtures)
MIN = 60_000

DEFAULT_PROFILE = {"i_to_ma": 0.001, "v_to_mv": 1, "p_to_mw": 1, "sign": 1, "cells": 1, "p_src": 0}


def s(t, level_pm, status, plug, current_raw=None, voltage_raw=4100, *, screen=1, temp_dc=300,
      counter_raw=None, power_raw=None, utc=None, boot=1, tz=60, span=1, mode=0, q=0):
    return {"t": t, "utc": T0 + t if utc is None else utc, "boot": boot, "tz": tz, "span": span, "mode": mode,
            "level_pm": level_pm, "status": status, "plug": plug, "screen": screen,
            "current_raw": current_raw, "voltage_raw": voltage_raw, "power_raw": power_raw,
            "counter_raw": counter_raw, "temp_dc": temp_dc, "q": q}


def sqlite_round(x):
    """SQLite ROUND(x): kaufmännisch, halb von null weg."""
    return math.floor(x + 0.5) if x >= 0 else -math.floor(-x + 0.5)


def normalize(profile, smp):
    """Unabhängige Referenz-Implementierung der Normalisierung (muss sample_n entsprechen)."""
    p = {**DEFAULT_PROFILE, **profile}
    f = p["p_to_mw"] if p["p_src"] == 1 else p["i_to_ma"] * p["v_to_mv"] * p["cells"] / 1000.0
    cr, vr, pr = smp["current_raw"], smp["voltage_raw"], smp["power_raw"]

    c = None
    if p["i_to_ma"] != 0 and cr is not None:
        c = int(sqlite_round(cr * p["i_to_ma"] * p["sign"]))
    v = None if vr is None else int(sqlite_round(vr * p["v_to_mv"]))
    pw = None
    if p["p_src"] == 1:
        pw = None if pr is None else int(sqlite_round(p["sign"] * f * pr))
    elif p["i_to_ma"] != 0 and cr is not None and vr is not None:
        pw = int(sqlite_round(p["sign"] * f * cr * vr))

    def rng(x, lo, hi):
        return x if x is not None and lo <= x <= hi else None

    t = smp["temp_dc"]
    return {"current_ma": rng(c, -15000, 15000), "voltage_mv": rng(v, 2500, 10000),
            "power_mw": rng(pw, -150000, 150000) if (p["p_src"] == 1 or rng(v, 2500, 10000) is not None) else None,
            "temp_dc": rng(t, -200, 700)}


def view_check(fx):
    """Gegenprüfung: echter View sample_n muss identisch rechnen."""
    con = sqlite3.connect(":memory:")
    script = open(SCHEMA, encoding="utf-8").read()
    con.executescript(script)
    p = {**DEFAULT_PROFILE, **fx["profile"]}
    con.execute("UPDATE device_profile SET i_to_ma=?, v_to_mv=?, p_to_mw=?, sign=?, cells=?, p_src=?",
                (p["i_to_ma"], p["v_to_mv"], p["p_to_mw"], p["sign"], p["cells"], p["p_src"]))
    for i, x in enumerate(fx["samples"]):
        con.execute("INSERT INTO sample (id, span, ts_utc, elapsed_ms, tz_min, level_pm, status, plug, screen,"
                    " current_raw, voltage_raw, power_raw, counter_raw, temp_dc, q)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (i + 1, x["span"], x["utc"], x["t"], x["tz"], x["level_pm"], x["status"], x["plug"],
                     x["screen"], x["current_raw"], x["voltage_raw"], x["power_raw"], x["counter_raw"],
                     x["temp_dc"], x["q"]))
    rows = con.execute("SELECT current_ma, voltage_mv, power_mw, temp_dc FROM sample_n ORDER BY id").fetchall()
    for i, (row, exp) in enumerate(zip(rows, fx["expect"]["normalized"])):
        got = {"current_ma": row[0], "voltage_mv": row[1], "power_mw": row[2], "temp_dc": row[3]}
        want = {k: exp[k] for k in got}
        if got != want:
            sys.exit(f"{fx['id']} Sample {i}: View {got} != Referenz {want}")


def fixture(fid, edge, title, samples, events, *, profile=None, profile_after=None, settings=None, eta=None,
            note=""):
    profile = profile or {}
    fx = {
        "id": fid, "edgeCase": edge, "title": title, "note": note,
        "profile": {**DEFAULT_PROFILE, **profile},
        "settings": settings or {"targetPct": 80, "alarm": True},
        "samples": samples,
        "expect": {
            "normalized": [dict(i=i, **normalize(profile, x)) for i, x in enumerate(samples)],
            "events": events,
            "eta": eta or [],
            "profileAfter": profile_after,
        },
    }
    view_check(fx)
    return fx


def build():
    fx = []
    # ---------------- Top-10-Edge-Cases (design.md) ----------------
    fx.append(fixture(
        "ec01_current_unsupported", "EC01", "CURRENT_NOW dauerhaft 0/MIN → W und mA n/v, ETA nur aus %-Steigung",
        [s(i * MIN, 400 + i * 10, CHARGING, AC, current_raw=0) for i in range(6)],
        [{"at": 0, "type": "SESSION_START"}],
        profile={"i_to_ma": 0}, profile_after={"i_to_ma": 0},
        eta=[{"at": 5, "state": "approx", "estimated": True}],
        note="Profil hat bereits gelernt: Strom nicht unterstützt (i_to_ma = 0)."))

    fx.append(fixture(
        "ec02_deficit", "EC02", "Eingesteckt, aber Netto-Entladung → „Entlädt trotz Ladegerät“, keine ETA",
        [s(i * MIN, 500 - i * 2, DISCHARGING if i else CHARGING, USB, current_raw=-400_000, screen=1)
         for i in range(5)],
        [{"at": 0, "type": "SESSION_START"}, {"at": 2, "type": "DEFICIT_START"}],
        eta=[{"at": 4, "state": "deficit"}]))

    fx.append(fixture(
        "ec03_pause", "EC03", "Hersteller-Limit/adaptive Pause → „Ladepause“, keine ETA, Ziel nicht erreichbar",
        [s(i * 2 * MIN, 800, NOT_CHARGING if i else CHARGING, AC, current_raw=20_000) for i in range(7)],
        [{"at": 0, "type": "SESSION_START"}, {"at": 5, "type": "PAUSE_START"}],
        settings={"targetPct": 85, "alarm": True},
        eta=[{"at": 6, "state": "pause"}],
        note="|I| < 50 mA und Ladestand 10 min konstant (Index 5 = 10 min)."))

    fx.append(fixture(
        "ec04_plug_above_target", "EC04", "Einstecken bei ≥ Ziel → einmalig „Ziel bereits erreicht“, kein Alarm",
        [s(i * MIN, 850 + i * 2, CHARGING, AC, current_raw=900_000) for i in range(4)],
        [{"at": 0, "type": "SESSION_START"}, {"at": 0, "type": "TARGET_ALREADY_REACHED"}]))

    fx.append(fixture(
        "ec05_loose_contact", "EC05", "Wackelkontakt < 2 min → eine Sitzung, kein neues Popup",
        [s(0, 300, CHARGING, AC, current_raw=1_500_000),
         s(1 * MIN, 302, CHARGING, AC, current_raw=1_500_000),
         s(1 * MIN + 20_000, 302, DISCHARGING, 0, current_raw=-300_000),
         s(2 * MIN + 20_000, 302, CHARGING, AC, current_raw=1_500_000),
         s(3 * MIN, 305, CHARGING, AC, current_raw=1_500_000)],
        [{"at": 0, "type": "SESSION_START"}],
        note="Abgesteckt für 60 s (Index 2..3): keine SESSION_END, kein zweites SESSION_START."))

    fx.append(fixture(
        "ec06_target_flutter", "EC06", "79↔80-Flattern → genau ein Ziel-Alarm (2 % Hysterese)",
        [s(i * MIN, lv, CHARGING, AC, current_raw=500_000) for i, lv in enumerate([780, 790, 800, 790, 800, 810])],
        [{"at": 0, "type": "SESSION_START"}, {"at": 2, "type": "TARGET_REACHED"}]))

    fx.append(fixture(
        "ec07_outage_target_exceeded", "EC07", "Dienst tot, Ziel währenddessen überschritten → einmaliger Hinweis",
        [s(0, 700, CHARGING, AC, current_raw=1_200_000, span=1),
         s(1 * MIN, 705, CHARGING, AC, current_raw=1_200_000, span=1),
         s(2 * MIN, 710, CHARGING, AC, current_raw=1_200_000, span=1),
         s(32 * MIN, 860, CHARGING, AC, current_raw=600_000, span=2),
         s(33 * MIN, 862, CHARGING, AC, current_raw=600_000, span=2)],
        [{"at": 0, "type": "SESSION_START"},
         {"at": 3, "type": "SESSION_END", "reason": "kill"},
         {"at": 3, "type": "SESSION_START", "startKnown": False},
         {"at": 3, "type": "TARGET_EXCEEDED_DURING_OUTAGE", "levelPct": 86}],
        note="Lücke 30 min (> 5 min) bei gleichem Boot: alte Sitzung am letzten Commit schließen."))

    fx.append(fixture(
        "ec08_clock_and_dst", "EC08", "Sommerzeit und Uhr verstellt → Raten aus elapsed unverändert, Uhrsprung markiert",
        [s(0, 600, DISCHARGING, 0, current_raw=-250_000, screen=0, tz=60),
         s(10 * MIN, 598, DISCHARGING, 0, current_raw=-250_000, screen=0, tz=60),
         s(20 * MIN, 596, DISCHARGING, 0, current_raw=-250_000, screen=0, tz=120),
         s(30 * MIN, 594, DISCHARGING, 0, current_raw=-250_000, screen=0, tz=120),
         s(40 * MIN, 592, DISCHARGING, 0, current_raw=-250_000, screen=0, tz=120, utc=T0 + 40 * MIN + 3_600_000),
         s(50 * MIN, 590, DISCHARGING, 0, current_raw=-250_000, screen=0, tz=120, utc=T0 + 50 * MIN + 3_600_000)],
        [{"at": 4, "type": "CLOCK_JUMP", "deltaMs": 3_600_000}],
        note="Sommerzeit (Index 2) ändert nur tz_min, kein Ereignis. Uhrsprung +1 h (Index 4): keine Rate über die Stelle."))

    fx.append(fixture(
        "ec09_mode_switch_while_charging", "EC09", "Moduswechsel beim Laden → offene Sitzung wird übernommen",
        [s(0, 400, CHARGING, AC, current_raw=1_000_000, span=1, mode=0),
         s(1 * MIN, 405, CHARGING, AC, current_raw=1_000_000, span=1, mode=0),
         s(1 * MIN + 2_000, 405, CHARGING, AC, current_raw=1_000_000, span=2, mode=1),
         s(2 * MIN, 410, CHARGING, AC, current_raw=1_000_000, span=2, mode=1)],
        [{"at": 0, "type": "SESSION_START"}, {"at": 2, "type": "SESSION_RESUME"}]))

    fx.append(fixture(
        "ec10_recalibration_jump", "EC10", "Hersteller-Rekalibrierung (Sprung) → Segmentbruch, keine Rate darüber",
        [s(0, 600, DISCHARGING, 0, current_raw=-300_000),
         s(10 * MIN, 598, DISCHARGING, 0, current_raw=-300_000),
         s(10 * MIN + 30_000, 520, DISCHARGING, 0, current_raw=-300_000),
         s(20 * MIN, 518, DISCHARGING, 0, current_raw=-300_000)],
        [{"at": 2, "type": "SEGMENT_BREAK", "reason": "jump"}],
        note="8 % in 30 s > 2 %/min beim Entladen."))

    # ---------------- Geräteprofil / Normalisierung ----------------
    fx.append(fixture(
        "p01_microamps_default", None, "Standardgerät: CURRENT_NOW in µA, positiv beim Laden",
        [s(0, 300, CHARGING, AC, current_raw=1_500_000, voltage_raw=4200, counter_raw=1_500_000),
         s(10 * MIN, 325, CHARGING, AC, current_raw=1_500_000, voltage_raw=4210, counter_raw=1_750_000),
         s(20 * MIN, 320, DISCHARGING, 0, current_raw=-350_000, voltage_raw=4050, counter_raw=1_691_667, screen=1)],
        [], profile_after={"i_to_ma": 0.001, "sign": 1, "cells": 1}))

    fx.append(fixture(
        "p02_milliamps", None, "Gerät meldet CURRENT_NOW in mA (ΔCHARGE_COUNTER / ∫I·dt ≈ 1000)",
        [s(0, 300, CHARGING, AC, current_raw=1500, voltage_raw=4200, counter_raw=1_500_000),
         s(10 * MIN, 325, CHARGING, AC, current_raw=1500, voltage_raw=4210, counter_raw=1_750_000)],
        [], profile={"i_to_ma": 1}, profile_after={"i_to_ma": 1, "sign": 1, "cells": 1}))

    fx.append(fixture(
        "p03_inverted_sign", None, "Gerät meldet Laden negativ (Vorzeichen per Mehrheitsvotum beim Entladen)",
        [s(0, 500, DISCHARGING, 0, current_raw=300_000, voltage_raw=3950),
         s(10 * MIN, 498, DISCHARGING, 0, current_raw=320_000, voltage_raw=3940),
         s(20 * MIN, 498, CHARGING, AC, current_raw=-1_800_000, voltage_raw=4150)],
        [], profile={"sign": -1}, profile_after={"i_to_ma": 0.001, "sign": -1, "cells": 1}))

    fx.append(fixture(
        "p04_dual_cell", None, "Dual-Cell: Zellspannung zum Pack-Strom → Leistung ×2",
        [s(0, 400, CHARGING, AC, current_raw=2_000_000, voltage_raw=4300),
         s(1 * MIN, 402, CHARGING, AC, current_raw=2_000_000, voltage_raw=4310)],
        [], profile={"cells": 2}, profile_after={"i_to_ma": 0.001, "sign": 1, "cells": 2}))

    fx.append(fixture(
        "p05_implausible_values", None, "Unplausible Rohwerte → n/v statt falscher Zahlen",
        [s(0, 400, CHARGING, AC, current_raw=99_000_000, voltage_raw=4200, temp_dc=300),
         s(1 * MIN, 401, CHARGING, AC, current_raw=1_000_000, voltage_raw=0, temp_dc=900),
         s(2 * MIN, 402, CHARGING, AC, current_raw=None, voltage_raw=4200, temp_dc=-250)],
        [], note="99 A, 0 V, 90 °C und −25 °C sind unplausibel; fehlender Strom bleibt n/v."))
    return fx


def main():
    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):
        if old.endswith(".json"):
            os.remove(os.path.join(OUT, old))
    fixtures = build()
    for f in fixtures:
        with open(os.path.join(OUT, f["id"] + ".json"), "w", encoding="utf-8") as fh:
            json.dump(f, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
    print(f"{len(fixtures)} Fixtures geschrieben nach {OUT} (View-Gegenprüfung ok)")


if __name__ == "__main__":
    main()
