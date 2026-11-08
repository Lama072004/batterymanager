package de.mlampert.batterymanager.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.mlampert.batterymanager.monitor.engine.Settings
import de.mlampert.batterymanager.monitor.notify.NotificationController

/** Neustart nach Boot und App-Update (Prozess :monitor). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        MonitorRuntime.init(context)
        val s = MonitorRuntime.settings
        if (!s.onboarded) return
        if (s.mode == Settings.MODE_ALWAYS) {
            MonitorService.start(context)?.let {
                NotificationController(context).postStatus("Hintergrunddienst blockiert", "Android hat den Start nach dem Neustart verhindert ($it). Öffne die App einmal.")
            }
        } else {
            ChargeJobService.schedule(context)
        }
    }
}

/** Aktionen der Lade-Benachrichtigung – laufen nativ, ohne Flutter. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MonitorRuntime.init(context)
        when (intent.action) {
            ACTION_CYCLE_TARGET -> MonitorRuntime.cycleTarget()
            ACTION_MUTE -> MonitorRuntime.muteAlarm()
            ACTION_ACK -> MonitorRuntime.acknowledge()
        }
    }

    companion object {
        const val ACTION_CYCLE_TARGET = "de.mlampert.batterymanager.CYCLE_TARGET"
        const val ACTION_MUTE = "de.mlampert.batterymanager.MUTE"
        const val ACTION_ACK = "de.mlampert.batterymanager.ACK"
    }
}
