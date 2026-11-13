package com.kyusun.driveteacher.probe

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.Manifest
import android.content.Intent
import android.content.pm.PermissionInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kyusun.driveteacher.MainActivity
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Navigation / location signals a third-party Pleos app can observe while "driving" on the emulator:
 * NaviHelper callbacks (road speed limit, cameras, caution points...), Pleos FusedLocation and android.location.
 * GPS comes from the host feeder (adb emu geo fix along 테헤란로); the console holds Drive at 36 km/h.
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.kyusun.driveteacher.probe.NaviLocationProbeTest \
 *     com.kyusun.driveteacher.test/androidx.test.runner.AndroidJUnitRunner &
 *   python3 -I <scratchpad>/probe-tools/geo_feeder.py --log <scratchpad>/probe-logs/feeder.csv
 *   adb exec-out run-as com.kyusun.driveteacher --user 10 cat cache/probe-<test>.json
 */
class NaviLocationProbeTest : ProbeHarness() {
    @get:Rule
    val locationPermissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, FUSED_LOCATION)

    private val firstThread = ConcurrentHashMap<String, String>()
    private lateinit var navi: NaviHelper

    /** No route: does the speed limit (or anything else) arrive in free drive? */
    @Test
    fun idleDrive() {
        startSources()
        drive()
        listen()
    }

    /** Route guidance requested through the SDK toward 삼성역 (east end of the feeder's road). */
    @Test
    fun guidanceDrive() {
        startSources()
        drive()
        record("navi.requestRoute", runCatching {
            navi.requestRoute(RouteInfo(latitude = 37.50884, longitude = 127.06316, displayLatitude = null,
                displayLongitude = null, poiName = "삼성역", poiId = "", address = "서울 강남구 테헤란로 지하 538",
                poiSubId = "0"))
        }.fold({ "sent" }, { "threw: $it" }))
        onCleanup { navi.cancelRoute() }
        listen()
    }

    /**
     * android.location needs a foreground app (an instrumentation process alone is background and gets no fixes).
     * Park, so the non-DO MainActivity is not covered by the driving-restriction blocker.
     */
    @Test
    fun androidLocationForeground() {
        val activity = InstrumentationRegistry.getInstrumentation()
            .startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onCleanup { activity.finish() }
        record("clock.wallMs", System.currentTimeMillis().toString())
        startFused()
        startAndroidLocation()
        listen(FOREGROUND_LISTEN_MS, pollNavi = false)
    }

    /** Pleos TextToSpeech in ON_DEVICE mode: no registerApp, no credentials. */
    @Test
    fun ttsOnDevice() {
        val done = CountDownLatch(1)
        val tts = TextToSpeech(context, Mode.ON_DEVICE)
        val events = object : EventListener {
            override fun onStart() = record("tts", "onStart")
            override fun onReady() = record("tts", "onReady")
            override fun onDone() = record("tts", "onDone").also { done.countDown() }
            override fun onError(type: ErrorType) = record("tts", "onError $type").also { done.countDown() }
            override fun onStop() = record("tts", "onStop")
            override fun onUpdatedRms(rms: Double) = record("tts.rms", "%.1f".format(rms))
        }
        onCleanup { tts.removeEventListener(events); tts.release() }
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                record("tts.init", "onSuccess")
                tts.addEventListener(events)
                tts.setLocale(Locale.KOREA)
                tts.speak("제한속도 오십 킬로미터 구간입니다.")
            }
            override fun onFailure(type: ErrorType) = record("tts.init", "onFailure $type").also { done.countDown() }
        })
        log.note("tts.finished", done.await(TTS_TIMEOUT_S, TimeUnit.SECONDS))
    }

    private fun drive() {
        step("gear Drive", GEAR, "gear" to "Drive")
        step("speed 36", CONTROLS, "speed" to 36)
    }

    /** Polls the NaviHelper getters every [POLL_MS] for [durationMs], then writes per-source cadence notes. */
    private fun listen(durationMs: Long = LISTEN_MS, pollNavi: Boolean = true) {
        val from = now()
        if (pollNavi) navi.getDestinationInfo()
        while (now() - from < durationMs * 1_000_000) {
            if (pollNavi) {
                navi.getCurrentLocationInfo()
                navi.getRouteStateInfo()
                navi.getTBTInfo()
            }
            Thread.sleep(POLL_MS)
        }
        val to = now()
        firstThread.keys.sorted().forEach { src ->
            log.note("hold.$src", log.holdStats(src, from, to)
                .put("firstMs", log.events(src, from, to).firstOrNull()?.let { SignalLog.ms(it.tNs - from) })
                .put("thread", firstThread[src]))
        }
    }

    private fun startSources() {
        record("clock.wallMs", System.currentTimeMillis().toString())
        log.note("perm.FUSED_LOCATION", describePermission(FUSED_LOCATION))
        startNavi()
        startFused()
        startAndroidLocation()
    }

    private fun startNavi() {
        navi = NaviHelper(context)
        val listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NaviHelperEventListener::class.java)) { proxy, m, args ->
            when (m.name) { // Object methods first: removeListener needs a sane equals/hashCode
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NaviProbeListener"
                else -> record("navi.${m.name}", args?.joinToString { describe(it) } ?: "").let { null }
            }
        } as NaviHelperEventListener
        val bound = CountDownLatch(1)
        val t0 = SystemClock.elapsedRealtime()
        navi.initialize(
            onConnected = { record("navi.bind", "connected after ${SystemClock.elapsedRealtime() - t0} ms"); bound.countDown() },
            onFailed = { record("navi.bind", "failed: $it"); bound.countDown() },
        )
        log.note("navi.bound", bound.await(BIND_TIMEOUT_S, TimeUnit.SECONDS))
        navi.addListener(listener)
        onCleanup { navi.removeListener(listener); navi.release() }
    }

    private fun startFused() {
        val fused = FusedLocation(context)
        log.note("fused.isSupported", runCatching { fused.isSupported() }.getOrElse { it.toString() })
        fused.initialize()
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = record("fused", describeFused(location))
            override fun onError(e: Exception) = record("fused", "onError $e")
        }
        fused.registerFusedLocationCallback(listener)
        fused.getLastKnownLocation(object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = record("fused.last", describeFused(location))
            override fun onError(e: Exception) = record("fused.last", "onError $e")
        })
        onCleanup { fused.unregisterFusedLocationCallback(listener); fused.release() }
    }

    private fun startAndroidLocation() {
        val lm = context.getSystemService(LocationManager::class.java)
        log.note("lm.providers", lm.getProviders(true).joinToString())
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)) {
            val listener = LocationListener { record("lm.$provider", describeLocation(it)) }
            val result = runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            log.note("lm.$provider.request", result.fold({ "ok" }, { it.toString() }))
            if (result.isSuccess) onCleanup { lm.removeUpdates(listener) }
        }
    }

    private fun record(src: String, value: String) {
        firstThread.putIfAbsent(src, Thread.currentThread().name)
        log.record(src, value)
    }

    private fun describeFused(l: LocationData?) = l?.let {
        "%.6f,%.6f alt=%.1f h=%.1f conf=%.2f ageMs=%d".format(it.latitude, it.longitude, it.altitude, it.heading,
            it.confidence, System.currentTimeMillis() - it.timestampMillis)
    } ?: "null"

    private fun describeLocation(l: Location) = "%.6f,%.6f acc=%.1f spd=%s brg=%s ageMs=%d".format(l.latitude, l.longitude,
        l.accuracy, if (l.hasSpeed()) "%.2f".format(l.speed) else "-", if (l.hasBearing()) "%.1f".format(l.bearing) else "-",
        System.currentTimeMillis() - l.time)

    private fun describePermission(name: String) = runCatching {
        val info = context.packageManager.getPermissionInfo(name, 0)
        JSONObject().put("protection", if (info.protection == PermissionInfo.PROTECTION_DANGEROUS) "dangerous" else info.protection)
            .put("definedBy", info.packageName)
    }.getOrElse { it.toString() }

    private companion object {
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val LISTEN_MS = 90_000L
        const val FOREGROUND_LISTEN_MS = 30_000L
        const val POLL_MS = 5_000L
        const val BIND_TIMEOUT_S = 10L
        const val TTS_TIMEOUT_S = 20L
    }
}
