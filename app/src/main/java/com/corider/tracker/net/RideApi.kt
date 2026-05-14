package com.corider.tracker.net

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class RideApi(baseUrl: String) {
    private val root = baseUrl.trim().trimEnd('/')

    fun publishLocation(rideId: String, payload: CompactLocationPayload) {
        postJson("rides/${encode(rideId)}/location", payload.toJson())
    }

    fun leaveRide(rideId: String, riderId: String) {
        val json = JSONObject()
            .put(CompactLocationPayload.KEY_TYPE, CompactLocationPayload.TYPE_LEFT)
            .put("u", riderId)
            .toString()
        postJson("rides/${encode(rideId)}/leave", json)
    }

    fun streamRide(
        rideId: String,
        riderId: String,
        onLocation: (CompactLocationPayload) -> Unit,
        onLeft: (String) -> Unit,
        shouldContinue: () -> Boolean
    ) {
        val url = URL("$root/rides/${encode(rideId)}/events?riderId=${encode(riderId)}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(httpErrorMessage(code, url))
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                val data = StringBuilder()
                while (shouldContinue()) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            handleEvent(data.toString(), onLocation, onLeft)
                            data.clear()
                        }
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(path: String, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val url = URL("$root/$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
        }

        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(httpErrorMessage(code, url))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun handleEvent(
        json: String,
        onLocation: (CompactLocationPayload) -> Unit,
        onLeft: (String) -> Unit
    ) {
        if (json.isBlank()) return
        when (CompactLocationPayload.eventType(json)) {
            CompactLocationPayload.TYPE_LOCATION -> onLocation(CompactLocationPayload.fromJson(json))
            CompactLocationPayload.TYPE_LEFT -> onLeft(CompactLocationPayload.leftRiderId(json))
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun httpErrorMessage(code: Int, url: URL): String {
        return if (code == 404) {
            "relay HTTP 404 at ${url.path}; check that Relay URL is the deployed CoRider base URL"
        } else {
            "relay returned HTTP $code"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 35_000
    }
}
