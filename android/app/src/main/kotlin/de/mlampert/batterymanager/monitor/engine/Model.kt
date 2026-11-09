package de.mlampert.batterymanager.monitor.engine

import kotlin.math.floor

/** SQLite-ROUND: halb von null weg (identisch zu den Views in spec/db/001_init.sql). */
fun roundHalfAway(x: Double): Long = if (x >= 0) floor(x + 0.5).toLong() else -floor(-x + 0.5).toLong()

/** Geräteprofil = Zeile device_profile. Normalisierung identisch zum View sample_n. */
data class Profile(
    val iToMa: Double = 0.001,
    val vToMv: Double = 1.0,
    val pToMw: Double = 1.0,
    val sign: Int = 1,
    val cells: Int = 1,
    val pSrc: Int = 0,
    val source: Int = 0,
    val rev: Int = 0,
) {
    val f: Double get() = if (pSrc == 1) pToMw else iToMa * vToMv * cells / 1000.0
    val currentSupported: Boolean get() = pSrc == 1 || iToMa != 0.0

    fun currentMa(raw: Long?): Long? {
        if (raw == null || iToMa == 0.0) return null
        return roundHalfAway(raw * iToMa * sign).takeIf { it in -15_000..15_000 }
    }

    fun voltageMv(raw: Long?): Long? = raw?.let { roundHalfAway(it * vToMv) }?.takeIf { it in 2_500..10_000 }

    fun powerMw(currentRaw: Long?, voltageRaw: Long?, powerRaw: Long?): Long? {
        val p = when {
            pSrc == 1 -> powerRaw?.let { roundHalfAway(sign * f * it) }
            iToMa == 0.0 || currentRaw == null || voltageRaw == null -> null
            else -> roundHalfAway(sign * f * currentRaw * voltageRaw)
        } ?: return null
        if (p !in -150_000..150_000) return null
        if (pSrc != 1 && voltageMv(voltageRaw) == null) return null
        return p
    }

    fun tempDc(raw: Int?): Int? = raw?.takeIf { it in -200..700 }
}

/** Ein Messpunkt mit Rohwerten (spec/engine/spec.md §2). t = elapsedRealtime. */
data class Reading(
    val t: Long,
    val utc: Long,
    val boot: Int,
    val tzMin: Int,
    val span: Long,
    val levelPm: Int,
    val status: Int,
    val plug: Int,
    val screen: Int?,
    val currentRaw: Long?,
    val voltageRaw: Long?,
    val powerRaw: Long?,
    val counterRaw: Long?,
    val tempDc: Int?,
) {
    val plugged: Boolean get() = plug > 0
    val pct: Int get() = levelPm / 10
}

data class Settings(
    val targetPct: Int = 80,
    val alarm: Boolean = true,
    val mode: Int = MODE_ALWAYS,
    val heatWarnDc: Int = 450,
    val summary: Boolean = true,
    val onboarded: Boolean = false,
    /** Android 16+: Watt dauerhaft als Chip in der Statusleiste (Live Update). Standard aus = nur Symbol. */
    val liveChip: Boolean = false,
) {
    companion object {
        const val MODE_ALWAYS = 0
        const val MODE_CHARGING_ONLY = 1
    }
}

data class Event(val type: String, val extra: Map<String, Any?> = emptyMap()) {
    companion object {
        const val SESSION_START = "SESSION_START"
        const val SESSION_RESUME = "SESSION_RESUME"
        const val SESSION_END = "SESSION_END"
        const val PAUSE_START = "PAUSE_START"
        const val PAUSE_END = "PAUSE_END"
        const val DEFICIT_START = "DEFICIT_START"
        const val DEFICIT_END = "DEFICIT_END"
        const val TARGET_REACHED = "TARGET_REACHED"
        const val TARGET_ALREADY_REACHED = "TARGET_ALREADY_REACHED"
        const val TARGET_EXCEEDED_DURING_OUTAGE = "TARGET_EXCEEDED_DURING_OUTAGE"
        const val SEGMENT_BREAK = "SEGMENT_BREAK"
        const val CLOCK_JUMP = "CLOCK_JUMP"
        const val HEAT_WARN = "HEAT_WARN"
        const val HEAT_CLEAR = "HEAT_CLEAR"
    }
}

enum class EtaState { IDLE, COMPUTING, OK, APPROX, NONE, PAUSE, DEFICIT, FULL }

data class Eta(
    val state: EtaState,
    val targetS: Long? = null,
    val fullS: Long? = null,
    val conf: Double = 0.0,
    val estimated: Boolean = false,
)
