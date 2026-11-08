package de.mlampert.batterymanager.testutil

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lädt und validiert Golden-Fixtures aus `spec/engine/fixtures` (Format: spec/engine/spec.md §10). */
object Fixtures {

    data class Profile(
        val iToMa: Double, val vToMv: Double, val pToMw: Double,
        val sign: Int, val cells: Int, val pSrc: Int,
    )

    data class Sample(
        val t: Long, val utc: Long, val boot: Int, val tz: Int, val span: Int, val mode: Int,
        val levelPm: Int, val status: Int, val plug: Int, val screen: Int?,
        val currentRaw: Long?, val voltageRaw: Long?, val powerRaw: Long?, val counterRaw: Long?,
        val tempDc: Int?, val q: Int,
    )

    data class Normalized(val i: Int, val currentMa: Long?, val voltageMv: Long?, val powerMw: Long?, val tempDc: Long?)

    data class Event(val at: Int, val type: String)

    data class Fixture(
        val file: File, val id: String, val edgeCase: String?, val title: String,
        val profile: Profile, val samples: List<Sample>,
        val normalized: List<Normalized>, val events: List<Event>,
    )

    val EVENT_TYPES = setOf(
        "SESSION_START", "SESSION_RESUME", "SESSION_END", "PAUSE_START", "PAUSE_END",
        "DEFICIT_START", "DEFICIT_END", "TARGET_REACHED", "TARGET_ALREADY_REACHED",
        "TARGET_EXCEEDED_DURING_OUTAGE", "SEGMENT_BREAK", "CLOCK_JUMP", "HEAT_WARN", "HEAT_CLEAR",
    )

    private val SAMPLE_KEYS = setOf(
        "t", "utc", "boot", "tz", "span", "mode", "level_pm", "status", "plug", "screen",
        "current_raw", "voltage_raw", "power_raw", "counter_raw", "temp_dc", "q",
    )

    fun all(): List<Fixture> =
        Spec.fixturesDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.map(::load)

    /** Parst und validiert eine Fixture. Wirft [IllegalArgumentException] bei Formatfehlern. */
    fun load(file: File): Fixture {
        val o = JSONObject(file.readText(Charsets.UTF_8))
        fun req(cond: Boolean, msg: String) = require(cond) { "${file.name}: $msg" }

        for (k in listOf("id", "title", "profile", "settings", "samples", "expect")) req(o.has(k), "Feld '$k' fehlt")
        val id = o.getString("id")
        req(file.name == "$id.json", "Dateiname muss '$id.json' sein")

        val p = o.getJSONObject("profile")
        for (k in listOf("i_to_ma", "v_to_mv", "p_to_mw", "sign", "cells", "p_src")) req(p.has(k), "profile.$k fehlt")
        val profile = Profile(
            p.getDouble("i_to_ma"), p.getDouble("v_to_mv"), p.getDouble("p_to_mw"),
            p.getInt("sign"), p.getInt("cells"), p.getInt("p_src"),
        )
        req(profile.sign == 1 || profile.sign == -1, "profile.sign muss ±1 sein")
        req(profile.cells in 1..2, "profile.cells muss 1 oder 2 sein")

        val sArr = o.getJSONArray("samples")
        req(sArr.length() > 0, "samples leer")
        val samples = (0 until sArr.length()).map { i ->
            val s = sArr.getJSONObject(i)
            val missing = SAMPLE_KEYS - s.keySet()
            req(missing.isEmpty(), "samples[$i]: fehlende Felder $missing")
            Sample(
                s.getLong("t"), s.getLong("utc"), s.getInt("boot"), s.getInt("tz"), s.getInt("span"), s.getInt("mode"),
                s.getInt("level_pm"), s.getInt("status"), s.getInt("plug"), s.optIntOrNull("screen"),
                s.optLongOrNull("current_raw"), s.optLongOrNull("voltage_raw"), s.optLongOrNull("power_raw"),
                s.optLongOrNull("counter_raw"), s.optIntOrNull("temp_dc"), s.getInt("q"),
            ).also { req(it.levelPm in 0..1000, "samples[$i].level_pm außerhalb 0..1000") }
        }
        req(samples.zipWithNext().all { (a, b) -> b.t >= a.t || b.boot != a.boot }, "elapsed 't' muss je Boot monoton steigen")

        val e = o.getJSONObject("expect")
        for (k in listOf("normalized", "events", "eta", "profileAfter")) req(e.has(k), "expect.$k fehlt")
        val nArr = e.getJSONArray("normalized")
        req(nArr.length() == samples.size, "expect.normalized braucht genau einen Eintrag je Sample")
        val normalized = (0 until nArr.length()).map { i ->
            val n = nArr.getJSONObject(i)
            req(n.getInt("i") == i, "expect.normalized[$i].i muss $i sein")
            Normalized(i, n.optLongOrNull("current_ma"), n.optLongOrNull("voltage_mv"),
                n.optLongOrNull("power_mw"), n.optLongOrNull("temp_dc"))
        }
        val events = e.getJSONArray("events").objects().mapIndexed { i, ev ->
            val type = ev.getString("type")
            req(type in EVENT_TYPES, "expect.events[$i]: unbekannter Typ '$type'")
            val at = ev.getInt("at")
            req(at in samples.indices, "expect.events[$i].at außerhalb der Samples")
            Event(at, type)
        }
        return Fixture(file, id, o.optStringOrNull("edgeCase"), o.getString("title"), profile, samples, normalized, events)
    }

    private fun JSONObject.optLongOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else getLong(k)
    private fun JSONObject.optIntOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else getInt(k)
    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
