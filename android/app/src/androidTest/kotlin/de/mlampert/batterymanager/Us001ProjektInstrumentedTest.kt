// US-001: Projekt-Identität & Grundgerüst (Akzeptanzvertrag AC-004) – läuft auf Gerät/Emulator:
//   cd android && ./gradlew :app:connectedDebugAndroidTest
package de.mlampert.batterymanager

import android.app.NotificationManager
import android.media.AudioAttributes
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.mlampert.batterymanager.monitor.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Us001ProjektInstrumentedTest {

    // AC-004: Die fünf Kanäle existieren mit den festgelegten Importance-Stufen und deutschen Namen.
    @Test
    fun ac004() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("de.mlampert.batterymanager", ctx.packageName)
        NotificationChannels.ensure(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val expected = mapOf(
            NotificationChannels.MONITOR to (NotificationManager.IMPORTANCE_MIN to "Hintergrunddienst"),
            NotificationChannels.CHARGING to (NotificationManager.IMPORTANCE_HIGH to "Laden"),
            NotificationChannels.TARGET_ALARM to (NotificationManager.IMPORTANCE_HIGH to "Ladeziel-Alarm"),
            NotificationChannels.HEAT to (NotificationManager.IMPORTANCE_HIGH to "Hitzewarnung"),
            NotificationChannels.STATUS to (NotificationManager.IMPORTANCE_DEFAULT to "Status"),
        )
        for ((id, want) in expected) {
            val ch = nm.getNotificationChannel(id)
            assertNotNull("Kanal $id fehlt", ch)
            assertEquals("Importance $id", want.first, ch.importance)
            assertEquals("Name $id", want.second, ch.name.toString())
        }
        assertNull("charging muss ohne Ton sein", nm.getNotificationChannel(NotificationChannels.CHARGING).sound)
        val alarm = nm.getNotificationChannel(NotificationChannels.TARGET_ALARM)
        assertEquals(AudioAttributes.USAGE_ALARM, alarm.audioAttributes.usage)
        assertEquals(true, alarm.shouldVibrate())
    }
}
