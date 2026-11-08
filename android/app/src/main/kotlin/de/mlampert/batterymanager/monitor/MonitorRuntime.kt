package de.mlampert.batterymanager.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import de.mlampert.batterymanager.monitor.db.AndroidSqlDb
import de.mlampert.batterymanager.monitor.db.BatteryStore
import de.mlampert.batterymanager.monitor.engine.Engine
import de.mlampert.batterymanager.monitor.engine.EtaState
import de.mlampert.batterymanager.monitor.engine.Event
import de.mlampert.batterymanager.monitor.engine.Profile
import de.mlampert.batterymanager.monitor.engine.ProfileDetector
import de.mlampert.batterymanager.monitor.engine.Reading
import de.mlampert.batterymanager.monitor.engine.Settings
import de.mlampert.batterymanager.monitor.notify.Fmt
import de.mlampert.batterymanager.monitor.notify.NotificationController
import de.mlampert.batterymanager.monitor.notify.NotificationController.Kind
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Zustand und Logik des Hintergrunds im Prozess :monitor (ohne Flutter).
 * Wird vom [MonitorService] (Messungen), [MonitorProvider] (UI) und [NotificationActionReceiver] genutzt.
 */
object MonitorRuntime {
    private lateinit var app: Context
    lateinit var store: BatteryStore
        private set
    lateinit var engine: Engine
        private set
    private lateinit var notif: NotificationController
    private val detector = ProfileDetector()

    var settings = Settings()
        private set
    var profile = Profile()
        private set
    var service: MonitorService? = null
        private set
    private var span = 0L
    private val pending = mutableListOf<Pair<Reading, Int>>()
    private var lastFlushElapsed = 0L
    private var lastPointUtc = 0L
    private var lastPointPct = -1
    var flushes = 0L
        private set

