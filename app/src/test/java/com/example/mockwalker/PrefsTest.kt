package com.example.mockwalker

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsTest {

    @Test
    fun freshInstallUsesDefaults() {
        val p = Prefs(MemoryPrefs())
        assertEquals(15f, p.speedKph)
        assertEquals(30f, p.radiusM)
        assertEquals(true, p.speedRandom)
        assertEquals(true, p.gpsDrift)
        assertEquals("", p.shape)
        assertEquals("50", p.shapeRadiusM)
        assertEquals("40", p.letterHeightM)
        assertEquals("0", p.rotationDeg)
        assertEquals("", p.text)
        assertEquals("", p.pitchM)
    }

    @Test
    fun valuesSurviveANewInstance() {
        val sp = MemoryPrefs()
        Prefs(sp).apply {
            speedKph = 22.5f
            radiusM = 250f
            speedRandom = false
            gpsDrift = false
            shape = "TEXT"
            shapeRadiusM = "120"
            letterHeightM = "75"
            rotationDeg = "-30"
            text = "HELLO"
            pitchM = "90"
        }
        val p = Prefs(sp) // as on the next launch
        assertEquals(22.5f, p.speedKph)
        assertEquals(250f, p.radiusM)
        assertFalse(p.speedRandom)
        assertFalse(p.gpsDrift)
        assertEquals("TEXT", p.shape)
        assertEquals("120", p.shapeRadiusM)
        assertEquals("75", p.letterHeightM)
        assertEquals("-30", p.rotationDeg)
        assertEquals("HELLO", p.text)
        assertEquals("90", p.pitchM)
    }

    @Test
    fun snapKeepsValuesAlreadyOnTheGrid() {
        assertEquals(15f, Prefs.snapToSlider(15f, 0.5f, 1f, 30f))
        assertEquals(7.5f, Prefs.snapToSlider(7.5f, 0.5f, 1f, 30f))
        assertEquals(1000f, Prefs.snapToSlider(1000f, 1f, 1f, 1000f))
    }

    @Test
    fun snapRoundsToStepAndClampsToRange() {
        assertEquals(7.5f, Prefs.snapToSlider(7.4f, 0.5f, 1f, 30f))
        assertEquals(42f, Prefs.snapToSlider(41.6f, 1f, 1f, 1000f))
        assertEquals(30f, Prefs.snapToSlider(99f, 0.5f, 1f, 30f))
        assertEquals(1f, Prefs.snapToSlider(0f, 1f, 1f, 1000f))
        assertEquals(1f, Prefs.snapToSlider(Float.NaN, 0.5f, 1f, 30f))
    }

    /** Just enough SharedPreferences for Prefs, kept in a map. */
    private class MemoryPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, def: String?) = map[key] as String? ?: def
        override fun getStringSet(key: String, def: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (map[key] as MutableSet<String>? ?: def)
        override fun getInt(key: String, def: Int) = map[key] as Int? ?: def
        override fun getLong(key: String, def: Long) = map[key] as Long? ?: def
        override fun getFloat(key: String, def: Float) = map[key] as Float? ?: def
        override fun getBoolean(key: String, def: Boolean) = map[key] as Boolean? ?: def
        override fun contains(key: String) = key in map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            var clear = false
            override fun putString(key: String, v: String?) = apply { pending[key] = v }
            override fun putStringSet(key: String, v: MutableSet<String>?) = apply { pending[key] = v }
            override fun putInt(key: String, v: Int) = apply { pending[key] = v }
            override fun putLong(key: String, v: Long) = apply { pending[key] = v }
            override fun putFloat(key: String, v: Float) = apply { pending[key] = v }
            override fun putBoolean(key: String, v: Boolean) = apply { pending[key] = v }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
