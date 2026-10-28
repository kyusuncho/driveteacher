package com.kyusun.pleos.probe

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives the Pleos Virtual Console from the device through its HTTP API on port 8088 (undocumented;
 * endpoints and params come from GET /vehicle/catalog). Writes are accepted only while some client
 * heartbeats, so [start] posts a browser session every second. Needs INTERNET + cleartext (debug manifest).
 */
class ConsoleClient(private val baseUrl: String = "http://127.0.0.1:8088") : AutoCloseable {
    /** One accepted write: when the successful attempt was sent and answered, and how many attempts it took. */
    data class Put(val request: String, val sendNs: Long, val ackNs: Long, val attempts: Int)

    private data class Response(val code: Int, val body: String)

    private val heartbeat = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        heartbeat.scheduleAtFixedRate(::beat, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    /** Blocks until the console reports emulator_active (i.e. it sees our heartbeat). */
    fun waitActive(timeoutMs: Long = ACTIVE_TIMEOUT_MS) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = try {
                state().getJSONObject("controls").getBoolean("emulator_active")
            } catch (e: Exception) {
                Log.w(TAG, "state poll failed: $e")
                false
            }
            if (active) return
            Thread.sleep(RETRY_MS)
        }
        error("Virtual Console not active after $timeoutMs ms (is the console reachable on $baseUrl?)")
    }

    fun state(): JSONObject = JSONObject(request("GET", "/vehicle/state").body)

    /** PUT [path] with query [params]; retries 503 (no heartbeat yet) and timeouts, which are transient here. */
    fun put(path: String, params: Map<String, Any>): Put {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v.toString(), "UTF-8")}" }
        val pathAndQuery = "$path?$query"
        for (attempt in 1..MAX_ATTEMPTS) {
            val sendNs = SystemClock.elapsedRealtimeNanos()
            val response = try {
                request("PUT", pathAndQuery)
            } catch (e: IOException) {
                Log.w(TAG, "PUT $pathAndQuery attempt $attempt: $e")
                null
            }
            when {
                response != null && response.code in 200..299 ->
                    return Put(pathAndQuery, sendNs, SystemClock.elapsedRealtimeNanos(), attempt)
                response != null && response.code != 503 ->
                    error("PUT $pathAndQuery -> ${response.code} ${response.body}")
                else -> Thread.sleep(RETRY_MS)
            }
        }
        error("PUT $pathAndQuery failed $MAX_ATTEMPTS times (console inactive or stalled)")
    }

    override fun close() {
        heartbeat.shutdownNow()
    }

    private fun beat() {
        try {
            request("POST", "/browser/session?client=$CLIENT_ID")
        } catch (e: Exception) { // must not escape: an exception cancels the scheduled heartbeat
            Log.w(TAG, "heartbeat failed: $e")
        }
    }

    private fun request(method: String, pathAndQuery: String): Response {
        val conn = URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        val code = conn.responseCode
        val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }
        return Response(code, body.orEmpty())
    }

    private companion object {
        const val TAG = "ConsoleClient"
        const val CLIENT_ID = "pleos-signal-probe"
        const val HEARTBEAT_MS = 1_000L
        const val ACTIVE_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MS = 3_000
        const val RETRY_MS = 300L
        const val MAX_ATTEMPTS = 15
    }
}
