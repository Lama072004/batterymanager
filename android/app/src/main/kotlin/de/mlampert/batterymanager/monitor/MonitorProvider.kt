package de.mlampert.batterymanager.monitor

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import de.mlampert.batterymanager.monitor.db.AndroidSqlDb
import de.mlampert.batterymanager.monitor.engine.Profile
import de.mlampert.batterymanager.monitor.engine.Settings

/**
 * Schmale, versionierte Schnittstelle UI → Dienst (Prozess :monitor, nicht exportiert).
 * Nur `call()`; die UI liest die Historie selbst read-only aus der SQLite-Datei.
 * Jede Antwort enthält `v = 1`; unbekannte Methoden liefern null („Dienst veraltet“).
 */
class MonitorProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        MonitorRuntime.init(ctx)
        val rt = MonitorRuntime
        return when (method) {
            "getState" -> stateBundle()
            "snapshot" -> rt.snapshot(ctx).toBundle()
            "putSettings" -> {
                val e = extras ?: Bundle()
                val old = rt.settings
                val s = Settings(
                    targetPct = e.getInt("targetPct", old.targetPct).coerceIn(50, 100),
                    alarm = e.getBoolean("alarm", old.alarm),
                    mode = e.getInt("mode", old.mode),
                    heatWarnDc = e.getInt("heatWarnDc", old.heatWarnDc).coerceIn(400, 500),
                    summary = e.getBoolean("summary", old.summary),
                    onboarded = e.getBoolean("onboarded", old.onboarded),
                )
                rt.putSettings(s)
                if (s.mode != old.mode) {
                    if (s.mode == Settings.MODE_CHARGING_ONLY) {
                        ChargeJobService.schedule(ctx)
                        val plugged = rt.snapshot(ctx).charging
                        if (!plugged) MonitorService.stop(ctx)
                    } else {
                        ChargeJobService.cancel(ctx)
                    }
                }
                stateBundle()
            }
            "setProfile" -> {
                val e = extras ?: Bundle()
                if (e.getBoolean("auto", false)) rt.setProfile(Profile(source = 0))
                else rt.setProfile(
                    rt.profile.copy(
                        iToMa = e.getDouble("iToMa", rt.profile.iToMa),
                        sign = e.getInt("sign", rt.profile.sign),
                        cells = e.getInt("cells", rt.profile.cells),
                        source = 2,
                    )
                )
                stateBundle()
            }
            "flush" -> { rt.flush(); Bundle().apply { putInt("v", 1); putLong("lastSampleId", rt.store.lastSampleId()) } }
            "clearHistory" -> { rt.clearHistory(); stateBundle() }
            "seedDemo" -> {
                if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
                rt.flush()
                DemoSeeder.seed(rt.store, System.currentTimeMillis())
                rt.reopenSpan()
                stateBundle()
            }
            else -> null
        }
    }

    private fun stateBundle(): Bundle {
        val rt = MonitorRuntime
        val s = rt.settings
        val p = rt.profile
        return Bundle().apply {
            putInt("v", 1)
            putInt("targetPct", s.targetPct); putBoolean("alarm", s.alarm); putInt("mode", s.mode)
            putInt("heatWarnDc", s.heatWarnDc); putBoolean("summary", s.summary); putBoolean("onboarded", s.onboarded)
            putString("dbPath", context!!.getDatabasePath(AndroidSqlDb.DB_NAME).path)
            putDouble("iToMa", p.iToMa); putInt("sign", p.sign); putInt("cells", p.cells)
            putInt("profileSource", p.source); putInt("profileRev", p.rev)
            putLong("lastSampleId", rt.store.lastSampleId())
            putBoolean("serviceRunning", rt.service != null)
            putLong("cpuMs", rt.cpuMs()); putLong("flushes", rt.flushes); putLong("notifUpdates", rt.notifUpdates())
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
