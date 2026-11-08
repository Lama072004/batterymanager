package de.mlampert.batterymanager.monitor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import de.mlampert.batterymanager.BatteryApp
import de.mlampert.batterymanager.monitor.engine.Settings

/**
 * Hintergrunddienst (FGS specialUse) im Prozess :monitor, ohne Flutter-Engine.
 * Entladen: nur BATTERY_CHANGED + SCREEN_ON/OFF, keine eigene Weckquelle.
 * Laden: zusätzlich Handler-Tick 10 s (Bildschirm an) / 30 s (aus); Handler nutzt uptimeMillis und weckt nicht.
 * Modus „Nur beim Laden“: endet 120 s nach dem Abstecken.
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastBattery: Intent? = null
    private var chargingOnly = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> { lastBattery = intent; MonitorRuntime.onBattery("battery_changed", intent) }
                Intent.ACTION_SCREEN_ON -> MonitorRuntime.onBattery("screen_on", lastBattery)
                Intent.ACTION_SCREEN_OFF -> MonitorRuntime.onBattery("screen_off", lastBattery)
            }
            reschedule()
        }
    }

    private val tick = Runnable {
        if (isPlugged()) MonitorRuntime.onBattery("tick", lastBattery)
        reschedule()
    }

    private val unplugCheck = Runnable { MonitorRuntime.checkUnplugged() }
    private val summaryEnd = Runnable { MonitorRuntime.endSummary() }
    private val alarmReminder = Runnable { MonitorRuntime.alarmReminder() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        check(BatteryApp.isMonitorProcess()) { "MonitorService muss im Prozess :monitor laufen" }
        MonitorRuntime.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        chargingOnly = MonitorRuntime.settings.mode == Settings.MODE_CHARGING_ONLY
        val sticky = MonitorRuntime.sticky(this)
        MonitorRuntime.postInitial(this, sticky)
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            lastBattery = if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, filter)
            registered = true
            MonitorRuntime.onBattery("start", lastBattery)
        }
        reschedule()
        return if (chargingOnly) START_NOT_STICKY else START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MonitorRuntime.flush()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) unregisterReceiver(receiver)
        registered = false
        MonitorRuntime.detach(this)
        super.onDestroy()
    }

    private fun isPlugged(): Boolean = (lastBattery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun reschedule() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(unplugCheck)
        if (isPlugged()) {
            val on = getSystemService(PowerManager::class.java).isInteractive
            handler.postDelayed(tick, if (on) TICK_SCREEN_ON_MS else TICK_SCREEN_OFF_MS)
        } else if (MonitorRuntime.engine.session != null) {
            handler.postDelayed(unplugCheck, 121_000L)
        }
    }

    /** Vom Runtime aufgerufen, wenn eine Ladesitzung endet. */
    fun onSessionEnded() {
        handler.removeCallbacks(summaryEnd)
        handler.postDelayed(summaryEnd, MonitorRuntime.SUMMARY_MS)
        if (chargingOnly) {
            // Modus „Nur beim Laden“: Zusammenfassung stehen lassen, Dienst beenden, Job neu planen.
            MonitorRuntime.flush()
            stopForeground(STOP_FOREGROUND_DETACH)
            ChargeJobService.schedule(this)
            stopSelf()
        }
    }

    fun scheduleAlarmReminder() {
        handler.removeCallbacks(alarmReminder)
        handler.postDelayed(alarmReminder, 10 * 60_000L)
    }

    companion object {
        const val TICK_SCREEN_ON_MS = 10_000L
        const val TICK_SCREEN_OFF_MS = 30_000L

        /** Startet den Dienst (aus Activity, Boot-Receiver oder Job). @return null oder Fehlername. */
        fun start(ctx: Context): String? = try {
            val i = Intent(ctx, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            null
        } catch (e: Exception) {
            e.javaClass.simpleName
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}
