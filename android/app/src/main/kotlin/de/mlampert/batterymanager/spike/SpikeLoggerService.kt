package de.mlampert.batterymanager.spike

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import de.mlampert.batterymanager.BatteryApp
import de.mlampert.batterymanager.R
import de.mlampert.batterymanager.monitor.NotificationChannels
import java.util.TimeZone

/**
 * Phase-0-Mess-Spike (US-004, S1/S3): zeichnet alle Rohquellen auf, OHNE Flutter, im Prozess :monitor.
 * Verhalten entspricht dem späteren Dienst (spec/engine/spec.md §4):
 *  - Entladen: nur BATTERY_CHANGED + SCREEN_ON/OFF, keine eigene Weckquelle, Flush pro Ereignis.
 *  - Laden: zusätzlich Handler-Tick 10 s (Bildschirm an) / 30 s (aus), Flush alle 60 s.
 *  - Benachrichtigung statisch (0 Updates).
 * Modus "charge_only" (S2/S4): wird vom Job gestartet und beendet sich beim Abstecken.
 */
class SpikeLoggerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var csv: SpikeCsv
    private lateinit var bm: BatteryManager
    private lateinit var pm: PowerManager
    private var registered = false
    private var chargeOnly = false
    private var lastPlug = -1
    private var lastFlushElapsed = 0L
    private var lastBatteryIntent: Intent? = null
    private var unplugSince = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    lastBatteryIntent = intent
                    record("battery_changed", intent)
                }
                Intent.ACTION_SCREEN_ON -> { record("screen_on", lastBatteryIntent); reschedule() }
                Intent.ACTION_SCREEN_OFF -> { record("screen_off", lastBatteryIntent); reschedule() }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (isPlugged()) record("tick", lastBatteryIntent)
            reschedule()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        check(BatteryApp.isMonitorProcess()) { "SpikeLoggerService muss im Prozess :monitor laufen" }
        bm = getSystemService(BatteryManager::class.java)
        pm = getSystemService(PowerManager::class.java)
        csv = SpikeCsv(this, "s1_samples.csv", HEADER)
        NotificationChannels.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Läuft der Logger bereits voll (S1), bleibt er voll – der Job darf ihn nicht auf "nur Laden" umstellen.
        chargeOnly = intent?.getStringExtra(EXTRA_MODE) == MODE_CHARGE_ONLY &&
            !SpikeCsv.flag(this, "logger").exists()
        startInForeground()
        if (!chargeOnly) SpikeCsv.flag(this, "logger").createNewFile()
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            lastBatteryIntent = if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            registered = true
        }
        record(if (chargeOnly) "start_charge_only" else "start", lastBatteryIntent)
        flushNow()
        reschedule()
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        flushNow()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(stopIfStillUnplugged)
        if (registered) unregisterReceiver(receiver)
        registered = false
        record("stop", lastBatteryIntent)
        csv.close()
        super.onDestroy()
    }

    private fun startInForeground() {
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NotificationChannels.MONITOR)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        val n: Notification = builder
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(getString(R.string.spike_notification_title))
            .setContentText(getString(R.string.spike_notification_text))
            .setColor(getColor(R.color.notification_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NotificationChannels.ID_MONITOR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationChannels.ID_MONITOR, n)
        }
    }

    private fun isPlugged(): Boolean =
        (lastBatteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    /** Tick nur beim Laden; beim Entladen keine eigene Weckquelle. */
    private fun reschedule() {
        handler.removeCallbacks(tick)
        if (isPlugged()) {
            handler.postDelayed(tick, if (pm.isInteractive) TICK_SCREEN_ON_MS else TICK_SCREEN_OFF_MS)
        }
    }

    private fun record(trigger: String, battery: Intent?) {
        val now = SystemClock.elapsedRealtime()
        val plug = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: -1
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val cycle = if (Build.VERSION.SDK_INT >= 34) battery?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else null
        csv.append(listOf(
            System.currentTimeMillis(), now, bootCount(), TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000,
            trigger, if (chargeOnly) "charge_only" else "full",
            level, scale,
            battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plug,
            battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1),
            battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
            intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            intProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            longProp(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            intProp(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            if (Build.VERSION.SDK_INT >= 26) intProp(BatteryManager.BATTERY_PROPERTY_STATUS) else null,
            cycle,
            if (pm.isInteractive) 1 else 0,
            if (pm.isIgnoringBatteryOptimizations(packageName)) 1 else 0,
            Process.getElapsedCpuTime(),
            csv.rows, csv.flushes,
        ))

        val stateChange = plug != lastPlug && lastPlug != -1
        if (stateChange && plug == 0) SpikeJobs.scheduleIfEnabled(this)
        lastPlug = plug

        // Flush: pro Ereignis beim Entladen, alle 60 s beim Laden, sofort bei Zustandswechsel.
        if (plug == 0 || stateChange || now - lastFlushElapsed >= FLUSH_CHARGING_MS) flushNow()

        // Modus "Nur beim Laden": nach ≥ 120 s abgesteckt beenden (kürzer = Wackelkontakt).
        if (chargeOnly) {
            if (plug == 0) {
                if (unplugSince == 0L) {
                    unplugSince = now
                    handler.postDelayed(stopIfStillUnplugged, UNPLUG_END_MS)
                }
            } else if (plug > 0) {
                unplugSince = 0L
                handler.removeCallbacks(stopIfStillUnplugged)
            }
        }
    }

    private val stopIfStillUnplugged = Runnable {
        if (!isPlugged()) {
            record("charge_only_end", lastBatteryIntent)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun flushNow() {
        csv.flush()
        lastFlushElapsed = SystemClock.elapsedRealtime()
    }

    private fun intProp(id: Int): Int? = bm.getIntProperty(id).takeUnless { it == Int.MIN_VALUE }
    private fun longProp(id: Int): Long? = bm.getLongProperty(id).takeUnless { it == Long.MIN_VALUE }
    private fun bootCount(): Int = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CHARGE_ONLY = "charge_only"
        const val TICK_SCREEN_ON_MS = 10_000L
        const val TICK_SCREEN_OFF_MS = 30_000L
        const val FLUSH_CHARGING_MS = 60_000L
        const val UNPLUG_END_MS = 120_000L

        val HEADER = listOf(
            "utc_ms", "elapsed_ms", "boot", "tz_min", "trigger", "mode",
            "level", "scale", "status", "plugged", "health", "voltage_raw", "temp_dc",
            "current_now", "current_avg", "charge_counter", "energy_counter", "capacity_pct", "status_prop",
            "cycle_count", "screen_on", "ignoring_battery_opt", "cpu_ms", "rows", "flushes",
        )
    }
}
