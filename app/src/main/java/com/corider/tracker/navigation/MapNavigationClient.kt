package com.corider.tracker.navigation

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class PlaceSearchResult(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
) {
    val label: String
        get() = name.ifBlank { address.ifBlank { "Destination" } }
}

data class RouteResult(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<NavigationPoint>
)

data class NavigationPoint(
    val latitude: Double,
    val longitude: Double
)

class MapNavigationClient {
    fun search(query: String, nearLatitude: Double? = null, nearLongitude: Double? = null): List<PlaceSearchResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val bias = if (nearLatitude != null && nearLongitude != null) {
            val latDelta = 0.7
            val lonDelta = 0.7
            val left = nearLongitude - lonDelta
            val top = nearLatitude + latDelta
            val right = nearLongitude + lonDelta
            val bottom = nearLatitude - latDelta
            "&viewbox=${format(left)},${format(top)},${format(right)},${format(bottom)}&bounded=0"
        } else {
            ""
        }
        val response = get("$NOMINATIM_SEARCH_URL?q=$encoded&format=jsonv2&addressdetails=1&dedupe=1&limit=6$bias")
        val results = JSONArray(response)
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.getJSONObject(index)
                val address = item.optString("display_name", query)
                val name = item.optString("name")
                    .ifBlank { address.substringBefore(",").trim() }
                    .ifBlank { query }
                add(
                    PlaceSearchResult(
                        name = name,
                        address = address,
                        latitude = item.getString("lat").toDouble(),
                        longitude = item.getString("lon").toDouble()
                    )
                )
            }
        }
    }

    fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double
    ): RouteResult? {
        val coordinates = String.format(
            Locale.US,
            "%.6f,%.6f;%.6f,%.6f",
            originLongitude,
            originLatitude,
            destinationLongitude,
            destinationLatitude
        )
        val response = get("$OSRM_ROUTE_URL/$coordinates?overview=full&geometries=geojson")
        val root = JSONObject(response)
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null

        val route = routes.getJSONObject(0)
        val geometry = route.getJSONObject("geometry")
        val routeCoordinates = geometry.getJSONArray("coordinates")
        val points = buildList {
            for (index in 0 until routeCoordinates.length()) {
                val pair = routeCoordinates.getJSONArray(index)
                add(NavigationPoint(latitude = pair.getDouble(1), longitude = pair.getDouble(0)))
            }
        }
        if (points.isEmpty()) return null

        return RouteResult(
            distanceMeters = route.optDouble("distance", 0.0),
            durationSeconds = route.optDouble("duration", 0.0),
            points = points
        )
    }

    private fun get(urlText: String): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "CoRider/0.1 Android")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("Map service returned HTTP $code")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
        private const val OSRM_ROUTE_URL = "https://router.project-osrm.org/route/v1/driving"

        private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
    }
}
