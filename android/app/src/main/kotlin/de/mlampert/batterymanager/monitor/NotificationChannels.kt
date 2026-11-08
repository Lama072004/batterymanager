package de.mlampert.batterymanager.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import de.mlampert.batterymanager.R

/**
 * Endgültige Kanal-IDs und Importance-Stufen (nachträglich nicht änderbar, siehe design.md).
 * Es ist immer höchstens EINE App-Benachrichtigung sichtbar; der Kanal wechselt je Zustand.
 */
object NotificationChannels {
    const val MONITOR = "monitor"
    const val CHARGING = "charging"
    const val TARGET_ALARM = "target_alarm"
    const val HEAT = "heat"
    const val STATUS = "status"

    /** Notification-IDs: 1 Dauerdienst, 2 Laden (ersetzt 1), 3 Fallback Alarm, 5 Status. */
    const val ID_MONITOR = 1
    const val ID_CHARGING = 2
    const val ID_ALARM_FALLBACK = 3
    const val ID_STATUS = 5

    val ALL = listOf(MONITOR, CHARGING, TARGET_ALARM, HEAT, STATUS)

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val monitor = NotificationChannel(
            MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val charging = NotificationChannel(
            CHARGING, context.getString(R.string.channel_charging), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_charging_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val alarm = NotificationChannel(
            TARGET_ALARM, context.getString(R.string.channel_target_alarm), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_target_alarm_desc)
            setShowBadge(false)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        }
        val heat = NotificationChannel(
            HEAT, context.getString(R.string.channel_heat), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_heat_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val status = NotificationChannel(
            STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_status_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(monitor, charging, alarm, heat, status))
    }
}
