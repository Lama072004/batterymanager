// US-006: Ladesitzung, Restzeit & Ziel-Alarm (AC-027 … AC-030) + US-015 AC-073 (Robustheit) gegen Golden-Fixtures
package de.mlampert.batterymanager

import de.mlampert.batterymanager.monitor.engine.Engine
import de.mlampert.batterymanager.monitor.engine.EtaState
import de.mlampert.batterymanager.monitor.engine.Event
import de.mlampert.batterymanager.monitor.engine.Profile
import de.mlampert.batterymanager.monitor.engine.Reading
import de.mlampert.batterymanager.monitor.engine.Settings
import de.mlampert.batterymanager.testutil.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Us006SessionEtaTest {

    private fun run(fx: Fixtures.Fixture, target: Int = 80): Pair<Engine, List<Pair<Int, Event>>> {
        val p = fx.profile
        val engine = Engine(Profile(p.iToMa, p.vToMv, p.pToMw, p.sign, p.cells, p.pSrc), Settings(targetPct = target))
        val out = mutableListOf<Pair<Int, Event>>()
        fx.samples.forEachIndexed { i, s ->
            val r = Reading(s.t, s.utc, s.boot, s.tz, s.span.toLong(), s.levelPm, s.status, s.plug, s.screen,
                s.currentRaw, s.voltageRaw, s.powerRaw, s.counterRaw, s.tempDc)
            engine.onReading(r).forEach { out += i to it }
        }
        return engine to out
    }

    private fun target(fx: Fixtures.Fixture): Int =
        JSONObject(fx.file.readText()).getJSONObject("settings").getInt("targetPct")

    // AC-027/029/030 und AC-073: Ereignisse aller Edge-Case-Fixtures (EC01–EC10) exakt wie spezifiziert.
    @Test
    fun edgeCaseEvents() {
        for (fx in Fixtures.all().filter { it.edgeCase != null }) {
            val (_, events) = run(fx, target(fx))
            val got = events.map { (i, e) -> "${e.type}@$i" }
            val want = fx.events.map { "${it.type}@${it.at}" }
            assertEquals("${fx.id}", want, got)
        }
    }

    // AC-028: ETA „wird berechnet“ im Warmup, ohne Strom höchstens „ca.“ (geschätzt), Pause/Defizit ersetzen die ETA.
    @Test
    fun etaStates() {
        val byId = Fixtures.all().associateBy { it.id }
        run(byId.getValue("ec01_current_unsupported")).first.eta().let {
            assertEquals(EtaState.APPROX, it.state); assertTrue(it.estimated)
            assertTrue((it.targetS ?: 0) > 0)
        }
        assertEquals(EtaState.PAUSE, run(byId.getValue("ec03_pause"), 85).first.eta().state)
        assertEquals(EtaState.DEFICIT, run(byId.getValue("ec02_deficit")).first.eta().state)

        val e = Engine(Profile(), Settings())
        e.onReading(Reading(0, 0, 1, 60, 1, 400, 2, 1, 1, 1_500_000, 4200, null, null, 300))
        e.onReading(Reading(30_000, 30_000, 1, 60, 1, 400, 2, 1, 1, 1_500_000, 4200, null, null, 300))
        assertEquals(EtaState.COMPUTING, e.eta().state)
    }

    // Ladelimit des Geräts (z. B. Ladeschutz 90 %): Halten mit Erhaltungsstrom (~100 mA) gilt als Pause,
    // der Alarm kommt am Haltepunkt, die Restzeit „bis voll“ rechnet bis zum Limit.
    @Test
    fun deviceLimitHoldAndAlarm() {
        val curve = (0 until 100).associate { Triple(1, 1, it) to 60.0 }
        val e = Engine(Profile(), Settings(targetPct = 90), curve, deviceLimitPct = 90)
        var t = 0L
        val events = mutableListOf<String>()
        for (lv in 850..890 step 10) {
            events += e.onReading(Reading(t, t, 1, 60, 1, lv, 2, 1, 1, 1_000_000, 4300, null, null, 300)).map { it.type }
            t += 60_000
        }
        // Restzeit bis Limit (89 → 90 %) statt bis 100 %
        assertTrue("bis Limit, nicht bis 100 %", (e.eta().fullS ?: 0) <= 120)
        // 12 min halten bei 89 % mit 100 mA → Pause + Ziel erreicht (Ziel 90 % ≥ Limit 90 %)
        repeat(12) {
            events += e.onReading(Reading(t, t, 1, 60, 1, 890, 2, 1, 1, 100_000, 4350, null, null, 300)).map { it.type }
            t += 60_000
        }
        assertTrue(events.toString(), Event.PAUSE_START in events)
        assertEquals(events.toString(), 1, events.count { it == Event.TARGET_REACHED })
        assertEquals(EtaState.PAUSE, e.eta().state)
        assertEquals(89, e.session?.pausePct)
    }

    // Ø nur über die aktive Ladephase, Spitze ohne einzelne Messausreißer.
    @Test
    fun activeAverageAndSmoothedPeak() {
        val e = Engine(Profile(), Settings(targetPct = 100))
        var t = 0L
        var lv = 400
        // 30 min aktiv mit 5 W (1,25 A · 4 V), eine Einzel-Spitze 21 W
        repeat(30) { i ->
            val cur = if (i == 10) 5_250_000L else 1_250_000L
            e.onReading(Reading(t, t, 1, 60, 1, lv, 2, 1, 1, cur, 4000, null, null, 300))
            t += 60_000; lv += 5
        }
        // danach 2 h halten mit 0,4 W
        repeat(120) {
            e.onReading(Reading(t, t, 1, 60, 1, lv, 2, 1, 1, 100_000, 4000, null, null, 300))
            t += 60_000
        }
        val s = e.session!!
        val avg = s.activeAvgMw(Profile())!!
        assertTrue("Ø aktiv ≈ 5 W, war $avg", avg in 4_500..6_500)
        assertTrue("Spitze ohne Ausreißer, war ${s.maxPowerMw}", (s.maxPowerMw ?: 0) in 4_500..6_000)
    }

    // Sofort-Restzeit: vor dem ersten %-Schritt aus Ladestrom und Kapazität (5.000 mAh, 2 A → 90 s je %).
    @Test
    fun etaFromCurrentBeforeFirstStep() {
        val e = Engine(Profile(), Settings(targetPct = 80), capacityMah = 5000.0)
        e.onReading(Reading(0, 0, 1, 60, 1, 500, 2, 1, 1, 2_000_000, 4200, null, null, 300))
        e.onReading(Reading(100_000, 100_000, 1, 60, 1, 500, 2, 1, 1, 2_000_000, 4200, null, null, 300))
        val eta = e.eta()
        assertEquals(EtaState.APPROX, eta.state)
        assertEquals(30 * 90L, eta.targetS)
        // ohne Kapazität: keine Schätzung
        val e2 = Engine(Profile(), Settings(targetPct = 80))
        e2.onReading(Reading(0, 0, 1, 60, 1, 500, 2, 1, 1, 2_000_000, 4200, null, null, 300))
        e2.onReading(Reading(100_000, 100_000, 1, 60, 1, 500, 2, 1, 1, 2_000_000, 4200, null, null, 300))
        assertEquals(EtaState.NONE, e2.eta().state)
    }

    // Lernkurve wird mit gelerntem Profil zu „ok“ und rechnet die CV-Phase langsamer.
    @Test
    fun etaWithLearnedProfile() {
        // 1,5 A · 4,2 V = 6,3 W → Leistungsklasse 1 (≤ 7,5 W)
        val curve = (0 until 100).associate { Triple(1, 1, it) to if (it < 80) 60.0 else 180.0 }
        val e = Engine(Profile(), Settings(targetPct = 80), curve)
        var t = 0L
        for (lv in 500..540 step 10) { e.onReading(Reading(t, t, 1, 60, 1, lv, 2, 1, 1, 1_500_000, 4200, null, null, 300)); t += 60_000 }
        val eta = e.eta()
        assertEquals(EtaState.OK, eta.state)
        assertTrue("Ziel 80 % ≈ 26 × 60 s", eta.targetS!! in 1200..1900)
        assertTrue("Voll > Ziel", eta.fullS!! > eta.targetS!! + 20 * 120)
    }
}
