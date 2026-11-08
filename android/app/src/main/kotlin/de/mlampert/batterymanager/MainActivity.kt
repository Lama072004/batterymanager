package de.mlampert.batterymanager

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import de.mlampert.batterymanager.monitor.MonitorService
import de.mlampert.batterymanager.monitor.NotificationChannels
import de.mlampert.batterymanager.spike.SpikeControl
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * UI-Prozess. Brücke Flutter ↔ Dienst: MethodChannel → ContentProvider.call() im Prozess :monitor.
 * Die Activity schreibt nie in die Datenbank.
 */
class MainActivity : FlutterActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingPermission: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensure(this)
        SpikeControl.handle(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SpikeControl.handle(this, intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "call" -> {
                    val method = call.argument<String>("method") ?: return@setMethodCallHandler result.error("arg", "method fehlt", null)
                    val args = call.argument<Map<String, Any?>>("args") ?: emptyMap()
                    io.execute {
                        val out = try {
                            contentResolver.call(Uri.parse("content://$packageName.monitor"), method, null, toBundle(args))
                        } catch (e: Exception) {
                            main.post { result.error("provider", e.message, null) }; return@execute
                        }
                        main.post { result.success(out?.let(::toMap)) }
                    }
                }
                "startService" -> result.success(MonitorService.start(this))
                "systemStatus" -> result.success(systemStatus())
                "requestNotificationPermission" -> {
                    if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        result.success(true)
                    } else {
                        pendingPermission = result
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                    }
                }
                "requestIgnoreBatteryOptimizations" -> {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    }
                    result.success(null)
                }
                "openNotificationSettings" -> {
                    val i = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    runCatching { startActivity(i) }
                    result.success(null)
                }
                "openBatterySettings" -> {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            pendingPermission?.success(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            pendingPermission = null
        }
    }

    private fun systemStatus(): Map<String, Any?> {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return mapOf(
            "notificationsEnabled" to nm.areNotificationsEnabled(),
            "ignoringBatteryOptimizations" to pm.isIgnoringBatteryOptimizations(packageName),
            "sdk" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
        )
    }

    private fun toBundle(m: Map<String, Any?>): Bundle = Bundle().apply {
        for ((k, v) in m) when (v) {
            is Int -> putInt(k, v)
            is Long -> putLong(k, v)
            is Double -> putDouble(k, v)
            is Boolean -> putBoolean(k, v)
            is String -> putString(k, v)
        }
    }

    @Suppress("DEPRECATION")
    private fun toMap(b: Bundle): Map<String, Any?> = b.keySet().associateWith { b.get(it) }

    companion object {
        const val CHANNEL = "batterymanager/monitor"
        const val REQ_NOTIF = 42
    }
}
