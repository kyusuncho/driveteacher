package com.kyusun.driveteacher.signal

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Collects the car signals the app scores on into one [Sample] stream:
 * AAOS speed (10 Hz) and gear, Vehicle SDK brake / turn signal / steering / driver seat belt,
 * NaviHelper road speed limit, enforcement cameras and next turn, and Pleos FusedLocation.
 * Debug builds also listen to the simulator channel (③, [openSimChannel]): what it sends replaces the car's signal
 * of the same kind ([Overrides]), for what the emulator cannot fake (seat belt, speed limit, cameras, next turn).
 *
 * [sink] runs on one background thread, one sample at a time, in arrival order (see [Sample.tNs] for the times).
 * Call [start] once, then [close]; once [close] returns, [sink] is not called again. Do not call [close] from [sink].
 * A source that fails (missing permission, unsupported signal) logs an error and stays silent; the others go on.
 */
class SignalCollector(private val context: Context, private val sink: (Sample) -> Unit) : AutoCloseable {
    private val thread = HandlerThread("signals").apply { start() }
    private val handler = Handler(thread.looper)

    // Only touched on [thread].
    private val cleanups = mutableListOf<() -> Unit>()
    private var closed = false
    private val overrides = Overrides()

    fun start() {
        handler.post {
            startSource("vehicle", ::startVehicle)
            startSource("car properties", ::startCarProperties)
            startSource("navi", ::startNavi)
            startSource("location", ::startLocation)
            startSource("simulator", ::startSim)
        }
    }

    override fun close() {
        handler.post {
            closed = true
            cleanups.asReversed().forEach { runCatching(it).onFailure { e -> Log.w(TAG, "cleanup failed", e) } }
            cleanups.clear()
        }
        thread.quitSafely()
        thread.join()
    }

    /**
     * A car signal: delivers on [thread] in arrival order unless the simulator has taken over its kind. [make] runs
     * there and may drop the sample by returning null.
     */
    private fun emit(make: () -> Sample?) {
        handler.post { if (!closed) make()?.let(overrides::fromCar)?.let(sink) }
    }

    private fun startSim() {
        val channel = openSimChannel { line ->
            val t = now()
            handler.post {
                if (closed) return@post
                val sample = simSample(line, t)
                if (sample == null) Log.w(TAG, "simulator: unreadable line '$line'") else sink(overrides.fromSim(sample))
            }
        } ?: return
        cleanups += { channel.close() }
    }

    private fun startSource(name: String, start: () -> Unit) {
        runCatching(start).onFailure { Log.e(TAG, "$name: could not start", it) }
    }

