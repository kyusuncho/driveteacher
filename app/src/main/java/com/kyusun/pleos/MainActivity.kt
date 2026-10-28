package com.kyusun.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var vehicle: Vehicle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 32f
            setPadding(64, 64, 64, 64)
            this.text = "Reading driver door…"
        }
        setContentView(text)

        vehicle = Vehicle(this).apply { initialize() }
        vehicle.getDoor().getCurrentDoorPosition(
            area = DoorArea(DoorValue.ROW_1_LEFT),
            onComplete = { pos -> runOnUiThread { text.text = "Driver door position: $pos" } },
            onFailed = { e -> runOnUiThread { text.text = "Vehicle SDK error: ${e.message}" } },
        )
    }

    override fun onDestroy() {
        vehicle.release()
        super.onDestroy()
    }
}
