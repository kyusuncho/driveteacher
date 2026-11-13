package com.kyusun.driveteacher.probe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Debug-only distraction-optimized (DO) screen for UxAudioProbeTest: one static line of large text.
 * Nothing animates, so it is safe under UX_RESTRICTIONS_NO_VIDEO.
 */
class DoProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            textSize = 48f
            gravity = Gravity.CENTER
            text = intent.getStringExtra(EXTRA_TEXT) ?: "3점 감점되었습니다"
        })
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
