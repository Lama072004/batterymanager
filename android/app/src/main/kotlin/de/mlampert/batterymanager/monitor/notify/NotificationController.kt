package de.mlampert.batterymanager.monitor.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import de.mlampert.batterymanager.MainActivity
import de.mlampert.batterymanager.R
import de.mlampert.batterymanager.monitor.LiveState
import de.mlampert.batterymanager.monitor.NotificationActionReceiver
import de.mlampert.batterymanager.monitor.NotificationChannels
import de.mlampert.batterymanager.monitor.engine.EtaState
import kotlin.math.abs

/**
 * Genau EINE App-Benachrichtigung (ID 1). Der Kanal wechselt je Zustand; Signal (Heads-up/Ton) gibt es
 * nur beim Einstecken, bei Hitze und beim Ziel-Alarm. Updates höchstens alle 10 s, nur bei geändertem
 * Rundungswert und nie bei ausgeschaltetem Bildschirm (Tuner).
 */
class NotificationController(private val ctx: Context) {

    enum class Kind { IDLE, CHARGING, HEAT, TARGET, SUMMARY }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var lastPostElapsed = 0L
    private var lastKey: String? = null
    private var lastPowerMw: Long? = null
    private var lastPct: Int? = null
    private var lastEtaS: Long? = null
    var updates = 0L
        private set

    /**
     * @param alert true = einmal auffällig (Heads-up bzw. Ton beim Alarm-Kanal).
     * @param force Zustandswechsel → Drosselung umgehen.
     */
    fun post(service: Service?, kind: Kind, live: LiveState, screenOn: Boolean, alert: Boolean, force: Boolean) {
        val key = "$kind/${live.etaState}/${live.charging}"
        val now = SystemClock.elapsedRealtime()
        if (!force && key == lastKey) {
            if (!screenOn) return
            if (now - lastPostElapsed < MIN_INTERVAL_MS) return
            val dW = abs((live.powerMw ?: 0) - (lastPowerMw ?: 0)) >= 500
            val dPct = live.pct != lastPct
            val dEta = abs((live.etaTargetS ?: live.etaFullS ?: 0) - (lastEtaS ?: 0)) >= 60
            if (!dW && !dPct && !dEta) return
        }
        val n = build(kind, live, alert)
        if (service != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(NotificationChannels.ID_MONITOR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                service.startForeground(NotificationChannels.ID_MONITOR, n)
            }
        } else {
            nm.notify(NotificationChannels.ID_MONITOR, n)
        }
        lastKey = key
        lastPostElapsed = now
        lastPowerMw = live.powerMw
        lastPct = live.pct
        lastEtaS = live.etaTargetS ?: live.etaFullS
        updates++
    }

    fun postStatus(title: String, text: String) {
        val n = builder(NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        nm.notify(NotificationChannels.ID_STATUS, n)
    }

    private fun build(kind: Kind, live: LiveState, alert: Boolean): Notification {
        val channel = when (kind) {
            Kind.IDLE -> NotificationChannels.MONITOR
            Kind.HEAT -> NotificationChannels.HEAT
            Kind.TARGET -> NotificationChannels.TARGET_ALARM
            Kind.CHARGING, Kind.SUMMARY -> NotificationChannels.CHARGING
        }
        val (title, text) = texts(kind, live)
        val b = builder(channel)
            .setSmallIcon(
                when (kind) {
                    Kind.IDLE -> R.drawable.ic_stat_battery
                    Kind.TARGET -> R.drawable.ic_stat_target
                    else -> R.drawable.ic_stat_charging
                }
            )
            .setContentTitle(title)
            .setContentText(text)
            .setColor(ctx.getColor(R.color.notification_accent))
            .setOnlyAlertOnce(!alert)
            .setShowWhen(false)
            .setOngoing(kind != Kind.SUMMARY)
            .setContentIntent(openApp())
            .setCategory(if (kind == Kind.TARGET) Notification.CATEGORY_ALARM else Notification.CATEGORY_PROGRESS)
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            b.setPriority(if (kind == Kind.IDLE) Notification.PRIORITY_MIN else Notification.PRIORITY_HIGH)
        }
        if (live.charging && kind != Kind.SUMMARY) {
            b.setProgress(100, live.pct, false)
            b.addAction(action(R.drawable.ic_stat_target, "Ziel ${Fmt.pct(live.targetPct)}", NotificationActionReceiver.ACTION_CYCLE_TARGET, 1))
            if (live.alarm && !live.alarmMuted && kind != Kind.TARGET) {
                b.addAction(action(R.drawable.ic_stat_battery, "Alarm aus", NotificationActionReceiver.ACTION_MUTE, 2))
            }
            if (Build.VERSION.SDK_INT >= 36) applyLiveUpdate(b, live)
        }
        if (kind == Kind.TARGET || kind == Kind.HEAT) {
            b.addAction(action(R.drawable.ic_stat_target, "Verstanden", NotificationActionReceiver.ACTION_ACK, 3))
        }
        return b.build()
    }

