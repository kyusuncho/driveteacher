package com.kyusun.driveteacher

import android.app.Activity
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.signal.SignalCollector
import com.kyusun.driveteacher.ui.Actions
import com.kyusun.driveteacher.ui.Faces
import com.kyusun.driveteacher.ui.Palette
import com.kyusun.driveteacher.ui.Stage
import com.kyusun.driveteacher.ui.Ui
import com.kyusun.driveteacher.ui.UiState
import com.kyusun.driveteacher.ui.driveScreen
import com.kyusun.driveteacher.ui.homeScreen
import com.kyusun.driveteacher.ui.resultScreen
import com.kyusun.driveteacher.ui.startScreen

/**
 * Home → start → the road test → its result (PLAN 5단계). A test starts parked; while driving (UX restrictions) the
 * screen is one sign with the score and the last penalty, each penalty also spoken. Parked again, the driver ends the
 * test and reads why each point was lost.
 * ponytail: scoring lives in this activity, so it ends when the activity is destroyed; Back during a test only moves
 * the app behind (the test goes on). A foreground service would keep it alive past a process kill.
 */
class MainActivity : Activity(), Actions {
    private lateinit var faces: Faces
    private lateinit var voice: Voice
    private lateinit var car: Car
    private var collector: SignalCollector? = null
    private var run = 0 // the test the collector's posts belong to
    private var state = UiState()
    private var revealResult = false

    @Volatile private var latest = Exam() // the running test, written on the collector's thread

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        faces = Faces(this)
        voice = Voice(this)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        state = state.copy(canScore = hasSpeedPermission(), simulated = debuggable)
        // With a lifecycle listener a Car Service restart reconnects instead of killing the app (AOSP).
        car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
            if (ready) watchRestrictions(car)
        }
        if (!state.canScore) askPermission()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_SPEED) return
        state = state.copy(canScore = hasSpeedPermission())
        render()
    }

    /** Day/night switches arrive here (configChanges): the palette follows without restarting the test. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (state.stage) {
            Stage.HOME -> @Suppress("DEPRECATION") super.onBackPressed()
            Stage.READY, Stage.DONE -> home()
            Stage.TESTING -> moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        collector?.close()
        voice.close()
        car.disconnect()
        super.onDestroy()
    }

    override fun openRoadTest() = show(state.copy(stage = Stage.READY))

    override fun home() {
        if (state.stage != Stage.TESTING) show(state.copy(stage = Stage.HOME))
    }

    override fun askPermission() = requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQUEST_SPEED)

    override fun start() {
        if (!state.canScore || state.driving || state.stage == Stage.TESTING) return
        val id = ++run
        var exam = Exam() // only touched on the collector's thread
        latest = exam
        collector = SignalCollector(this) { sample ->
            val (next, new) = exam.next(sample)
            exam = next
            latest = next
            if (new.isNotEmpty()) runOnUiThread { if (id == run && !isDestroyed) onPenalties(next, new) }
        }.apply { start() }
        voice.say("도로주행 채점을 시작합니다.") // also warms up the engine: its first sentence is slow
        show(state.copy(stage = Stage.TESTING, exam = exam))
    }

    override fun end() {
        if (state.stage != Stage.TESTING) return
        collector?.close() // once it returns, no more samples: [latest] is final
        collector = null
        run++
        val exam = latest
        voice.say(endSpeechOf(exam.test))
        revealResult = true
        show(state.copy(stage = Stage.DONE, exam = exam, lastResult = exam.test))
    }

    private fun onPenalties(exam: Exam, new: List<Penalty>) {
        voice.say(speechOf(new))
        show(state.copy(exam = exam))
    }

    private fun watchRestrictions(car: Car) {
        val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        if (manager == null) {
            Log.e(TAG, "no UX restrictions service; keeping the driving screen")
            return
        }
        manager.registerListener(::onRestrictions)
        onRestrictions(manager.currentCarUxRestrictions)
    }

    private fun onRestrictions(r: CarUxRestrictions) = show(state.copy(driving = r.isRequiresDistractionOptimization))

    private fun show(next: UiState) {
        state = next
        render()
    }

    /** Rebuilds the screen: penalties and state changes are rare, and a fresh tree keeps every view honest. */
    private fun render() {
        if (isDestroyed) return
        val ui = Ui(this, Palette.of(this), faces)
        // Parked mid-test, show the latest span and coverage, not those of the last penalty.
        val shown = if (state.stage == Stage.TESTING) state.copy(exam = latest) else state
        val screen = when (state.stage) {
            Stage.HOME -> homeScreen(ui, shown, this)
            Stage.READY -> startScreen(ui, shown, this)
            Stage.TESTING, Stage.DONE ->
                if (state.driving) driveScreen(ui, shown.exam.test) else resultScreen(ui, shown, this, revealResult)
        }
        revealResult = false
        setContentView(screen)
    }

    private fun hasSpeedPermission() = checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MainActivity"
        const val REQUEST_SPEED = 1
    }
}
