package de.mlampert.batterymanager.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** S4: Startwege nach Neustart und Update prüfen (BOOT_COMPLETED, MY_PACKAGE_REPLACED). */
class SpikeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val csv = SpikeCsv(context, "s4_starts.csv", SpikeJobs.START_HEADER)
        val source = if (action == Intent.ACTION_BOOT_COMPLETED) "boot" else "package_replaced"

        if (SpikeCsv.flag(context, "logger").exists()) {
            val result = try {
                val i = Intent(context, SpikeLoggerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
                "ok"
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
            csv.append(listOf(System.currentTimeMillis(), SystemClock.elapsedRealtime(), source, -1, result))
        }
        SpikeJobs.scheduleIfEnabled(context)
        csv.close()
    }
}
