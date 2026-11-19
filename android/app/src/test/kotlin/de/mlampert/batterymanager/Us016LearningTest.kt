// US-016: Lernendes Restzeit-Modell (Energie je % + Leistungskurve + Bias) und Kapazitätsfenster.
package de.mlampert.batterymanager

import de.mlampert.batterymanager.monitor.engine.ChargeModel
import de.mlampert.batterymanager.monitor.engine.Engine
import de.mlampert.batterymanager.monitor.engine.EtaState
import de.mlampert.batterymanager.monitor.engine.Profile
import de.mlampert.batterymanager.monitor.engine.Reading
import de.mlampert.batterymanager.monitor.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Us016LearningTest {

    /** Simuliertes Gerät: 5.000 mAh bei 3,9 V; Rohprodukt in µA·mV·s (1 % = 50 mAh = 50.000 µAh). */
    private val ePerPct = 50_000.0 * 3600 * 3900

    /** Leistungsform: konstant bis 80 %, dann linear fallend auf 30 % bei 100 %. */
    private fun shape(p: Int) = if (p < 80) 1.0 else 1.0 - 0.7 * (p - 80) / 20.0

    /** Schritte eines Ladevorgangs von [from] bis [to] mit Grundleistung [base] (µA·mV). */
    private fun session(from: Int, to: Int, base: Double): List<ChargeModel.Step> =
        (from until to).map { p ->
            val pw = base * shape(p)
            ChargeModel.Step(p, ePerPct / pw, ePerPct)
        }

    private fun trueSeconds(from: Int, to: Int, base: Double) = (from until to).sumOf { ePerPct / (base * shape(it)) }

    // Aus Ladevorgängen mit verschiedenen Netzteilen lernen und mit einer NEUEN aktuellen Leistung prognostizieren.
    @Test
    fun learnsEnergyAndShapeAcrossChargers() {
        val m = ChargeModel()
        m.learn(session(20, 90, 4_000_000.0 * 4000), klass = 2)   // ~16 W
        m.learn(session(40, 100, 3_000_000.0 * 4000), klass = 2)  // ~12 W
        m.learn(session(10, 70, 3_500_000.0 * 4000), klass = 2)
        assertEquals(3, m.sessions)
        // Jetzt 30 % mit 10 W-Netzteil (2,5 A · 4 V) – Leistung kennt das Modell nur von „jetzt“.
        val now = 2_500_000.0 * 4000
        val pred = m.seconds(30, 90, now, klass = 2)!!
        val truth = trueSeconds(30, 90, now)
        assertTrue("Prognose $pred vs. echt $truth", abs(pred - truth) / truth < 0.05)
        // Ab 85 % (CV-Phase): Leistung jetzt bereits reduziert.
        val now85 = now * shape(85)
        val pred85 = m.seconds(85, 100, now85, klass = 2)!!
        val truth85 = trueSeconds(85, 100, now)
        assertTrue("CV $pred85 vs. $truth85", abs(pred85 - truth85) / truth85 < 0.08)
    }

    // Ohne gelernte Daten: keine Modell-Prognose.
    @Test
    fun emptyModelGivesNothing() {
        val m = ChargeModel()
        assertNull(m.seconds(20, 80, 1e10, 2))
        assertTrue(!m.covers(20, 100))
    }

    // Bias: Prognose war systematisch 20 % zu kurz → nach einigen Ladevorgängen korrigiert.
    @Test
    fun biasCorrectsSystematicError() {
        val m = ChargeModel()
        m.learn(session(20, 100, 1.6e10), klass = 2)
        val p0 = m.seconds(30, 90, 1.6e10, 2)!!
        repeat(6) {
            val pred = m.seconds(30, 90, 1.6e10, 2)!!
            m.learnBias(pred, p0 * 1.2)
        }
        val p = m.seconds(30, 90, 1.6e10, 2)!!
        assertTrue("nach Bias ${p / p0}", abs(p / p0 - 1.2) < 0.03)
    }

    // Engine: mit gelerntem Modell gibt es SOFORT eine sichere Restzeit aus der aktuellen Leistung.
    @Test
    fun engineUsesModelImmediately() {
        val m = ChargeModel()
        repeat(3) { m.learn(session(20, 100, 2_000_000.0 * 4000), klass = 2) }
        val e = Engine(Profile(), Settings(targetPct = 90), model = m)
        // 2 A · 4 V = 8 W → Klasse 2; erste beiden Messungen bei 40 %
        e.onReading(Reading(0, 0, 1, 60, 1, 400, 2, 1, 1, 2_000_000, 4000, null, null, 300))
        e.onReading(Reading(10_000, 10_000, 1, 60, 1, 400, 2, 1, 1, 2_000_000, 4000, null, null, 300))
        val eta = e.eta()
        assertEquals(EtaState.OK, eta.state)
        assertEquals(3, eta.learned)
        val truth = trueSeconds(40, 90, 2_000_000.0 * 4000)
        assertTrue("Ziel ${eta.targetS} vs. $truth", abs(eta.targetS!! - truth) / truth < 0.08)
        assertNotNull(eta.fullS)
        assertTrue(eta.fullS!! > eta.targetS!!)
    }

    // Engine sammelt Energie je Schritt und lernt daraus (modelSteps), inkl. Kapazitätsfenster aus CHARGE_COUNTER.
    @Test
    fun engineCollectsStepsAndCounter() {
        val e = Engine(Profile(), Settings(targetPct = 100))
        var t = 0L
        var counter = 2_000_000L // µAh
        for (lv in 400..700 step 10) {
            e.onReading(Reading(t, t, 1, 60, 1, lv, 2, 1, 1, 2_000_000, 4000, null, counter, 300))
            t += 90_000; counter += 50_000
        }
        val s = e.session!!
        val steps = s.modelSteps()
        assertTrue("Schritte: ${steps.size}", steps.size >= 25)
        // 90 s × 2 A·4 V Rohprodukt je Schritt
        steps.forEach { assertEquals(90.0, it.sec, 0.5); assertEquals(90.0 * 2_000_000 * 4000, it.eRaw, 1e9) }
        assertEquals(300, s.counterLastPm - s.counterStartPm)
        assertEquals(1_500_000L, s.counterLastRaw!! - s.counterStartRaw!!) // 1.500 mAh für 30 % → 5.000 mAh
        val m = ChargeModel().apply { learn(steps, s.klass) }
        assertEquals(1, m.sessions)
    }
}
