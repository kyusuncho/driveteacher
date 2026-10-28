package com.kyusun.pleos.probe

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Timestamped record of what one probe test did (console PUTs = steps) and saw (callbacks/polls = events).
 * All times are SystemClock.elapsedRealtimeNanos, the same clock CarPropertyValue timestamps use.
 */
class SignalLog(private val name: String) {
    /** [halNs]: CarPropertyValue.timestamp for property events, 0 otherwise. */
    data class Event(val tNs: Long, val src: String, val value: String, val halNs: Long = 0)

    private data class Step(val label: String, val put: ConsoleClient.Put)

    private val startNs = SystemClock.elapsedRealtimeNanos()
    private val events = CopyOnWriteArrayList<Event>()
    private val steps = CopyOnWriteArrayList<Step>()
    private val notes = JSONObject()

    fun record(src: String, value: String, halNs: Long = 0) {
        events += Event(SystemClock.elapsedRealtimeNanos(), src, value, halNs)
        Log.v(EVENT_TAG, "$name $src=$value")
    }

    fun step(label: String, put: ConsoleClient.Put) {
        steps += Step(label, put)
        Log.i(TAG, "$name step '$label' ${put.request} attempts=${put.attempts} ackMs=${ms(put.ackNs - put.sendNs)}")
    }

    @Synchronized
    fun note(key: String, value: Any) {
        notes.put(key, value)
        Log.i(TAG, "$name $key=$value")
    }

    fun events(src: String, fromNs: Long = 0, toNs: Long = Long.MAX_VALUE): List<Event> =
        events.filter { it.src == src && it.tNs >= fromNs && it.tNs < toNs }

    /** Writes cache/probe-<name>.json and logs the per-step summary under [TAG]. */
    fun save(dir: File): File {
        val summary = stepSummary()
        for (i in 0 until summary.length()) Log.i(TAG, "$name ${summary.get(i)}")
        val json = JSONObject()
            .put("test", name)
            .put("notes", synchronized(this) { JSONObject(notes.toString()) })
            .put("steps", summary)
            .put("events", JSONArray(events.map { it.toJson() }))
        return File(dir, "probe-$name.json").apply { writeText(json.toString(1)) }
            .also { Log.i(TAG, "$name saved ${it.path}") }
    }

    /**
     * One entry per window [PUT i, PUT i+1) plus the window before the first PUT (initial callbacks).
     * Per source: count, latency of the first event and of the first value change, distinct values in order.
     */
    private fun stepSummary(): JSONArray {
        val bounds = listOf(startNs) + steps.map { it.put.sendNs } + Long.MAX_VALUE
        val labels = listOf("(before first step)" to null) + steps.map { it.label to it.put }
        return JSONArray(labels.mapIndexed { i, (label, put) ->
            val from = bounds[i]
            val sources = JSONObject()
            events.filter { it.tNs >= from && it.tNs < bounds[i + 1] }.groupBy { it.src }.forEach { (src, inWindow) ->
                val previous = events.lastOrNull { it.src == src && it.tNs < from }?.value
                val firstChange = inWindow.firstOrNull { it.value != previous }
                sources.put(src, JSONObject()
                    .put("n", inWindow.size)
                    .put("firstMs", ms(inWindow.first().tNs - from))
                    .put("firstChangeMs", firstChange?.let { ms(it.tNs - from) } ?: JSONObject.NULL)
                    .put("values", JSONArray(inWindow.map { it.value }.distinct().take(MAX_VALUES))))
            }
            JSONObject().put("label", label).put("put", put?.request ?: JSONObject.NULL)
                .put("ackMs", put?.let { ms(it.ackNs - it.sendNs) } ?: JSONObject.NULL)
                .put("sources", sources)
        })
    }

    /** A window where the input does not change: event count, rate, spacing, values. */
    fun holdStats(src: String, fromNs: Long, toNs: Long): JSONObject {
        val inWindow = events(src, fromNs, toNs)
        return JSONObject().put("events", inWindow.size)
            .put("rateHz", inWindow.size * 1e9 / (toNs - fromNs))
            .put("intervalMs", stats(inWindow.zipWithNext { a, b -> ms(b.tNs - a.tNs) }))
            .put("values", JSONArray(inWindow.map { it.value }.distinct().take(MAX_VALUES)))
    }

    /** Speed ramp (one PUT per kph), per source: callback spacing, PUT->first matching callback (|v*3.6-kph|<0.05), HAL timestamp lag. */
    fun speedRampStats(puts: List<Pair<Int, ConsoleClient.Put>>, sources: Set<String>): JSONObject {
        val from = puts.first().second.sendNs
        val to = puts.last().second.sendNs + RAMP_TAIL_NS
        val json = JSONObject()
            .put("putAckMs", stats(puts.map { ms(it.second.ackNs - it.second.sendNs) }))
            .put("putSpacingMs", stats(puts.zipWithNext { a, b -> ms(b.second.sendNs - a.second.sendNs) }))
        for (src in sources) {
            val seen = events(src, from, to)
            val matches = puts.mapNotNull { (kph, put) ->
                seen.firstOrNull { e -> e.tNs >= put.sendNs && e.value.toFloatOrNull()?.let { abs(it * 3.6 - kph) < 0.05 } == true }
                    ?.let { put to it }
            }
            json.put(src, JSONObject()
                .put("events", seen.size)
                .put("intervalMs", stats(seen.zipWithNext { a, b -> ms(b.tNs - a.tNs) }))
                .put("putsMatched", "${matches.size}/${puts.size}")
                .put("putToCallbackMs", stats(matches.map { (put, e) -> ms(e.tNs - put.sendNs) }))
                .put("putToHalTimestampMs", stats(matches.map { (put, e) -> ms(e.halNs - put.sendNs) }))
                .put("lastValue", seen.lastOrNull()?.value ?: JSONObject.NULL))
        }
        return json
    }

    private fun Event.toJson() = JSONObject().put("t", ms(tNs - startNs)).put("src", src).put("v", value)
        .apply { if (halNs != 0L) put("hal", ms(halNs - startNs)) }

    companion object {
        const val TAG = "ProbeResult"
        private const val EVENT_TAG = "ProbeEvent"
        private const val MAX_VALUES = 12
        private const val RAMP_TAIL_NS = 1_000_000_000L

        fun ms(ns: Long): Double = Math.round(ns / 1e4) / 100.0

        /** n/min/median/max of [xs], rounded to 0.01. */
        fun stats(xs: List<Double>): JSONObject {
            if (xs.isEmpty()) return JSONObject().put("n", 0)
            val sorted = xs.sorted()
            fun r(x: Double) = Math.round(x * 100) / 100.0
            return JSONObject().put("n", xs.size).put("min", r(sorted.first()))
                .put("median", r(sorted[sorted.size / 2])).put("max", r(sorted.last()))
        }
    }
}
