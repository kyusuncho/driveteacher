package com.kyusun.pleos.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared setup for the signal probes: console heartbeat, Vehicle SDK + CarPropertyManager connections,
 * a [SignalLog] per test, and a vehicle reset (Park, pedals/steering 0, signals off) before and after each test.
 */
abstract class ProbeHarness {
    @get:Rule
    val speedPermission: GrantPermissionRule = GrantPermissionRule.grant(Car.PERMISSION_SPEED)

    @get:Rule
    val testName = TestName()

    protected val context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val console = ConsoleClient()
    protected val vehicle = Vehicle(context)
    protected lateinit var car: Car
    protected lateinit var cpm: CarPropertyManager
    protected lateinit var log: SignalLog

    /** Runs after each [step]'s settle time; tests set it to poll getters that have no listener. */
    protected var afterStep: () -> Unit = {}
    private val cleanups = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        log = SignalLog(testName.methodName)
        console.start()
        console.waitActive()
        resetVehicle()
        car = Car.createCar(context)
        cpm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        vehicle.initialize()
        awaitVehicleService()
    }

    @After
    fun tearDown() {
        cleanups.asReversed().forEach { runCatching(it).onFailure { e -> log.note("cleanupError", e.toString()) } }
        try {
            resetVehicle()
        } finally {
            log.save(context.cacheDir)
            vehicle.release()
            if (::car.isInitialized) car.disconnect()
            console.close()
        }
    }

    protected fun onCleanup(block: () -> Unit) {
        cleanups += block
    }

    /** One console write, then wait [settleMs] for callbacks, then [afterStep]. */
    protected fun step(
        label: String,
        path: String,
        vararg params: Pair<String, Any>,
        settleMs: Long = SETTLE_MS,
    ): ConsoleClient.Put {
        val put = console.put(path, params.toMap())
        log.step(label, put)
        Thread.sleep(settleMs)
        afterStep()
        return put
    }

    /** Subscribes to an AAOS property and records every event; registration result and config go to the notes. */
    protected fun watchProp(
        id: Int,
        rate: Float = CarPropertyManager.SENSOR_RATE_FASTEST,
        src: String = "prop." + VehiclePropertyIds.toString(id),
    ) {
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                val area = if (v.areaId != 0) "area${v.areaId}=" else ""
                val status = if (v.status != CarPropertyValue.STATUS_AVAILABLE) " status=${v.status}" else ""
                log.record(src, area + describe(v.value) + status, v.timestamp)
            }
            override fun onErrorEvent(propId: Int, areaId: Int) = log.record(src, "onErrorEvent area=$areaId")
        }
        val config = runCatching { cpm.getCarPropertyConfig(id) }
        val registered = runCatching { cpm.registerCallback(callback, id, rate) }
        log.note("$src.register", "rate=$rate -> ${registered.getOrElse { it.toString() }}; " +
            "config=${config.fold({ it?.let(::describeConfig) ?: "null (unsupported or not permitted)" }, { it.toString() })}")
        if (registered.getOrNull() == true) onCleanup { cpm.unregisterCallback(callback, id) }
    }

    /** Records driving-state (UX restriction) changes as `DO=<requires DO> 0x<active restrictions>`. */
    protected fun watchUxRestrictions() {
        val uxm = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        val describeUx = { r: android.car.drivingstate.CarUxRestrictions ->
            "DO=${r.isRequiresDistractionOptimization} 0x${Integer.toHexString(r.activeRestrictions)}"
        }
        log.record("ux", describeUx(uxm.currentCarUxRestrictions))
        uxm.registerListener { log.record("ux", describeUx(it)) }
        onCleanup { uxm.unregisterListener() }
    }

    /** Calls an async SDK getter/capability check, waits for it and records the result under [src]. */
    protected fun query(src: String, call: (ok: (Any?) -> Unit, fail: (Exception) -> Unit) -> Unit): String {
        val done = CountDownLatch(1)
        val result = AtomicReference("timeout")
        try {
            call({ result.set(describe(it)); done.countDown() }, { result.set("onFailed: ${it.message}"); done.countDown() })
            done.await(QUERY_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            result.set("threw: $e")
        }
        return result.get().also { log.record(src, it) }
    }

    protected fun sdkFailed(src: String, e: Exception) = log.record(src, "onFailed: ${e.message}")

    protected fun now() = SystemClock.elapsedRealtimeNanos()

    protected fun describe(v: Any?): String = when (v) {
        null -> "null"
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { describe(it) }
        is Collection<*> -> v.joinToString(prefix = "[", postfix = "]") { describe(it) }
        is Map<*, *> -> v.entries.joinToString(prefix = "{", postfix = "}") { "${describe(it.key)}=${describe(it.value)}" }
        is SeatArea -> v.values.joinToString("|")
        is WindowArea -> v.values.joinToString("|")
        is Telltales -> v.telltales.filter { it.state != TelltaleState.OFF }
            .joinToString(prefix = "active[", postfix = "] of ${v.telltales.size}") { "${it.type}:${it.state}" }
        else -> v.toString()
    }

    protected fun watchTelltales() {
        val safety = vehicle.getSafety()
        val listener = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) = log.record("sdk.telltales", describe(telltales))
            override fun onFailed(e: Exception) = sdkFailed("sdk.telltales", e)
        }
        safety.registerTelltales(listener)
        onCleanup { safety.unregisterTelltales(listener) }
        query("get.telltales") { ok, fail -> safety.getTelltalesInfo(ok, fail) }
    }

    /** Public SDK domain methods whose name contains [word] (the SDK's own API surface, via reflection). */
    protected fun sdkMethodsContaining(word: String) = Vehicle::class.java.methods
        .map { it.returnType }.filter { it.name.startsWith("ai.pleos.playground.vehicle.api.") }
        .flatMap { api -> api.methods.filter { it.name.contains(word, ignoreCase = true) }.map { "${api.simpleName}.${it.name}" } }
        .distinct().sorted()

    private fun describeConfig(c: CarPropertyConfig<*>) = "access=${c.access} changeMode=${c.changeMode} " +
        "rate=${c.minSampleRate}..${c.maxSampleRate} areas=${c.areaIds.contentToString()} type=${c.propertyType.simpleName}"

    /** initialize() has no connected callback: poll a cheap getter until the Vehicle Service answers. */
    private fun awaitVehicleService() {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < SERVICE_TIMEOUT_MS) {
            val r = query("sdk.ready") { ok, fail -> vehicle.getBrake().getCurrentBrakePedalStatus(ok, fail) }
            if (!r.startsWith("onFailed") && r != "timeout" && !r.startsWith("threw")) {
                log.note("sdk.readyAfterMs", SystemClock.elapsedRealtime() - start)
                return
            }
            Thread.sleep(SERVICE_POLL_MS)
        }
        error("Vehicle Service did not answer within $SERVICE_TIMEOUT_MS ms")
    }

    /** Everything the probes may touch, back to the state found on this emulator (all off, Park, 0). */
    protected fun resetVehicle() {
        console.put(CONTROLS, mapOf("speed" to 0, "accelerator" to 0, "brake" to 0, "steering" to 0))
        console.put(GEAR, mapOf("gear" to "Park"))
        console.put(TURN, mapOf("mode" to "Off"))
        console.put(LIGHTS, mapOf("feature" to "headlamp_mode", "value" to "Off"))
        console.put(LIGHTS, mapOf("feature" to "high_beam", "value" to false))
        console.put(BODY, mapOf("front_wiper" to "Off", "rear_wiper" to "Off"))
        console.put(TELLTALES, mapOf("type" to "SeatBelt", "state" to "Off", "color" to "Red"))
        console.put(SEAT, mapOf("kind" to "occupied", "area" to "driver", "value" to false))
    }

    protected companion object {
        const val CONTROLS = "/vehicle/controls"
        const val GEAR = "/vehicle/gear"
        const val TURN = "/vehicle/turn-signal"
        const val LIGHTS = "/vehicle/lights"
        const val BODY = "/vehicle/body"
        const val TELLTALES = "/vehicle/telltales/simulator"
        const val SEAT = "/vehicle/seat"
        const val ODOMETER = "/vehicle/odometer"
        const val SETTLE_MS = 1_500L
        const val QUERY_TIMEOUT_S = 3L
        const val SERVICE_TIMEOUT_MS = 10_000L
        const val SERVICE_POLL_MS = 250L
    }
}
