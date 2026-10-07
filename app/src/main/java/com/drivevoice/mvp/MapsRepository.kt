package com.drivevoice.mvp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MapsRepository {
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    data class Place(val name: String, val lat: Double, val lon: Double, val display: String)
    data class RouteStep(
        val distanceM: Double,
        val durationS: Double,
        val instruction: String,
        val lat: Double,
        val lon: Double,
        val maneuverType: String = "",
        val modifier: String = "",
        val roadName: String = ""
    )
    data class Route(
        val points: List<Pair<Double, Double>>,
        val distanceM: Double,
        val durationS: Double,
        val steps: List<RouteStep> = emptyList()
    )

    fun search(query: String, lat: Double?, lon: Double?, callback: (List<Place>) -> Unit) {
        Thread {
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val viewbox = if (lat != null && lon != null) "&lat=$lat&lon=$lon" else ""
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&accept-language=ar,en&q=$q$viewbox"
                val request = Request.Builder().url(url).header("User-Agent", "DriveVoice/1.3").build()
                val body = client.newCall(request).execute().body?.string().orEmpty()
                val arr = JSONArray(body)
                val out = mutableListOf<Place>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") }, o.getDouble("lat"), o.getDouble("lon"), o.optString("display_name"))
                }
                callback(out)
            } catch (_: Exception) { callback(emptyList()) }
        }.start()
    }

    /** Reverse geocode to a short Arabic place name; null on any failure. */
    fun reverse(lat: Double, lon: Double, callback: (String?) -> Unit) {
        Thread {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=ar,en&lat=$lat&lon=$lon"
                val request = Request.Builder().url(url).header("User-Agent", "DriveVoice/1.3").build()
                val body = client.newCall(request).execute().body?.string().orEmpty()
                val o = JSONObject(body)
                val a = o.optJSONObject("address")
                val road = a?.optString("road").orEmpty()
                val area = a?.optString("suburb").orEmpty().ifBlank { a?.optString("city").orEmpty() }
                callback(listOf(road, area).filter { it.isNotBlank() }.joinToString("، ").ifBlank { o.optString("display_name").substringBefore(",") }.ifBlank { null })
            } catch (_: Exception) { callback(null) }
        }.start()
    }

    fun searchNearby(query: String, lat: Double, lon: Double, callback: (List<Place>) -> Unit) {
        Thread {
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = "${Config.GEOCODER_BASE}?format=jsonv2&limit=8&accept-language=ar,en&q=$q&lat=$lat&lon=$lon&bounded=1"
                val request = Request.Builder().url(url).header("User-Agent", "DriveVoice/0.7").build()
                val body = client.newCall(request).execute().body?.string().orEmpty()
                val arr = JSONArray(body)
                val out = mutableListOf<Place>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") }, o.getDouble("lat"), o.getDouble("lon"), o.optString("display_name"))
                }
                callback(out)
            } catch (_: Exception) { callback(emptyList()) }
        }.start()
    }

    fun routeViaStops(fromLat: Double, fromLon: Double, stops: List<Pair<Double, Double>>, toLat: Double, toLon: Double, callback: (Route?) -> Unit) {
        Thread {
            try {
                val coords = buildList {
                    add(fromLon to fromLat)
                    stops.forEach { add(it.second to it.first) }
                    add(toLon to toLat)
                }.joinToString(";") { "${it.first},${it.second}" }
                val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true"
                val root = getJson(url, "DriveVoiceMVP/0.8") ?: return@Thread callback(null)
                callback(parseRoute(root, toLat, toLon))
            } catch (_: Exception) { callback(null) }
        }.start()
    }

    fun routeViaStop(fromLat: Double, fromLon: Double, stopLat: Double, stopLon: Double, toLat: Double, toLon: Double, callback: (Route?) -> Unit) {
        Thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$stopLon,$stopLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
                val root = getJson(url, "DriveVoiceMVP/0.6") ?: return@Thread callback(null)
                callback(parseRoute(root, toLat, toLon))
            } catch (_: Exception) { callback(null) }
        }.start()
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, callback: (Route?) -> Unit) {
        Thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
                val root = getJson(url, "DriveVoiceMVP/0.6") ?: return@Thread callback(null)
                callback(parseRoute(root, toLat, toLon))
            } catch (_: Exception) { callback(null) }
        }.start()
    }

    /** Requests alternatives when the routing provider supports them. We never label an
     * alternative as traffic-aware unless the provider actually returns a different route. */
    fun routeAlternative(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, callback: (Route?, Boolean) -> Unit) {
        Thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true&alternatives=true"
                val root = getJson(url, "DriveVoiceMVP/0.6") ?: return@Thread callback(null, false)
                val routes = root.optJSONArray("routes") ?: return@Thread callback(null, false)
                if (routes.length() == 0) return@Thread callback(null, false)
                val chosen = if (routes.length() > 1) routes.getJSONObject(1) else routes.getJSONObject(0)
                callback(parseSingleRoute(chosen, toLat, toLon), routes.length() > 1)
            } catch (_: Exception) { callback(null, false) }
        }.start()
    }

    private fun getJson(url: String, userAgent: String): JSONObject? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val body = client.newCall(request).execute().body?.string().orEmpty()
        val root = JSONObject(body)
        if (root.optString("code") != "Ok") return null
        return root
    }

    private fun parseRoute(root: JSONObject, toLat: Double, toLon: Double): Route? {
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        return parseSingleRoute(routes.getJSONObject(0), toLat, toLon)
    }

    private fun parseSingleRoute(r: JSONObject, toLat: Double, toLon: Double): Route {
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = buildList {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                add(c.getDouble(1) to c.getDouble(0))
            }
        }
        val steps = mutableListOf<RouteStep>()
        val legs = r.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until legSteps.length()) {
                    val st = legSteps.getJSONObject(si)
                    val maneuver = st.optJSONObject("maneuver")
                    val loc = maneuver?.optJSONArray("location")
                    val lon = loc?.optDouble(0, toLon) ?: toLon
                    val lat = loc?.optDouble(1, toLat) ?: toLat
                    val type = maneuver?.optString("type").orEmpty()
                    val modifier = maneuver?.optString("modifier").orEmpty()
                    val name = st.optString("name").trim()
                    val instruction = instruction(type, modifier, name)
                    if (instruction.isNotBlank()) {
                        steps += RouteStep(st.optDouble("distance", 0.0), st.optDouble("duration", 0.0), instruction, lat, lon, type, modifier, name)
                    }
                }
            }
        }
        return Route(points, r.optDouble("distance", 0.0), r.optDouble("duration", 0.0), steps)
    }

    private fun instruction(type: String, modifier: String, name: String): String {
        val road = if (name.isNotBlank()) " في $name" else ""
        return when (type) {
            "depart" -> "ابدأ السير$road"
            "arrive" -> "أنت قريب من الوصول"
            "turn" -> when (modifier) {
                "left" -> "انعطف يسارًا$road"
                "right" -> "انعطف يمينًا$road"
                "slight left" -> "خذ اليسار الخفيف$road"
                "slight right" -> "خذ اليمين الخفيف$road"
                "sharp left" -> "انعطف يسارًا حادًا$road"
                "sharp right" -> "انعطف يمينًا حادًا$road"
                "uturn" -> "خذ الدوران للخلف$road"
                else -> "استمر$road"
            }
            "roundabout", "rotary" -> "ادخل الدوار$road"
            "merge" -> "اندمج مع الطريق$road"
            "fork" -> if (modifier.contains("left")) "خذ الفرع الأيسر$road" else "خذ الفرع الأيمن$road"
            "on ramp" -> "ادخل الطريق$road"
            "off ramp" -> "اخرج من الطريق$road"
            else -> if (name.isNotBlank()) "استمر في $name" else "استمر للأمام"
        }
    }
}
