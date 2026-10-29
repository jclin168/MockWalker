package com.example.mockwalker

import android.content.Context
import androidx.core.content.edit

/** The last-used walking and shape parameters, so a relaunch starts where the user left off. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("mockwalker_settings", Context.MODE_PRIVATE)

    // ----- main panel -----

    var speedKph by FloatPref("speed_kph", 15f)
    var radiusM by FloatPref("radius_m", 30f)
    var speedRandom by BoolPref("speed_random", true)
    var gpsDrift by BoolPref("gps_drift", true)

    // ----- shapes & text dialog -----

    var shape by StringPref("shape", "")
    var shapeRadiusM by StringPref("shape_radius_m", "50")
    var letterHeightM by StringPref("letter_height_m", "40")
    var rotationDeg by StringPref("rotation_deg", "0")
    var text by StringPref("text", "")
    var pitchM by StringPref("pitch_m", "") // blank = ShapeGen default

    private inner class FloatPref(val key: String, val def: Float) {
        operator fun getValue(thisRef: Any?, p: Any?): Float = sp.getFloat(key, def)
        operator fun setValue(thisRef: Any?, p: Any?, v: Float) = sp.edit { putFloat(key, v) }
    }

    private inner class BoolPref(val key: String, val def: Boolean) {
        operator fun getValue(thisRef: Any?, p: Any?): Boolean = sp.getBoolean(key, def)
        operator fun setValue(thisRef: Any?, p: Any?, v: Boolean) = sp.edit { putBoolean(key, v) }
    }

    private inner class StringPref(val key: String, val def: String) {
        operator fun getValue(thisRef: Any?, p: Any?): String = sp.getString(key, def) ?: def
        operator fun setValue(thisRef: Any?, p: Any?, v: String) = sp.edit { putString(key, v) }
    }
}
