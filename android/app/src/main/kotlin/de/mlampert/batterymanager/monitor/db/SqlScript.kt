package de.mlampert.batterymanager.monitor.db

/**
 * Zerlegt ein SQL-Skript in einzelne Anweisungen.
 * Android-`execSQL` führt nur die erste Anweisung aus, deshalb wird vorher getrennt.
 * Entfernt `--`-Kommentare und trennt an `;` außerhalb von '…' und "…".
 * (Das Schema nutzt keine Trigger, daher sind `;` innerhalb von BEGIN…END nicht zu beachten.)
 */
object SqlScript {

    fun split(script: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < script.length) {
            val ch = script[i]
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) {
                    // verdoppeltes Anführungszeichen = Escape
                    if (i + 1 < script.length && script[i + 1] == quote) {
                        cur.append(script[i + 1]); i += 2; continue
                    }
                    quote = null
                }
                i++
                continue
            }
            when {
                ch == '-' && i + 1 < script.length && script[i + 1] == '-' -> {
                    // Kommentar bis Zeilenende überspringen
                    while (i < script.length && script[i] != '\n') i++
                }
                ch == '\'' || ch == '"' -> { quote = ch; cur.append(ch); i++ }
                ch == ';' -> { flush(cur, out); i++ }
                else -> { cur.append(ch); i++ }
            }
        }
        flush(cur, out)
        return out
    }

    private fun flush(cur: StringBuilder, out: MutableList<String>) {
        val s = cur.toString().trim()
        if (s.isNotEmpty()) out += s
        cur.setLength(0)
    }
}
