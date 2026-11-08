// US-003: Engine-Spezifikation & Golden-Fixtures (Akzeptanzvertrag AC-012, AC-013)
package de.mlampert.batterymanager

import de.mlampert.batterymanager.testutil.Fixtures
import de.mlampert.batterymanager.testutil.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Us003EngineSpecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // AC-012: Für jeden der 10 Top-Edge-Cases gibt es mindestens eine Fixture.
    @Test
    fun ac012() {
        val covered = Fixtures.all().mapNotNull { it.edgeCase }.toSet()
        val required = (1..10).map { "EC%02d".format(it) }.toSet()
        assertEquals("fehlende Edge-Cases: ${required - covered}", emptySet<String>(), required - covered)
        assertTrue("spec.md fehlt", File(Spec.root, "engine/spec.md").isFile)
    }

    // AC-013: Der Loader liest und validiert das Fixture-Format; ungültige Fixtures scheitern.
    @Test
    fun ac013() {
        val all = Fixtures.all()
        assertTrue(all.size >= 10)
        all.forEach { fx -> assertEquals(fx.samples.size, fx.normalized.size) }

        val valid = File(Spec.fixturesDir, "ec06_target_flutter.json").readText()
        fun rejects(name: String, json: String) {
            val f = File(tmp.root, "$name.json").apply { writeText(json) }
            try {
                Fixtures.load(f)
                fail("$name hätte abgelehnt werden müssen")
            } catch (expected: IllegalArgumentException) {
                // ok
            } catch (expected: org.json.JSONException) {
                // ok
            }
        }
        rejects("ec06_target_flutter", valid.replace("\"TARGET_REACHED\"", "\"TARGET_HIT\""))
        rejects("ec06_target_flutter", valid.replace("\"level_pm\": 780", "\"level_pm\": 1780"))
        rejects("ec06_target_flutter", valid.replace("\"profile\":", "\"profil\":"))
        rejects("falscher_name", valid)
    }
}