    /**
     * With a lifecycle listener a Car Service restart disconnects and reconnects the car instead of killing the app
     * (AOSP); each connection subscribes again. The listener and the property events run on [thread].
     */
    private fun startCarProperties() {
        val car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
            when {
                closed -> Unit
                ready -> startSource("car properties") { watchCarProperties(car) }
                else -> Log.w(TAG, "car service disconnected; speed and gear wait for it to come back")
            }
        }
        cleanups += { car.disconnect() }
    }

    private fun watchCarProperties(car: Car) {
        val cpm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val subscribedNs = now()
        // HAL time: receipt times bunch up when [thread] stalls. Speed also keeps its receipt time, so a recording
        // shows those stalls.
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                if (v.status != CarPropertyValue.STATUS_AVAILABLE) return
                val value = v.value
                val receivedNs = now()
                when {
                    v.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED && value is Float && value.isFinite() ->
                        emit { Sample.Speed(v.timestamp, value, receivedNs) }
                    // The event on registration carries the time of the last change, maybe long ago.
                    v.propertyId == VehiclePropertyIds.GEAR_SELECTION && value is Int ->
                        emit { Sample.Gear(maxOf(v.timestamp, subscribedNs), gearOf(value)) }
                }
            }

            override fun onErrorEvent(propId: Int, areaId: Int) {
                Log.w(TAG, "car property error: ${VehiclePropertyIds.toString(propId)}")
            }
        }
        // On-change properties deliver their current value on registration, so gear needs no separate read.
        for ((id, rate) in listOf(
            VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_FASTEST,
            VehiclePropertyIds.GEAR_SELECTION to CarPropertyManager.SENSOR_RATE_ONCHANGE,
        )) {
            val registered = runCatching { cpm.registerCallback(callback, id, rate) }
            if (registered.getOrNull() == true) {
                cleanups += { cpm.unregisterCallback(callback, id) }
            } else {
                Log.e(TAG, "not subscribed: ${VehiclePropertyIds.toString(id)}", registered.exceptionOrNull())
            }
        }
    }

    private fun startVehicle() {
        val vehicle = Vehicle(context).apply { initialize() }
        cleanups += { vehicle.release() }
        awaitVehicle(vehicle, SystemClock.elapsedRealtime())
    }

    /** initialize() has no connected callback: poll a getter until the Vehicle Service answers, then watch. */
    private fun awaitVehicle(vehicle: Vehicle, sinceMs: Long) {
        val watch = { handler.post { if (!closed) watchVehicle(vehicle) } }
        vehicle.getBrake().getCurrentBrakePedalStatus({ watch() }) { e ->
            if (e is SecurityException) {
                // No CAR_DRIVE: checked on the client, before the service; waiting will not help the others.
                Log.e(TAG, "brake read not permitted; watching the rest", e)
                watch()
            } else if (SystemClock.elapsedRealtime() - sinceMs < VEHICLE_TIMEOUT_MS) {
                handler.postDelayed({ if (!closed) awaitVehicle(vehicle, sinceMs) }, VEHICLE_POLL_MS)
            } else {
                Log.e(TAG, "brake read still failing after $VEHICLE_TIMEOUT_MS ms; watching the rest anyway", e)
                watch()
            }
        }
    }

    /** Each domain has its own permission, and register* throws when it is missing: start them independently. */
    private fun watchVehicle(vehicle: Vehicle) {
        startSource("brake") { watchBrake(vehicle) }
        startSource("turn signal") { watchTurnSignal(vehicle) }
        startSource("steering") { watchSteering(vehicle) }
        startSource("seat belt") { watchSeatBelt(vehicle) }
    }

    private fun watchBrake(vehicle: Vehicle) {
        val brake = vehicle.getBrake()
        val signal = OnChange(Sample::Brake)
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = signal.changed(status)
            override fun onFailed(e: Exception) = failed("brake", e)
        }
        brake.registerBrakePedalStatus(listener)
        cleanups += { brake.unregisterBrakePedalStatus(listener) }
        brake.getCurrentBrakePedalStatus(signal::read) { failed("brake read", it) }
    }

    private fun watchTurnSignal(vehicle: Vehicle) {
        val turn = vehicle.getTurnSignal()
        val signal = OnChange<TurnSignalValue> { t, v -> Sample.Turn(t, turnOf(v)) }
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = signal.changed(state)
            override fun onFailed(e: Exception) = failed("turn signal", e)
        }
        turn.registerTurnSignalState(listener)
        cleanups += { turn.unregisterTurnSignalState(listener) }
        turn.getCurrentTurnSignalState(signal::read) { failed("turn signal read", it) }
    }

    private fun watchSteering(vehicle: Vehicle) {
        val wheel = vehicle.getSteeringWheel()
        val signal = OnChange(Sample::Steering)
        val listener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = signal.changed(angle)
            override fun onFailed(e: Exception) = failed("steering", e)
        }
        wheel.registerSteeringWheelAngle(listener)
        cleanups += { wheel.unregisterSteeringWheelAngle(listener) }
        wheel.getCurrentSteeringWheelAngle(signal::read) { failed("steering read", it) }
    }

    private fun watchSeatBelt(vehicle: Vehicle) {
        val seat = vehicle.getSeat()
        val driver = SeatArea(SeatValue.ROW_1_LEFT)
        val signal = OnChange(Sample::SeatBelt)
        val listener = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = signal.changed(state)
            override fun onFailed(e: Exception) = failed("seat belt", e)
        }
        seat.registerSeatBeltBuckledState(driver, listener)
        cleanups += { seat.unregisterSeatBeltBuckledState(driver, listener) }
        seat.getCurrentSeatBeltBuckledState(driver, signal::read) { failed("seat belt read", it) }
    }

    private fun startNavi() {
        val navi = NaviHelper(context)
        val listener = object : NaviHelperEventListener {
            override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
                val t = now()
                emit { Sample.SpeedLimit(t, speedLimit) }
            }

            override fun onCameraAlert(cameraAlerts: List<CameraAlert>) {
                val t = now()
                emit { Sample.SpeedCameras(t, cameraAlerts.mapNotNull(::speedCameraOf)) }
            }

            override fun onTBTInfo(tbtInfos: List<TBTInfo>) {
                val t = now()
                emit { nextTurnOf(t, tbtInfos) }
            }
        }
        cleanups += { navi.release() }
        navi.initialize(
            onConnected = {
                handler.post {
                    if (!closed) {
                        navi.addListener(listener)
                        cleanups += { navi.removeListener(listener) }
                    }
                }
            },
            onFailed = { Log.e(TAG, "navi: bind failed", it) },
        )
    }

    /** No last-known read: it can be a stale fix from an earlier drive, and live fixes come about 1 Hz. */
    private fun startLocation() {
        val fused = FusedLocation(context).apply { initialize() }
        cleanups += { fused.release() }
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                val t = now()
                emit { Sample.Location(t, location.latitude, location.longitude) }
            }
            override fun onError(e: Exception) = failed("location", e)
        }
        fused.registerFusedLocationCallback(listener)
        cleanups += { fused.unregisterFusedLocationCallback(listener) }
    }

    /**
     * One on-change SDK signal, stamped when its callback fires. Listener values always pass; the startup read
     * passes only if no listener value was delivered first (its reply can land after a newer change). A value equal
     * to the last one delivered is dropped (the read and the listener's first event often report the same state).
     */
    private inner class OnChange<T : Any>(private val sample: (tNs: Long, value: T) -> Sample) {
        // Only touched on [thread].
        private var live = false
        private var last: T? = null
        private var lastNs = 0L

        fun changed(value: T?) {
            value ?: return
            val t = now()
            emit { live = true; deliver(t, value) }
        }

        fun read(value: T?) {
            value ?: return
            val t = now()
            emit { if (live) null else deliver(t, value) }
        }

        /** Callbacks can come on different binder threads; keep this signal's times non-decreasing. */
        private fun deliver(t: Long, value: T): Sample? {
            if (value == last) return null
            last = value
            lastNs = maxOf(lastNs, t)
            return sample(lastNs, value)
        }
    }

    private fun failed(source: String, e: Exception) {
        Log.w(TAG, "$source failed", e)
    }

    private companion object {
        const val TAG = "SignalCollector"
        const val VEHICLE_POLL_MS = 250L
        const val VEHICLE_TIMEOUT_MS = 10_000L

        fun now() = SystemClock.elapsedRealtimeNanos()
    }
}

