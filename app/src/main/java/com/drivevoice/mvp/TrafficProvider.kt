package com.drivevoice.mvp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional live-traffic adapter. The app remains functional without it and falls back to routing ETA.
 * Configure Config.TRAFFIC_ENDPOINT + Config.TRAFFIC_API_KEY in a private build/environment.
 * Expected response: {"delaySeconds":123,"speedFactor":0.82,"source":"provider"}
 */
class TrafficProvider {
    data class Snapshot(val delaySeconds: Long, val speedFactor: Double, val source: String)
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    fun fetch(lat: Double, lon: Double, destinationLat: Double, destinationLon: Double, callback: (Snapshot?) -> Unit) {
        if (Config.TRAFFIC_ENDPOINT.isBlank()) return callback(null)
        Thread {
            try {
                val url = Config.TRAFFIC_ENDPOINT
                    .replace("{lat}", lat.toString())
                    .replace("{lon}", lon.toString())
                    .replace("{destinationLat}", destinationLat.toString())
                    .replace("{destinationLon}", destinationLon.toString())
                val builder = Request.Builder().url(url).header("User-Agent", "DriveVoice/0.7")
                if (Config.TRAFFIC_API_KEY.isNotBlank()) builder.header("Authorization", "Bearer ${Config.TRAFFIC_API_KEY}")
                val body = client.newCall(builder.build()).execute().body?.string().orEmpty()
                val o = JSONObject(body)
                callback(Snapshot(
                    o.optLong("delaySeconds", 0L).coerceAtLeast(0L),
                    o.optDouble("speedFactor", 1.0).coerceIn(0.2, 1.5),
                    o.optString("source", "traffic")
                ))
            } catch (_: Exception) { callback(null) }
        }.start()
    }
}