    // Sichtbarer Benachrichtigungs-Zustand
    private var alarmShowing = false
    private var heatShowing = false
    private var summaryText: String? = null
    private var summaryUntil = 0L

    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) return
        app = context.applicationContext
        NotificationChannels.ensure(app)
        val db = AndroidSqlDb.openAndMigrate(app)
        store = BatteryStore(db, AndroidSqlDb.loadQuery(app.assets, "retention.sql"))
        settings = store.loadSettings()
        profile = store.loadProfile()
        if (store.profileFingerprint() != null && store.profileFingerprint() != Build.FINGERPRINT && profile.source == 1) {
            profile = Profile(rev = profile.rev + 1) // OTA: neu lernen
            store.saveProfile(profile, Build.FINGERPRINT)
        }
        engine = Engine(profile, settings, store.loadChargeProfile())
        val last = store.lastReading()
        engine.restore(last, store.openSessionFor(last))
        notif = NotificationController(app)
    }

    // ---------------- Dienst ----------------

    @Synchronized
    fun attach(s: MonitorService) {
        init(s)
        service = s
        span = store.openSpan(bootCount(), settings.mode, System.currentTimeMillis())
        lastPointUtc = 0
    }

    @Synchronized
    fun detach(s: MonitorService) {
        if (service !== s) return
        flush()
        store.closeSpan(span, 0, System.currentTimeMillis())
        service = null
    }

    /** Erste Benachrichtigung sofort (startForeground-Pflicht innerhalb von 5 s). */
    @Synchronized
    fun postInitial(s: MonitorService, battery: Intent?) {
        val live = liveFrom(battery, null)
        notif.post(s, if (live.charging) Kind.CHARGING else Kind.IDLE, live, isScreenOn(), alert = false, force = true)
    }

    @Synchronized
    fun onBattery(trigger: String, battery: Intent?) {
        val s = service ?: return
        battery ?: return
        val r = readingFrom(battery) ?: return
        val events = engine.onReading(r)
        process(s, r, events, trigger)
    }

    @Synchronized
    fun checkUnplugged() {
        val s = service ?: return
        val events = engine.checkUnplugged(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (events.isNotEmpty()) handleEvents(s, events, engine.last)
    }

    private fun process(s: MonitorService, r: Reading, events: List<Event>, trigger: String) {
        // Qualitäts-Bits
        val sess = engine.session
        var q = 0
        if (sess != null && r.t - sess.startT < Engine.WARMUP_MS) q = q or 2
        if (sess?.pause == true) q = q or 4
        if (profile.source == 0) q = q or 32
        pending += r to q

        detector.observe(r, profile)?.let { p ->
            if (profile.source != 2) {
                profile = p
                engine.profile = p
                store.saveProfile(p, Build.FINGERPRINT)
            }
        }

        handleEvents(s, events, r)

        // Punkte für Graph 2: bei jedem %-Schritt oder alle 5 min
        engine.session?.let { ses ->
            if (r.plugged && (r.pct != lastPointPct || r.utc - lastPointUtc >= 5 * 60_000)) {
                store.addPoint(ses, r)
                lastPointPct = r.pct; lastPointUtc = r.utc
            }
        }

        val now = SystemClock.elapsedRealtime()
        val stateChange = events.isNotEmpty() || trigger == "start"
        if (!r.plugged || stateChange || now - lastFlushElapsed >= FLUSH_CHARGING_MS) flush()
        if (r.plugged) store.maintenanceIfDue(r.utc)

        if (events.isEmpty()) {
            val live = liveFrom(null, r)
            notif.post(s, currentKind(live), live, isScreenOn(), alert = false, force = false)
        }
    }

    private fun handleEvents(s: MonitorService?, events: List<Event>, r: Reading?) {
        for (e in events) {
            when (e.type) {
                Event.SESSION_START -> engine.session?.let { it.dbId = store.insertSession(it, span, settings.targetPct) }
                Event.SESSION_END -> engine.lastEnded?.let { ended ->
                    val reason = when (e.extra["reason"]) { "unplug" -> 0; "kill" -> 1; "reboot" -> 2; else -> 3 }
                    store.endSession(ended, (e.extra["endUtc"] as? Long) ?: System.currentTimeMillis(), reason)
                    if (reason == 0) { store.learn(ended); engine.chargeProfile = store.loadChargeProfile() }
                    alarmShowing = false
                    summaryText = summaryOf(ended)
                    summaryUntil = SystemClock.elapsedRealtime() + SUMMARY_MS
                    lastPointPct = -1
                }
                Event.TARGET_REACHED, Event.TARGET_EXCEEDED_DURING_OUTAGE ->
                    if (settings.alarm && engine.session?.alarmMuted != true) alarmShowing = true
                Event.HEAT_WARN -> heatShowing = true
                Event.HEAT_CLEAR -> heatShowing = false
            }
        }
        if (events.isEmpty()) return
        flush()
        val live = liveFrom(null, r)
        val kind = currentKind(live)
        val alert = events.any {
            it.type == Event.SESSION_START && it.extra["startKnown"] != false ||
                it.type == Event.TARGET_REACHED || it.type == Event.TARGET_EXCEEDED_DURING_OUTAGE || it.type == Event.HEAT_WARN
        } && (kind != Kind.TARGET || alarmShowing)
        notif.post(s, kind, live, isScreenOn(), alert = alert, force = true)
        if (events.any { it.type == Event.TARGET_REACHED }) engine.session?.let { store.markTargetReached(it, System.currentTimeMillis()) }
        if (events.any { it.type == Event.SESSION_END }) s?.onSessionEnded()
        if (alarmShowing) s?.scheduleAlarmReminder()
    }

    private fun currentKind(live: LiveState): Kind = when {
        alarmShowing && live.charging -> Kind.TARGET
        heatShowing -> Kind.HEAT
        live.charging -> Kind.CHARGING
        summaryText != null && SystemClock.elapsedRealtime() < summaryUntil && settings.summary -> Kind.SUMMARY
        else -> Kind.IDLE
    }

    /** Nach 15 min: Zusammenfassung → wieder „bereit“. */
    @Synchronized
    fun endSummary() {
        summaryText = null
        val s = service ?: return
        val live = liveFrom(null, engine.last)
        notif.post(s, currentKind(live), live, isScreenOn(), alert = false, force = true)
    }

    @Synchronized
    fun alarmReminder() {
        if (!alarmShowing) return
        val live = liveFrom(null, engine.last)
        if (live.charging) notif.post(service, Kind.TARGET, live, isScreenOn(), alert = true, force = true)
    }

    private fun summaryOf(s: Engine.Session): String {
        val endPct = s.lastLevelPm / 10
        val minutes = ((s.lastUtc - s.startUtc) / 60_000).coerceAtLeast(1)
        val dur = if (minutes < 60) "$minutes${Fmt.NNBSP}Min." else "${minutes / 60}${Fmt.NNBSP}Std. ${minutes % 60}${Fmt.NNBSP}Min."
        val start = if (s.startKnown) Fmt.pct(s.startLevelPm / 10) else "Erfasst ab ${Fmt.pct(s.startLevelPm / 10)}"
        val avg = if (s.eRaw > 0 && s.lastUtc > s.startUtc) " · Ø ${Fmt.watts((profile.f * s.eRaw / ((s.lastUtc - s.startUtc) / 1000.0) * profile.sign).toLong())}" else ""
        val max = s.maxPowerMw?.let { ", max. ${Fmt.watts(it)}" } ?: ""
        return "$start → ${Fmt.pct(endPct)} in $dur$avg$max" + if (!s.startKnown) " · Beginn nicht erfasst" else ""
    }

    @Synchronized
    fun flush() {
        if (pending.isEmpty() && engine.session == null) return
        store.insertSamples(pending.toList())
        pending.clear()
        if (span != 0L) store.heartbeat(span, System.currentTimeMillis())
        engine.session?.let { store.updateSession(it) }
        lastFlushElapsed = SystemClock.elapsedRealtime()
        flushes++
    }

    // ---------------- Aktionen ----------------

    @Synchronized
    fun cycleTarget() {
        val order = listOf(80, 85, 90, 100)
        val next = order.firstOrNull { it > settings.targetPct } ?: order.first()
        putSettings(settings.copy(targetPct = next))
    }

    @Synchronized
    fun muteAlarm() {
        engine.muteAlarm()
        alarmShowing = false
        repost()
    }

    @Synchronized
    fun acknowledge() {
        alarmShowing = false
        heatShowing = false
        repost()
    }

    private fun repost() {
        val live = liveFrom(null, engine.last)
        notif.post(service, currentKind(live), live, isScreenOn(), alert = false, force = true)
    }

    @Synchronized
    fun putSettings(s: Settings) {
        val old = settings
        settings = s
        store.saveSettings(s)
        engine.settings = s
        engine.session?.let { if (s.targetPct != old.targetPct && (engine.last?.pct ?: 0) < s.targetPct) it.reached = false }
        if (service != null) repost()
    }

    @Synchronized
    fun setProfile(p: Profile) {
        profile = p.copy(rev = profile.rev + 1)
        engine.profile = profile
        store.saveProfile(profile, Build.FINGERPRINT)
    }

    @Synchronized
    fun clearHistory() {
        flush()
        store.clearHistory()
        engine.restore(null, null)
        engine.chargeProfile = emptyMap()
        if (service != null) span = store.openSpan(bootCount(), settings.mode, System.currentTimeMillis())
    }

    /** Nach dem Einspielen von Demo-Daten (nur Debug): neuen Span öffnen, Engine neu aufsetzen. */
    @Synchronized
    fun reopenSpan() {
        engine.restore(null, null)
        engine.chargeProfile = store.loadChargeProfile()
        if (service != null) span = store.openSpan(bootCount(), settings.mode, System.currentTimeMillis())
    }

    // ---------------- Live-Zustand ----------------

    @Synchronized
    fun snapshot(ctx: Context): LiveState {
        init(ctx)
        val battery = if (service == null) sticky(ctx) else null
        return liveFrom(battery, if (service != null) engine.last else null)
    }

    fun cpuMs(): Long = Process.getElapsedCpuTime()
    fun notifUpdates(): Long = if (::notif.isInitialized) notif.updates else 0

    private fun liveFrom(battery: Intent?, r0: Reading?): LiveState {
        val b = battery ?: sticky(app)
        val r = r0 ?: b?.let { readingFrom(it) }
        val bm = app.getSystemService(BatteryManager::class.java)
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }
        val sess = engine.session?.takeIf { r?.plugged == true }
        val eta = if (sess != null && service != null) engine.eta() else null
        val levelPm = r?.levelPm ?: 0
        return LiveState(
            ts = System.currentTimeMillis(),
            levelPm = levelPm,
            status = r?.status ?: 1,
            plug = r?.plug ?: 0,
            screen = r?.screen,
            currentMa = profile.currentMa(r?.currentRaw),
            voltageMv = profile.voltageMv(r?.voltageRaw),
            powerMw = profile.powerMw(r?.currentRaw, r?.voltageRaw, r?.powerRaw)
                ?.let { p -> sess?.powerEmaMw?.roundToInt()?.toLong()?.takeIf { service != null } ?: p },
            tempDc = profile.tempDc(r?.tempDc),
            health = b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1) ?: 1,
            cycleCount = if (Build.VERSION.SDK_INT >= 34) b?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null,
            capacityMah = counter?.let { c -> if (levelPm >= 200) (c / 1000.0 / (levelPm / 1000.0)).toLong().takeIf { it in 1_500..8_000 } else null },
            etaState = eta?.state ?: if (r?.plugged == true) EtaState.COMPUTING else EtaState.IDLE,
            etaTargetS = eta?.targetS,
            etaFullS = eta?.fullS,
            etaConf = eta?.conf ?: 0.0,
            estimated = eta?.estimated ?: (profile.source == 0),
            targetPct = settings.targetPct,
            alarm = settings.alarm,
            alarmMuted = sess?.alarmMuted ?: false,
            sessionStartUtc = sess?.startUtc,
            sessionStartPct = sess?.startLevelPm?.div(10),
            startKnown = sess?.startKnown ?: true,
            fullSinceUtc = sess?.fullSinceUtc,
            peakMw = sess?.maxPowerMw,
            profileRev = profile.rev,
            profileSource = profile.source,
            serviceRunning = service != null,
            summary = summaryText,
        )
    }

    private fun readingFrom(b: Intent): Reading? {
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0 || level > scale) return null
        val bm = app.getSystemService(BatteryManager::class.java)
        val now = System.currentTimeMillis()
        return Reading(
            t = SystemClock.elapsedRealtime(),
            utc = now,
            boot = bootCount(),
            tzMin = TimeZone.getDefault().getOffset(now) / 60_000,
            span = span,
            levelPm = (1000.0 * level / scale).roundToInt(),
            status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 1),
            plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            screen = if (isScreenOn()) 1 else 0,
            currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeUnless { it == Int.MIN_VALUE }?.toLong(),
            voltageRaw = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }?.toLong(),
            powerRaw = null,
            counterRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeUnless { it == Int.MIN_VALUE || it <= 0 }?.toLong(),
            tempDc = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
        )
    }

    fun sticky(ctx: Context): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun isScreenOn(): Boolean = app.getSystemService(PowerManager::class.java).isInteractive

    private fun bootCount(): Int = AndroidSettings.Global.getInt(app.contentResolver, AndroidSettings.Global.BOOT_COUNT, 0)

    const val FLUSH_CHARGING_MS = 60_000L
    const val SUMMARY_MS = 15 * 60_000L
}
