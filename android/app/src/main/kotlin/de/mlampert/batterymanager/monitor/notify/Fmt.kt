package de.mlampert.batterymanager.monitor.notify

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Deutsche Formatierung mit schmalem geschütztem Leerzeichen (U+202F) vor Einheiten. */
object Fmt {
    const val NNBSP = ' '

    fun watts(mw: Long): String {
        val w = mw / 1000.0
        return if (abs(w) >= 10) "${w.roundToLong()}${NNBSP}W" else "${"%.1f".format(Locale.GERMANY, w)}${NNBSP}W"
    }

    fun pct(p: Int): String = "$p${NNBSP}%"

    fun tempC(dc: Int): String = "${(dc / 10.0).roundToLong()}${NNBSP}°C"

    /** Gerundete Dauer: 5-min-Stufen (ok) bzw. 15-min-Stufen (approx), mit „ca.“. */
    fun duration(seconds: Long, step: Int): String {
        val min = ((seconds / 60.0) / step).roundToLong() * step
        val m = if (min < step) step.toLong() else min
        return if (m < 60) "$m${NNBSP}Min." else {
            val h = m / 60; val r = m % 60
            if (r == 0L) "$h${NNBSP}Std." else "$h${NNBSP}Std. $r${NNBSP}Min."
        }
    }

    fun clock(utc: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(utc))
}
