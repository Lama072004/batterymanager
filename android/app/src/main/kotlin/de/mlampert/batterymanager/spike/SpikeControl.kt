package de.mlampert.batterymanager.spike

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Steuerung des Mess-Spikes per adb, ohne UI (siehe spec/spike-anleitung.md):
 *   adb shell am start -n de.mlampert.batterymanager/.MainActivity --es spike start|stop|job_on|job_off
 */
object SpikeControl {
    const val EXTRA = "spike"

    fun handle(context: Context, intent: Intent?) {
        when (intent?.getStringExtra(EXTRA)) {
            "start" -> {
                val i = Intent(context, SpikeLoggerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
            "stop" -> {
                SpikeCsv.flag(context, "logger").delete()
                context.stopService(Intent(context, SpikeLoggerService::class.java))
            }
            "job_on" -> SpikeJobs.enable(context, true)
            "job_off" -> SpikeJobs.enable(context, false)
        }
    }
}
