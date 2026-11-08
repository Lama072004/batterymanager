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
