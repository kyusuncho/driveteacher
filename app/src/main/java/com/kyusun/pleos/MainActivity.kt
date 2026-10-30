package com.kyusun.pleos

import android.app.Activity
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kyusun.pleos.scoring.Penalty
import com.kyusun.pleos.scoring.RoadTest
import com.kyusun.pleos.signal.SignalCollector

/**
 * The road test while it is driven (PLAN 5단계, minimum): the score and the last penalty in one line, each penalty
 * spoken. Parked (no UX restrictions) the line gives way to every penalty so far. One test per launch.
 * ponytail: scoring lives in this activity, so it ends when the activity is destroyed (Back); a foreground service
 * would keep it alive behind the navigation app.
 */
class MainActivity : Activity() {
    private lateinit var headline: TextView
    private lateinit var notice: TextView
    private lateinit var history: ScrollView
    private lateinit var historyText: TextView
    private lateinit var voice: Voice
    private lateinit var car: Car
    private var collector: SignalCollector? = null
    private var shown = RoadTest()
    private var driving = true // until the restrictions are read: the driving screen is the safe one
    private var unscored: String? = null // why nothing is scored, if so

    // Only touched on the collector's thread.
    private var test = RoadTest()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        voice = Voice(this)
        // With a lifecycle listener a Car Service restart reconnects instead of killing the app (AOSP).
        car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
            if (ready) watchRestrictions(car)
        }
        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            startScoring()
        } else {
            requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQUEST_SPEED)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_SPEED || collector != null) return
        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            startScoring()
        } else {
            unscored = "차속 권한이 없어 채점할 수 없습니다"
            render()
        }
    }

    override fun onDestroy() {
        collector?.close()
        voice.close()
        car.disconnect()
        super.onDestroy()
    }

    private fun startScoring() {
        collector = SignalCollector(this) { sample ->
            val (next, new) = test.next(sample)
            test = next
            if (new.isNotEmpty()) runOnUiThread { if (!isDestroyed) onPenalties(next, new) }
        }.apply { start() }
        voice.say("도로주행 채점을 시작합니다.") // also warms up the engine: its first sentence is slow
    }

    private fun onPenalties(test: RoadTest, new: List<Penalty>) {
        shown = test
        notice.text = lineOf(new.last())
        voice.say(speechOf(new))
        render()
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

    private fun onRestrictions(r: CarUxRestrictions) {
        driving = r.isRequiresDistractionOptimization
        render()
    }

    /** Driving: score and last penalty. Parked: score and every penalty. Static text, no animation (NO_VIDEO). */
    private fun render() {
        headline.text = if (unscored == null) headlineOf(shown) else "채점 안 함"
        historyText.text = unscored ?: historyOf(shown.penalties)
        unscored?.let { notice.text = it }
        notice.visibility = if (driving) View.VISIBLE else View.GONE
        history.visibility = if (driving) View.GONE else View.VISIBLE
    }

    private fun buildScreen() {
        headline = text(HEADLINE_SP)
        notice = text(NOTICE_SP).apply { text = NO_PENALTY }
        historyText = text(HISTORY_SP)
        history = ScrollView(this).apply { addView(historyText) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
            addView(headline)
            addView(notice)
            addView(history)
        })
        render()
    }

    private fun text(sp: Float) = TextView(this).apply {
        textSize = sp
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, PADDING_PX / 2)
    }

    private companion object {
        const val TAG = "MainActivity"
        const val REQUEST_SPEED = 1
        const val HEADLINE_SP = 96f
        const val NOTICE_SP = 44f
        const val HISTORY_SP = 28f
        const val PADDING_PX = 64
    }
}
