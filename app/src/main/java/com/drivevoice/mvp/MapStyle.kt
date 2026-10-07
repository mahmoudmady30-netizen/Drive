package com.drivevoice.mvp

object MapStyle {
    const val JSON = """
    {"version":8,"name":"DriveVoice Dark","sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#08131F"}},{"id":"osm","type":"raster","source":"osm","paint":{"raster-opacity":0.92,"raster-saturation":-0.55,"raster-contrast":0.15}}]}
    """
}
