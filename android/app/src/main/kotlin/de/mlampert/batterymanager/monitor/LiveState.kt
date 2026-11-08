package de.mlampert.batterymanager.monitor

import android.os.Bundle
import de.mlampert.batterymanager.monitor.engine.EtaState

/** Live-Zustand für Benachrichtigung und UI (über ContentProvider.call → Bundle aus Primitiven). */
data class LiveState(
    val ts: Long,
    val levelPm: Int,
    val status: Int,
    val plug: Int,
    val screen: Int?,
    val currentMa: Long?,
    val voltageMv: Long?,
    val powerMw: Long?,
    val tempDc: Int?,
    val health: Int,
    val cycleCount: Int?,
    val capacityMah: Long?,
    val etaState: EtaState,
    val etaTargetS: Long?,
    val etaFullS: Long?,
    val etaConf: Double,
    val estimated: Boolean,
    val targetPct: Int,
    val alarm: Boolean,
    val alarmMuted: Boolean,
    val sessionStartUtc: Long?,
    val sessionStartPct: Int?,
    val startKnown: Boolean,
    val fullSinceUtc: Long?,
    val peakMw: Long?,
    val profileRev: Int,
    val profileSource: Int,
    val serviceRunning: Boolean,
    val summary: String? = null,
) {
    val pct: Int get() = levelPm / 10
    val charging: Boolean get() = plug > 0

    fun toBundle(): Bundle = Bundle().apply {
        putInt("v", 1)
        putLong("ts", ts); putInt("levelPm", levelPm); putInt("status", status); putInt("plug", plug)
        screen?.let { putInt("screen", it) }
        currentMa?.let { putLong("currentMa", it) }
        voltageMv?.let { putLong("voltageMv", it) }
        powerMw?.let { putLong("powerMw", it) }
        tempDc?.let { putInt("tempDc", it) }
        putInt("health", health)
        cycleCount?.let { putInt("cycleCount", it) }
        capacityMah?.let { putLong("capacityMah", it) }
        putString("etaState", etaState.name.lowercase())
        etaTargetS?.let { putLong("etaTargetS", it) }
        etaFullS?.let { putLong("etaFullS", it) }
        putDouble("etaConf", etaConf); putBoolean("estimated", estimated)
        putInt("targetPct", targetPct); putBoolean("alarm", alarm); putBoolean("alarmMuted", alarmMuted)
        sessionStartUtc?.let { putLong("sessionStartUtc", it) }
        sessionStartPct?.let { putInt("sessionStartPct", it) }
        putBoolean("startKnown", startKnown)
        fullSinceUtc?.let { putLong("fullSinceUtc", it) }
        peakMw?.let { putLong("peakMw", it) }
        putInt("profileRev", profileRev); putInt("profileSource", profileSource)
        putBoolean("serviceRunning", serviceRunning)
    }
}
