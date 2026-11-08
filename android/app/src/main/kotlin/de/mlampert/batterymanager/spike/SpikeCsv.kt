package de.mlampert.batterymanager.spike

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * CSV-Ausgabe des Mess-Spikes unter Android/data/<pkg>/files/spike/ (per `adb pull` abholbar).
 * Puffert Zeilen und schreibt nur bei [flush] auf den Flash (Tuner: Batching).
 */
class SpikeCsv(context: Context, name: String, private val header: List<String>) {

    val file: File = File(dir(context), name)
    private var writer: BufferedWriter? = null
    var rows: Long = 0
        private set
    var flushes: Long = 0
        private set

    private fun open(): BufferedWriter {
        writer?.let { return it }
        val isNew = !file.exists() || file.length() == 0L
        val w = BufferedWriter(FileWriter(file, true), 16 * 1024)
        if (isNew) {
            w.write(header.joinToString(","))
            w.newLine()
        }
        writer = w
        return w
    }

    fun append(values: List<Any?>) {
        require(values.size == header.size) { "CSV ${file.name}: ${values.size} Werte für ${header.size} Spalten" }
        val w = open()
        w.write(values.joinToString(",") { it?.toString() ?: "" })
        w.newLine()
        rows++
    }

    fun flush() {
        writer?.flush()
        flushes++
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    companion object {
        fun dir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "spike").apply { mkdirs() }

        /** Flag-Dateien funktionieren prozessübergreifend (UI-Prozess ↔ :monitor). */
        fun flag(context: Context, name: String): File = File(context.filesDir, "spike_$name.flag")
    }
}
