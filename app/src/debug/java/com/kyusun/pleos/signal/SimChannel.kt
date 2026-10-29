package com.kyusun.pleos.signal

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Port of tools/carla/bridge.py's simulator channel, reached through `adb reverse tcp:7777 tcp:7777` (run.sh). */
private const val SIM_PORT = 7777
private const val CONNECT_TIMEOUT_MS = 1_000
private const val RETRY_MS = 1_000L
private const val TAG = "SimChannel"

/**
 * ③ simulator channel, debug builds only (PLAN 9절): connects to the CARLA bridge and hands each line it sends
 * (a recording line without the time, see [simSample]) to [onLine], on this channel's own thread. Reconnects every
 * [RETRY_MS] while the bridge is not running, so the app can start first. With no bridge it stays silent.
 */
internal fun openSimChannel(onLine: (String) -> Unit): AutoCloseable? = SimChannel(onLine).apply { thread.start() }

private class SimChannel(private val onLine: (String) -> Unit) : AutoCloseable {
    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    val thread = Thread(::run, "sim-channel")

    private fun run() {
        while (!closed) {
            try {
                Socket().use { s ->
                    socket = s
                    if (closed) return@use // close() ran before the socket was published
                    s.connect(InetSocketAddress("127.0.0.1", SIM_PORT), CONNECT_TIMEOUT_MS)
                    Log.i(TAG, "connected to the simulator")
                    s.getInputStream().bufferedReader().forEachLine { if (!closed) onLine(it) }
                }
            } catch (e: IOException) {
                if (!closed) Log.d(TAG, "no simulator: ${e.message}")
            }
            if (!closed) {
                try {
                    Thread.sleep(RETRY_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() } // unblocks connect / readLine
        thread.interrupt()
        thread.join()
    }
}
