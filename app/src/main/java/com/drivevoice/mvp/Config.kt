package com.drivevoice.mvp

/** Replace public endpoints with your own production providers before launch. */
object Config {
    const val TILE_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val GEOCODER_BASE = "https://nominatim.openstreetmap.org/search"
    const val ROUTER_BASE = "https://router.project-osrm.org/route/v1/driving"
    /** Optional live-traffic endpoint. Leave blank for provider-free mode. */
    const val TRAFFIC_ENDPOINT = ""
    const val TRAFFIC_API_KEY = ""
}
