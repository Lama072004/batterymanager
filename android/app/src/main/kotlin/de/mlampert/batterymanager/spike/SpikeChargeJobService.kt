package de.mlampert.batterymanager.spike

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock

/**
 * S2/S4: Prototyp für Modus „Nur beim Laden“. Der Job (requiresCharging) protokolliert seinen
 * Startzeitpunkt (Latenz ab Einstecken) und startet den Logger nur fürs Laden.
 * Der Logger plant den Job beim Abstecken neu.
 */
class SpikeChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val battery: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val jobs = SpikeCsv(this, "s2_jobs.csv", JOB_HEADER)
        jobs.append(listOf(
            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        ))
        jobs.close()

        val starts = SpikeCsv(this, "s4_starts.csv", SpikeJobs.START_HEADER)
        SpikeJobs.startChargeOnlyLogger(this, starts, "job")
        starts.close()
        // Fehlschlag → in 15 min erneut versuchen, statt sofort wieder zu feuern (Endlosschleife beim Laden).
        if (!starts.file.readLines().last().endsWith(",ok")) SpikeJobs.scheduleIfEnabled(this, 15 * 60_000L)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        val JOB_HEADER = listOf("utc_ms", "elapsed_ms", "plugged", "level")
    }
}