/**
 * Simulator values (③) replace the car's of the same kind, from the simulator's first value of that kind on: the
 * car's value counts until then (a debug build in a real car, with no simulator, keeps every car signal).
 * It lasts as long as the collector (one simulated drive): if the bridge stops first, the last simulator values stay.
 * Not thread-safe: SignalCollector uses it on its one thread.
 */
internal class Overrides {
    private val kinds = mutableSetOf<Class<out Sample>>()

    fun fromSim(sample: Sample): Sample = sample.also { kinds += it.javaClass }

    fun fromCar(sample: Sample): Sample? = sample.takeUnless { it.javaClass in kinds }
}

/** A type this SDK version does not know is dropped; separators in the id would break the recording line. */
private fun speedCameraOf(alert: CameraAlert): SpeedCamera? {
    val type = SpeedCameraType.entries.find { it.name == alert.type.name } ?: return null
    val id = alert.id.replace(Regex("[,/;]"), "-")
    return SpeedCamera(id, type, alert.speedLimit, alert.remainDistance, alert.isOverSpeed)
}

/**
 * The nearest turn ahead and the signal it calls for: left and right turns, and U-turns (made from the left in Korea).
 * Forks, exits, roundabouts and the like are not turns the road test judges, so they are passed over. Unit assumed m.
 */
internal fun nextTurnOf(tNs: Long, maneuvers: List<TBTInfo>): Sample.NextTurn = maneuvers
    .filter { it.distance in 0.0..Double.MAX_VALUE }
    .mapNotNull { m -> signalFor(m.type)?.let { Sample.NextTurn(tNs, it, m.distance.toFloat()) } }
    .minByOrNull { it.remainM }
    ?: Sample.NextTurn(tNs, TurnSignal.OFF, 0f)

private fun signalFor(type: TurnType): TurnSignal? = when (type) {
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_UTURN -> TurnSignal.LEFT
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> TurnSignal.RIGHT
    else -> null
}

/** EVs have P/R/N/D only; manual gears (GEAR_1..) are not expected. */
internal fun gearOf(aaos: Int): GearPosition = when (aaos) {
    VehicleGear.GEAR_PARK -> GearPosition.PARK
    VehicleGear.GEAR_REVERSE -> GearPosition.REVERSE
    VehicleGear.GEAR_NEUTRAL -> GearPosition.NEUTRAL
    VehicleGear.GEAR_DRIVE -> GearPosition.DRIVE
    else -> GearPosition.UNKNOWN
}

private fun turnOf(v: TurnSignalValue): TurnSignal = when (v) {
    TurnSignalValue.LEFT -> TurnSignal.LEFT
    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    TurnSignalValue.OFF -> TurnSignal.OFF
}
