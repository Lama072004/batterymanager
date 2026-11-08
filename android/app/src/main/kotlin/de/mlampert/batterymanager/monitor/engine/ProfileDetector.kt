package de.mlampert.batterymanager.monitor.engine

import kotlin.math.abs

/**
 * Lernt Einheit, Vorzeichen und Strom-Unterstützung des Geräts (spec/engine/spec.md §8).
 * Ein manuelles Profil (source = 2) wird nie überschrieben.
 */
class ProfileDetector {
    private var signPos = 0
    private var signNeg = 0
    private var chargeIntegralRawS = 0.0
    private var chargeCounterStart: Long? = null
    private var chargeCounterLast: Long? = null
    private var chargeDurationMs = 0L
    private var chargeAbsRaw = mutableListOf<Long>()
    private var zeroWhileRising = 0L
    private var prev: Reading? = null

    /** @return neues Profil, wenn sich etwas geändert hat, sonst null. */
    fun observe(r: Reading, current: Profile): Profile? {
        val p = prev
        prev = r
        if (current.source == 2 || p == null || r.span != p.span || r.boot != p.boot) {
            if (p == null || r.span != p.span) resetCharge()
            return null
        }
        val dt = r.t - p.t
        if (dt <= 0) return null

        if (!r.plugged && r.currentRaw != null && r.currentRaw != 0L) {
            if (r.currentRaw > 0) signPos++ else signNeg++
        }
        if (r.plugged && p.plugged) {
            chargeDurationMs += dt
            r.currentRaw?.let { cr ->
                chargeIntegralRawS += abs(cr.toDouble()) * dt / 1000.0
                if (cr != 0L && chargeAbsRaw.size < 500) chargeAbsRaw += abs(cr)
            }
            if (r.counterRaw != null) {
                if (chargeCounterStart == null) chargeCounterStart = p.counterRaw ?: r.counterRaw
                chargeCounterLast = r.counterRaw
            }
            if ((r.currentRaw == null || r.currentRaw == 0L) && r.levelPm > p.levelPm) zeroWhileRising += dt
        } else if (!r.plugged) {
            resetCharge()
        }

        var next = current
        // Vorzeichen: Mehrheitsvotum beim Entladen (≥ 20 Stimmen, ≥ 80 %)
        val votes = signPos + signNeg
        if (votes >= 20) {
            val sign = when {
                signNeg >= votes * 0.8 -> 1   // Entladen negativ → Laden positiv
                signPos >= votes * 0.8 -> -1  // Entladen positiv → Vorzeichen umdrehen
                else -> next.sign
            }
            if (sign != next.sign) next = next.copy(sign = sign)
        }
        // Strom nicht unterstützt: ≥ 10 min Laden bei steigendem Ladestand ohne Strom
        if (zeroWhileRising >= 10 * 60_000L && chargeAbsRaw.isEmpty()) {
            if (next.iToMa != 0.0) next = next.copy(iToMa = 0.0)
        } else if (chargeDurationMs >= 10 * 60_000L && chargeAbsRaw.size >= 5) {
            // Einheit: ΔCHARGE_COUNTER (µAh) gegen ∫|I|·dt
            val cs = chargeCounterStart
            val cl = chargeCounterLast
            val unit = if (cs != null && cl != null && cl > cs && chargeIntegralRawS > 0) {
                val ratio = (cl - cs) / (chargeIntegralRawS / 3600.0)
                when { ratio in 0.3..3.0 -> 0.001; ratio in 300.0..3000.0 -> 1.0; else -> null }
            } else {
                val median = chargeAbsRaw.sorted()[chargeAbsRaw.size / 2]
                if (median < 20_000) 1.0 else 0.001
            }
            if (unit != null && unit != next.iToMa) next = next.copy(iToMa = unit)
        }
        if (next == current) {
            if (current.source == 0 && votes >= 20 && chargeDurationMs >= 10 * 60_000L) {
                return current.copy(source = 1, rev = current.rev + 1)
            }
            return null
        }
        return next.copy(source = 1, rev = current.rev + 1)
    }

    private fun resetCharge() {
        chargeIntegralRawS = 0.0
        chargeCounterStart = null
        chargeCounterLast = null
        chargeDurationMs = 0
        chargeAbsRaw.clear()
        zeroWhileRising = 0
    }
}
