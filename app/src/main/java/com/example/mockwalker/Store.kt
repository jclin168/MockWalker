package com.example.mockwalker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

data class SavedPlace(val name: String, val lat: Double, val lon: Double, val radiusM: Int)
data class SavedRoute(
    val name: String,
    val points: List<GeoPoint>,
    val endMode: EndMode,
    val breaks: Set<Int> = emptySet() // pen-up jumps, see RoutePath
)

/** Persists favorite places and routes as JSON in SharedPreferences. */
class Store(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("mockwalker_store", Context.MODE_PRIVATE)

    // ----- places -----

    fun places(): MutableList<SavedPlace> {
        val out = mutableListOf<SavedPlace>()
        try {
            val arr = JSONArray(sp.getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SavedPlace(
                        o.getString("name"), o.getDouble("lat"), o.getDouble("lon"),
                        o.optInt("radius", 30)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun savePlaces(list: List<SavedPlace>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("lat", it.lat)
                    .put("lon", it.lon).put("radius", it.radiusM)
            )
        }
        sp.edit().putString("places", arr.toString()).apply()
    }

    fun upsertPlace(p: SavedPlace) {
        val l = places()
        val idx = l.indexOfFirst { it.name == p.name }
        if (idx >= 0) l[idx] = p else l.add(p)
        savePlaces(l)
    }

    // ----- routes -----

    fun routes(): MutableList<SavedRoute> {
        val out = mutableListOf<SavedRoute>()
        try {
            val arr = JSONArray(sp.getString("routes", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("points")
                val list = List(pts.length()) { k ->
                    val p = pts.getJSONArray(k)
                    GeoPoint(p.getDouble(0), p.getDouble(1))
                }
                val end = EndMode.values().getOrElse(o.optInt("end", 0)) { EndMode.ONCE }
                val br = o.optJSONArray("breaks")
                val breaks = if (br == null) emptySet() else List(br.length()) { br.getInt(it) }.toSet()
                out.add(SavedRoute(o.getString("name"), list, end, breaks))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveRoutes(list: List<SavedRoute>) {
        val arr = JSONArray()
        list.forEach { r ->
            val pts = JSONArray()
            r.points.forEach { pts.put(JSONArray().put(it.latitude).put(it.longitude)) }
            val o = JSONObject().put("name", r.name).put("points", pts).put("end", r.endMode.ordinal)
            if (r.breaks.isNotEmpty()) o.put("breaks", JSONArray(r.breaks.sorted()))
            arr.put(o)
        }
        sp.edit().putString("routes", arr.toString()).apply()
    }

    fun upsertRoute(r: SavedRoute) {
        val l = routes()
        val idx = l.indexOfFirst { it.name == r.name }
        if (idx >= 0) l[idx] = r else l.add(r)
        saveRoutes(l)
    }
}
