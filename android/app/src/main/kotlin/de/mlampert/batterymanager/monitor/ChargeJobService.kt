package de.mlampert.batterymanager.monitor

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import de.mlampert.batterymanager.monitor.engine.Settings
import de.mlampert.batterymanager.monitor.notify.NotificationController

/**
 * Modus „Nur beim Laden“: JobScheduler startet bei Ladebeginn (meist 15–20 min verzögert) den Dienst,
 * der nur während des Ladens läuft. Braucht die Akku-Optimierungs-Ausnahme (FGS-Start aus dem Hintergrund).
 */
class ChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        MonitorRuntime.init(this)
        if (MonitorRuntime.settings.mode != Settings.MODE_CHARGING_ONLY) return false
        val error = MonitorService.start(this)
        if (error != null) {
            NotificationController(this).postStatus(
                "„Nur beim Laden“ konnte nicht starten",
                "Android hat den Start blockiert ($error). Erlaube die Ausnahme von der Akku-Optimierung oder wechsle zu „Immer aktiv“."
            )
            schedule(this, retryMs = 15 * 60_000L)
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 1001

        fun schedule(ctx: Context, retryMs: Long = 0L) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, ChargeJobService::class.java))
                .setRequiresCharging(true)
                .setPersisted(true)
                .apply { if (retryMs > 0) setMinimumLatency(retryMs) }
                .build()
            ctx.getSystemService(JobScheduler::class.java).schedule(info)
        }

        fun cancel(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
