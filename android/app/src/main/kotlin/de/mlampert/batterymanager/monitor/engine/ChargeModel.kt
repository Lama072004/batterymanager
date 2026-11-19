package de.mlampert.batterymanager.monitor.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Lernendes Restzeit-Modell (läuft komplett auf dem Gerät, ohne Netz).
 *
 * Die Zeit für einen Prozentpunkt ist Energie ÷ Leistung:
 *   sec(p) = E(p) / P(p)
 * - E(p): Energie, die der Akku für den Schritt p → p+1 braucht. Hängt kaum vom Ladegerät ab und wird
 *   aus allen bisherigen Ladevorgängen gelernt (gleitender Mittelwert je Prozentpunkt).
 * - P(p): Ladeleistung bei p. Vorhergesagt aus der AKTUELLEN Leistung und der gelernten Leistungskurve
 *   der Leistungsklasse: P(p) = P_jetzt · exp(L(p) − L(p_jetzt)). L ist der Logarithmus der Leistung je
 *   Prozentpunkt, gelernt als additives Modell (Kurve + Versatz je Ladevorgang), damit verschiedene
 *   Netzteile derselben Klasse die Form nicht verfälschen.
 * - Bias: Abweichung früherer Prognosen „bis Ziel“ von der tatsächlichen Dauer (log-Verhältnis, EWMA).
 *
 * Alle Größen sind Rohprodukte (Strom·Spannung wie geliefert), also einheitenfrei im Verhältnis.
 */
class ChargeModel {
    /** Energie je Prozentpunkt (|Rohprodukt|·s) und Anzahl Beobachtungen. */
    val energy = DoubleArray(100)
    val energyN = IntArray(100)
    /** Log-Leistungskurve je Klasse 1..3 (Index 0 = Klasse unbekannt, nutzt Klasse 2). */
    val shape = Array(4) { DoubleArray(100) }
    val shapeN = Array(4) { IntArray(100) }
    /** ln(tatsächlich ÷ Prognose), gleitend gemittelt. */
    var bias = 0.0
    var biasN = 0
    /** Anzahl gelernter Ladevorgänge. */
    var sessions = 0

    data class Step(val pct: Int, val sec: Double, val eRaw: Double)

    /** Aus den Prozentschritten eines abgeschlossenen Ladevorgangs lernen. */
    fun learn(steps: List<Step>, klass: Int) {
        val valid = steps.filter { it.pct in 0..99 && it.sec in 5.0..3600.0 && abs(it.eRaw) > 0 }
        if (valid.size < 3) return
        // Energie je Prozentpunkt: Ausreißer (> 2× Median) verwerfen.
        val med = valid.map { abs(it.eRaw) }.sorted()[valid.size / 2]
        for (s in valid) {
            val e = abs(s.eRaw)
            if (e > 2 * med || e < med / 2) continue
            val n = energyN[s.pct]
            energy[s.pct] = if (n == 0) e else energy[s.pct] + rate(n) * (e - energy[s.pct])
            energyN[s.pct] = n + 1
        }
        // Leistungskurve: log P je Schritt, Versatz dieses Ladevorgangs gegenüber der bisherigen Kurve.
        val k = klass.coerceIn(0, 3).let { if (it == 0) 2 else it }
        val logs = valid.map { it.pct to ln(abs(it.eRaw) / it.sec) }
        val known = logs.filter { shapeN[k][it.first] > 0 }
        val offset = if (known.isEmpty()) 0.0 else known.map { it.second - shape[k][it.first] }.median()
        for ((p, lp) in logs) {
            val y = lp - offset
            val n = shapeN[k][p]
            shape[k][p] = if (n == 0) y else shape[k][p] + rate(n) * (y - shape[k][p])
            shapeN[k][p] = n + 1
        }
        sessions++
    }

    /**
     * Prognose „bis Ziel“ (bereits inkl. bisherigem Bias) mit der tatsächlichen Dauer vergleichen.
     * Der Rest-Fehler verschiebt den Bias schrittweise (am Anfang vorsichtig, damit ein Ausreißer nicht alles kippt).
     */
    fun learnBias(predictedS: Double, actualS: Double) {
        if (predictedS < 120 || actualS < 120) return
        val r = ln(actualS / predictedS).coerceIn(-1.0, 1.0)
        bias = (bias + (if (biasN == 0) 0.5 else 0.3) * r).coerceIn(-1.0, 1.0)
        biasN++
    }

    /** Genug gelernt, um vom aktuellen Stand bis [to] zu rechnen? */
    fun covers(from: Int, to: Int): Boolean {
        if (sessions < 1 || to <= from) return sessions >= 1
        val n = (from until to).count { energyN[it.coerceIn(0, 99)] > 0 }
        return n >= 0.6 * (to - from)
    }

    /**
     * Sekunden von [from] bis [to] bei aktueller Leistung [powerRaw] (|Rohprodukt|) und Klasse [klass].
     * [kEnergy] korrigiert die Energie mit dem, was dieser Ladevorgang bisher gezeigt hat.
     */
    fun seconds(from: Int, to: Int, powerRaw: Double, klass: Int, kEnergy: Double = 1.0): Double? {
        if (to <= from) return 0.0
        if (powerRaw <= 0 || sessions < 1) return null
        val k = klass.coerceIn(0, 3).let { if (it == 0) 2 else it }
        val lNow = logPower(k, from)
        var sum = 0.0
        for (p in from until to) {
            val e = energyAt(p) ?: return null
            val pw = powerRaw * exp(logPower(k, p) - lNow)
            sum += e * kEnergy / pw
        }
        return sum * exp(bias)
    }

    /** Energie bei p: gelernt, sonst lineare Interpolation der Nachbarn, am Rand der nächste Wert. */
    fun energyAt(p: Int): Double? {
        if (energyN[p] > 0) return energy[p]
        val lo = (p downTo 0).firstOrNull { energyN[it] > 0 }
        val hi = (p until 100).firstOrNull { energyN[it] > 0 }
        return when {
            lo != null && hi != null -> energy[lo] + (energy[hi] - energy[lo]) * (p - lo) / (hi - lo).toDouble()
            lo != null -> energy[lo]
            hi != null -> energy[hi]
            else -> null
        }
    }

    /**
     * Log-Leistung bei p für Klasse k: gelernt bzw. interpoliert. Außerhalb des Gelernten gilt die
     * typische Form (konstant bis 80 %, danach CV-Phase mit fallender Leistung).
     */
    fun logPower(k: Int, p: Int): Double {
        val s = shape[k]; val n = shapeN[k]
        if (n[p] > 0) return s[p]
        val lo = (p downTo 0).firstOrNull { n[it] > 0 }
        val hi = (p until 100).firstOrNull { n[it] > 0 }
        return when {
            lo != null && hi != null -> s[lo] + (s[hi] - s[lo]) * (p - lo) / (hi - lo).toDouble()
            lo != null -> s[lo] + typical(p) - typical(lo)
            hi != null -> s[hi] + typical(p) - typical(hi)
            else -> typical(p)
        }
    }

    private fun typical(p: Int): Double = if (p < 80) 0.0 else -ln(1.0 + 2.0 * (p - 80) / 20.0)

    /** Schnell lernen am Anfang, später stabil (mind. 10 % Gewicht für neue Ladevorgänge). */
    private fun rate(n: Int): Double = maxOf(1.0 / (n + 1), 0.1)

    private fun List<Double>.median(): Double = sorted().let { it[it.size / 2] }
}
