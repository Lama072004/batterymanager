package de.mlampert.batterymanager

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Bewusst KEINE FlutterApplication: Deren onCreate lädt libflutter.so in JEDEM Prozess.
 * Im Hintergrundprozess `:monitor` darf die Flutter-Engine nie geladen werden (Stromverbrauch, RAM).
 * Im UI-Prozess initialisiert die FlutterActivity die Engine selbst.
 */
class BatteryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Absichtlich leer: weder im UI- noch im :monitor-Prozess wird hier etwas initialisiert.
    }

    companion object {
        const val MONITOR_PROCESS_SUFFIX = ":monitor"

        fun currentProcessName(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getProcessName()
            } else {
                runCatching {
                    File("/proc/self/cmdline").readText().trimEnd('\u0000').substringBefore('\u0000')
                }.getOrDefault("")
            }

        fun isMonitorProcess(): Boolean = currentProcessName().endsWith(MONITOR_PROCESS_SUFFIX)
    }
}