    private fun applyLiveUpdate(b: Notification.Builder, live: LiveState) {
        val accent = ctx.getColor(R.color.notification_accent)
        val style = Notification.ProgressStyle()
            .setProgressSegments(listOf(
                Notification.ProgressStyle.Segment(live.targetPct).setColor(accent),
                Notification.ProgressStyle.Segment(100 - live.targetPct),
            ))
            .setProgressPoints(listOf(Notification.ProgressStyle.Point(live.targetPct).setColor(accent)))
            .setProgress(live.pct)
            .setStyledByProgress(true)
        b.setStyle(style)
        // Live Update (Android 16): Promotion anfragen (Notification.EXTRA_REQUEST_PROMOTED_ONGOING).
        b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        live.powerMw?.takeIf { it > 0 }?.let { b.setShortCriticalText(Fmt.watts(it)) }
    }

    fun texts(kind: Kind, live: LiveState): Pair<String, String> {
        val pct = Fmt.pct(live.pct)
        val w = live.powerMw?.let { Fmt.watts(it) }
        return when (kind) {
            Kind.IDLE -> "Batterymanager ist bereit" to "Lade-Info erscheint beim Einstecken."
            Kind.SUMMARY -> "Ladevorgang beendet" to (live.summary ?: "")
            Kind.TARGET -> "Ladeziel ${Fmt.pct(live.targetPct)} erreicht" to "Jetzt abstecken, um den Akku zu schonen."
            Kind.HEAT -> "Akku warm: ${live.tempDc?.let { Fmt.tempC(it) } ?: "–"}" + (w?.let { " · $it" } ?: "") to
                (if (live.charging) etaLine(live) else "Gerät abkühlen lassen.")
            Kind.CHARGING -> when (live.etaState) {
                EtaState.COMPUTING -> "Lädt · $pct" to "Ladeleistung wird ermittelt …"
                EtaState.DEFICIT -> "Eingesteckt, entlädt trotzdem" to "Ladegerät zu schwach" + (w?.let { " ($it)." } ?: ".")
                EtaState.PAUSE -> "Ladepause · $pct" to "Gerät pausiert das Laden (Limit/adaptiv)."
                EtaState.FULL -> "Voll geladen" to (live.fullSinceUtc?.let { "Seit ${Fmt.clock(it)} bei 100${Fmt.NNBSP}%" } ?: "100${Fmt.NNBSP}%")
                else -> {
                    val title = when {
                        w == null -> "Lädt · $pct"
                        (live.powerMw ?: 0) in 1..7_499 -> "Lädt langsam · $w · $pct"
                        else -> "Lädt mit $w · $pct"
                    }
                    val prefix = if (!live.startKnown && live.sessionStartUtc != null) "Erfasst seit ${Fmt.clock(live.sessionStartUtc)} · " else ""
                    title to prefix + etaLine(live)
                }
            }
        }
    }

    private fun etaLine(live: LiveState): String {
        val step = if (live.etaState == EtaState.OK) 5 else 15
        val ca = if (live.etaState == EtaState.OK) "" else "ca. "
        val parts = mutableListOf<String>()
        val t = live.etaTargetS
        if (t != null && t > 0 && live.pct < live.targetPct) parts += "${Fmt.pct(live.targetPct)} in $ca${Fmt.duration(t, step)}"
        val f = live.etaFullS
        if (f != null && f > 0) parts += "Voll ca. ${Fmt.clock(System.currentTimeMillis() + f * 1000)}"
        return if (parts.isEmpty()) "Restzeit wird berechnet …" else parts.joinToString(" · ")
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, label: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, icon), label, pi).build()
    }

    companion object {
        const val MIN_INTERVAL_MS = 10_000L
    }
}
