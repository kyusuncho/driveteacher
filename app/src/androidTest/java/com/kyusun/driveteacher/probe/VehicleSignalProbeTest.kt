package com.kyusun.driveteacher.probe

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.VehiclePropertyIds as Ids
import android.car.hardware.property.CarPropertyManager.SENSOR_RATE_UI
import org.json.JSONArray
import org.junit.Test

/**
 * Which vehicle signals can a third-party Pleos app observe, how fast, and in what units?
 * Each test drives the Virtual Console from the device and records every app-visible callback.
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell pm grant --user 10 com.kyusun.driveteacher android.car.permission.CAR_SPEED
 *   adb shell am instrument -w -e class com.kyusun.driveteacher.probe.VehicleSignalProbeTest \
 *     com.kyusun.driveteacher.test/androidx.test.runner.AndroidJUnitRunner
 *   adb exec-out run-as com.kyusun.driveteacher --user 10 cat cache/probe-<test>.json   (also: adb logcat -s ProbeResult)
 */
class VehicleSignalProbeTest : ProbeHarness() {
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)

    @Suppress("DEPRECATION") // the SDK Gear API is deprecated in favour of AAOS GEAR_SELECTION; both are measured
    @Test
    fun gear() {
        val gear = vehicle.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = log.record("sdk.gear", describe(gear))
            override fun onFailed(e: Exception) = sdkFailed("sdk.gear", e)
        }
        gear.registerGearSelection(listener)
        onCleanup { gear.unregisterGearSelection(listener) }
        watchProp(Ids.GEAR_SELECTION)
        watchProp(Ids.CURRENT_GEAR)
        watchProp(VENDOR_GEAR_SELECTION, src = "prop.VENDOR_GEAR_SELECTION")
        watchUxRestrictions()
        afterStep = { query("get.gear") { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) } }
        for (g in listOf("Drive", "Park", "Reverse", "Neutral", "Drive", "Park")) step("gear $g", GEAR, "gear" to g)
    }

    @Test
    fun speedConstant() {
        log.note("sdk.methodsNamedSpeed", JSONArray(sdkMethodsContaining("speed")))
        watchProp(Ids.PERF_VEHICLE_SPEED)
        watchProp(Ids.PERF_VEHICLE_SPEED_DISPLAY)
        step("gear Drive", GEAR, "gear" to "Drive")
        step("speed 50", CONTROLS, "speed" to 50, settleMs = 2_000)
        val from = now()
        Thread.sleep(HOLD_MS)
        val to = now()
        for (src in listOf("prop.PERF_VEHICLE_SPEED", "prop.PERF_VEHICLE_SPEED_DISPLAY")) {
            log.note("hold50.$src", log.holdStats(src, from, to))
        }
        step("speed 0", CONTROLS, "speed" to 0)
    }

    @Test
    fun speedRamp() {
        val sources = mapOf("speed.fastest" to Ids.PERF_VEHICLE_SPEED, "speed.ui5Hz" to Ids.PERF_VEHICLE_SPEED,
            "speedDisplay.fastest" to Ids.PERF_VEHICLE_SPEED_DISPLAY)
        sources.forEach { (src, id) -> watchProp(id, if (src == "speed.ui5Hz") SENSOR_RATE_UI else 100f, src) }
        watchUxRestrictions()
        step("gear Drive", GEAR, "gear" to "Drive")
        repeat(RAMP_TRIALS) { trial ->
            val start = android.os.SystemClock.elapsedRealtime()
            val puts = (0..RAMP_TOP_KPH).map { kph ->
                Thread.sleep(maxOf(0, start + kph * RAMP_PERIOD_MS - android.os.SystemClock.elapsedRealtime()))
                kph to console.put(CONTROLS, mapOf("speed" to kph))
            }
            Thread.sleep(1_000)
            log.note("ramp$trial", log.speedRampStats(puts, sources.keys))
            step("ramp $trial done, speed 0", CONTROLS, "speed" to 0, settleMs = 2_000)
        }
    }

    @Test
    fun brake() {
        val brake = watchSdkBrake()
        query("cap.brake") { ok, fail -> brake.checkBrakePedalStatusCapabilities(ok, fail) }
        watchProp(VENDOR_BRAKE_PEDAL_STATUS, src = "prop.VENDOR_BRAKE_PEDAL_STATUS")
        watchProp(Ids.PARKING_BRAKE_ON)
        afterStep = { query("get.brake") { ok, fail -> brake.getCurrentBrakePedalStatus(ok, fail) } }
        for (pct in listOf(50, 100, 0, 5, 0, 1, 0)) step("brake $pct", CONTROLS, "brake" to pct)
    }

    /** Watches every property the app may read, plus the SDK brake, while the accelerator moves. */
    @Test
    fun accelerator() {
        val visible = cpm.propertyList.map { it.propertyId }
        log.note("visibleProps", JSONArray(visible.map { Ids.toString(it) }))
        visible.forEach { watchProp(it) }
        watchSdkBrake()
        val baseline = step("gear Drive (baseline)", GEAR, "gear" to "Drive", settleMs = 3_000)
        val pressed = step("accelerator 50", CONTROLS, "accelerator" to 50, settleMs = 3_000)
        step("accelerator 100", CONTROLS, "accelerator" to 100, settleMs = 3_000)
        val released = step("accelerator 0", CONTROLS, "accelerator" to 0, settleMs = 3_000)
        val changed = (visible.map { "prop." + Ids.toString(it) } + "sdk.brake").filter { src ->
            val before = log.events(src, baseline.ackNs + SETTLE_NS, pressed.sendNs).map { it.value }.toSet()
            val during = log.events(src, pressed.sendNs, released.sendNs).map { it.value }.toSet()
            (during - before).isNotEmpty()
        }
        log.note("changedWhileAccelerating", JSONArray(changed))
    }

    @Test
    fun steering() {
        val wheel = vehicle.getSteeringWheel()
        val listener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = log.record("sdk.steering", describe(angle))
            override fun onFailed(e: Exception) = sdkFailed("sdk.steering", e)
        }
        wheel.registerSteeringWheelAngle(listener)
        onCleanup { wheel.unregisterSteeringWheelAngle(listener) }
        query("cap.steering") { ok, fail -> wheel.checkSteeringWheelAngleCapabilities({ c, b -> ok(listOf(c, b)) }, fail) }
        watchProp(Ids.PERF_STEERING_ANGLE)
        afterStep = { query("get.steering") { ok, fail -> wheel.getCurrentSteeringWheelAngle(ok, fail) } }
        for (deg in listOf(90, -90, 0, 450, -450, 0)) step("steering $deg", CONTROLS, "steering" to deg)
    }

    @Test
    fun turnSignal() {
        val turn = vehicle.getTurnSignal()
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = log.record("sdk.turn", describe(state))
            override fun onFailed(e: Exception) = sdkFailed("sdk.turn", e)
        }
        turn.registerTurnSignalState(listener)
        onCleanup { turn.unregisterTurnSignalState(listener) }
        query("cap.turn") { ok, fail -> turn.checkTurnSignalStateCapabilities(ok, fail) }
        watchProp(Ids.TURN_SIGNAL_STATE)
        watchTelltales()
        afterStep = { query("get.turn") { ok, fail -> turn.getCurrentTurnSignalState(ok, fail) } }
        for (mode in listOf("BlinkLeft", "Off", "BlinkRight", "Off", "FlashLeft", "Off", "FlashRight", "Off")) {
            step("turn $mode", TURN, "mode" to mode)
        }
    }

    @Test
    fun seatBelt() {
        val seat = vehicle.getSeat()
        val safety = vehicle.getSafety()
        val buckled = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = log.record("sdk.beltBuckled", describe(state))
            override fun onFailed(e: Exception) = sdkFailed("sdk.beltBuckled", e)
        }
        val warning = object : SeatBeltNotBuckledWarningListener {
            override fun onSeatBeltNotBuckledWarningUpdated(state: Boolean?) = log.record("sdk.beltWarning", describe(state))
            override fun onFailed(e: Exception) = sdkFailed("sdk.beltWarning", e)
        }
        val occupancy = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = log.record("sdk.occupancy", describe(occupancy))
            override fun onFailed(e: Exception) = sdkFailed("sdk.occupancy", e)
        }
        seat.registerSeatBeltBuckledState(driver, buckled)
        safety.registerSeatBeltNotBuckledWarning(driver, warning)
        seat.registerSeatOccupancy(driver, occupancy)
        onCleanup {
            seat.unregisterSeatBeltBuckledState(driver, buckled)
            safety.unregisterSeatBeltNotBuckledWarning(driver, warning)
            seat.unregisterSeatOccupancy(driver, occupancy)
        }
        query("cap.beltBuckled") { ok, fail -> seat.checkSeatBeltBuckledCapabilities({ c, a -> ok(listOf(c, a)) }, fail) }
        query("cap.seatBuckled") { ok, fail -> safety.checkSeatBuckledCapabilities({ c, a -> ok(listOf(c, a)) }, fail) }
        query("cap.beltWarning") { ok, fail -> safety.checkSeatNotBuckledWarningCapabilities({ c, a -> ok(listOf(c, a)) }, fail) }
        query("cap.occupancy") { ok, fail -> seat.checkSeatOccupancyCapabilities({ c, a -> ok(listOf(c, a)) }, fail) }
        watchProp(Ids.SEAT_BELT_BUCKLED)
        watchProp(Ids.SEAT_OCCUPANCY)
        watchTelltales()
        afterStep = {
            query("get.beltBuckled") { ok, fail -> seat.getCurrentSeatBeltBuckledState(driver, ok, fail) }
            query("get.beltWarning") { ok, fail -> safety.hasSeatBeltNotBuckledWarning(driver, ok, fail) }
            query("get.occupancy") { ok, fail -> seat.getCurrentSeatOccupancy(driver, ok, fail) }
        }
        for (state in listOf("On", "Off", "Blink", "Off")) {
            step("telltale SeatBelt $state", TELLTALES, "type" to "SeatBelt", "state" to state, "color" to "Red")
        }
        for (occupied in listOf(true, false)) {
            step("driver occupied $occupied", SEAT, "kind" to "occupied", "area" to "driver", "value" to occupied)
        }
    }

    @Test
    fun lights() {
        val light = vehicle.getLight()
        fun watch(src: String, register: (LightStateListener) -> Unit, unregister: (LightStateListener) -> Unit) {
            val listener = object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = log.record(src, describe(state))
                override fun onFailed(e: Exception) = sdkFailed(src, e)
            }
            register(listener)
            onCleanup { unregister(listener) }
        }
        watch("sdk.lowBeam", light::registerLowBeamLightState, light::unregisterLowBeamLightState)
        watch("sdk.highBeam", light::registerHighBeamLightState, light::unregisterHighBeamLightState)
        watch("sdk.tailLamp", light::registerTailLampLightState, light::unregisterTailLampLightState)
        watchProp(Ids.HEADLIGHTS_STATE)
        watchProp(Ids.HEADLIGHTS_SWITCH)
        watchProp(Ids.HIGH_BEAM_LIGHTS_STATE)
        watchProp(VENDOR_EXTERIOR_LIGHTS_MODE, src = "prop.VENDOR_EXTERIOR_LIGHTS_MODE")
        watchTelltales()
        afterStep = {
            query("get.lowBeamSwitch") { ok, fail -> light.getLowBeamLightSwitch(ok, fail) }
            query("get.highBeamSwitch") { ok, fail -> light.getHighBeamLightSwitch(ok, fail) }
        }
        for (mode in listOf("Position", "LowBeam", "Auto", "Off")) {
            step("headlamp $mode", LIGHTS, "feature" to "headlamp_mode", "value" to mode)
        }
        for (on in listOf(true, false)) step("high beam $on", LIGHTS, "feature" to "high_beam", "value" to on)
    }

    @Test
    fun wiper() {
        val wiper = vehicle.getWiper()
        val listener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = log.record("sdk.wiperState", describe(state))
            override fun onFailed(e: Exception) = sdkFailed("sdk.wiperState", e)
        }
        wiper.registerWiperState(front, listener)
        onCleanup { wiper.unregisterWiperState(front, listener) }
        query("cap.wiperState") { ok, fail -> wiper.checkWiperStateCapabilities({ c, m -> ok(listOf(c, m)) }, fail) }
        query("cap.wiperSwitch") { ok, fail -> wiper.checkWiperSwitchCapabilities({ c, m -> ok(listOf(c, m)) }, fail) }
        afterStep = {
            query("get.wiperSwitch") { ok, fail -> wiper.getCurrentWiperSwitch(front, ok, fail) }
            query("get.wiperState") { ok, fail -> wiper.getCurrentWiperState(front, ok, fail) }
        }
        for (mode in listOf("Low", "High", "Auto", "Off")) step("front wiper $mode", BODY, "front_wiper" to mode)
    }

    @Test
    fun odometer() {
        val odometer = vehicle.getOdometer()
        fun watch(src: String, register: (DistanceDrivenListener) -> Unit, unregister: (DistanceDrivenListener) -> Unit) {
            val listener = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) = log.record(src, describe(value))
                override fun onFailed(e: Exception) = sdkFailed(src, e)
            }
            register(listener)
            onCleanup { unregister(listener) }
        }
        watch("sdk.odometerKm", odometer::registerDistanceDriven, odometer::unregisterDistanceDriven)
        watch("sdk.tripDistanceM", odometer::registerTripCurrentDrivingDistance, odometer::unregisterTripCurrentDrivingDistance)
        val time = object : DrivingTimeListener {
            override fun onDrivingTimeUpdated(value: Int?) = log.record("sdk.tripTimeMin", describe(value))
            override fun onFailed(e: Exception) = sdkFailed("sdk.tripTimeMin", e)
        }
        odometer.registerTripCurrentDrivingTime(time)
        onCleanup { odometer.unregisterTripCurrentDrivingTime(time) }
        watchProp(Ids.PERF_ODOMETER)
        afterStep = {
            query("get.odometerKm") { ok, fail -> odometer.getCurrentDistanceDriven(ok, fail) }
            query("get.tripDistanceM") { ok, fail -> odometer.getTripCurrentDrivingDistance(ok, fail) }
        }
        step("gear Drive", GEAR, "gear" to "Drive")
        step("speed 60, hold", CONTROLS, "speed" to 60, settleMs = ODOMETER_DRIVE_MS)
        step("speed 0", CONTROLS, "speed" to 0)
        // The console odometer cannot go back (400 traveled_distance_must_not_decrease), so bump it by 1 km only.
        val km = Math.round(console.state().getJSONObject("odometer").getDouble("traveled_distance_km")) + 1
        step("console odometer $km km", ODOMETER, "traveled_distance_km" to km, settleMs = ODOMETER_DRIVE_MS / 2)
    }

    private fun watchSdkBrake() = vehicle.getBrake().also { brake ->
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = log.record("sdk.brake", describe(status))
            override fun onFailed(e: Exception) = sdkFailed("sdk.brake", e)
        }
        brake.registerBrakePedalStatus(listener)
        onCleanup { brake.unregisterBrakePedalStatus(listener) }
    }

    private companion object {
        const val VENDOR_GEAR_SELECTION = 0x21400400
        const val VENDOR_BRAKE_PEDAL_STATUS = 0x21200409
        const val VENDOR_EXTERIOR_LIGHTS_MODE = 0x21400754
        const val HOLD_MS = 10_000L
        const val RAMP_TRIALS = 2
        const val RAMP_TOP_KPH = 60
        const val RAMP_PERIOD_MS = 100L
        const val SETTLE_NS = 1_000_000_000L
        const val ODOMETER_DRIVE_MS = 20_000L
    }
}
