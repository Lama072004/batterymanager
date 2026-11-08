package de.mlampert.batterymanager.spike

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Planung des S2/S4-Jobs („Nur beim Laden“-Prototyp). Prozessübergreifend über eine Flag-Datei. */
object SpikeJobs {
    const val JOB_ID = 4711

    fun enable(context: Context, on: Boolean) {
        val flag = SpikeCsv.flag(context, "job")
        val js = context.getSystemService(JobScheduler::class.java)
        if (on) {
            flag.createNewFile()
            schedule(context, 0L)
        } else {
            flag.delete()
            js.cancel(JOB_ID)
        }
    }

    fun scheduleIfEnabled(context: Context, minLatencyMs: Long = 0L) {
        if (SpikeCsv.flag(context, "job").exists()) schedule(context, minLatencyMs)
    }

    private fun schedule(context: Context, minLatencyMs: Long) {
        val js = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, SpikeChargeJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .apply { if (minLatencyMs > 0) setMinimumLatency(minLatencyMs) }
            .build()
        js.schedule(info)
    }

    /** Startet den Logger im Modus „Nur beim Laden“; protokolliert Erfolg/Fehlschlag (S4). */
    fun startChargeOnlyLogger(context: Context, csv: SpikeCsv, source: String) {
        val pm = context.getSystemService(PowerManager::class.java)
        val ok = try {
            val i = Intent(context, SpikeLoggerService::class.java)
                .putExtra(SpikeLoggerService.EXTRA_MODE, SpikeLoggerService.MODE_CHARGE_ONLY)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            "ok"
        } catch (e: Exception) {
            // Ab Android 12: ForegroundServiceStartNotAllowedException ohne Akku-Optimierungs-Ausnahme.
            e.javaClass.simpleName
        }
        csv.append(listOf(System.currentTimeMillis(), SystemClock.elapsedRealtime(), source,
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) 1 else 0, ok))
        csv.flush()
    }

    val START_HEADER = listOf("utc_ms", "elapsed_ms", "source", "ignoring_battery_opt", "fgs_start")
}
