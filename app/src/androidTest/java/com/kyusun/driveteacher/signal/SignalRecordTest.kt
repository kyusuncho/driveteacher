package com.kyusun.driveteacher.signal

import android.car.Car
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Records the [SignalCollector] stream to cache/signals.csv until cache/signals.stop appears.
 * tools/carla/check_signals.sh runs this next to the CARLA bridge and compares the two logs. By hand:
 *   adb shell run-as com.kyusun.driveteacher --user 10 rm -f cache/signals.stop
 *   adb shell am instrument -w -e class com.kyusun.driveteacher.signal.SignalRecordTest [-e maxSeconds 900] \
 *     com.kyusun.driveteacher.test/androidx.test.runner.AndroidJUnitRunner &
 *   adb shell run-as com.kyusun.driveteacher --user 10 touch cache/signals.stop
 *   adb exec-out run-as com.kyusun.driveteacher --user 10 cat cache/signals.csv
 */
class SignalRecordTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Car.PERMISSION_SPEED, FUSED_LOCATION)

    @Test
    fun record() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val maxMs = (InstrumentationRegistry.getArguments().getString("maxSeconds")?.toLong() ?: DEFAULT_MAX_S) * 1_000
        // Not deleted here: a stop requested while this test was still starting must not be lost.
        val stop = File(context.cacheDir, "signals.stop")
        val out = File(context.cacheDir, "signals.csv")
        out.bufferedWriter().use { writer ->
            // Anchor for lining this file up with the bridge log (wall clocks of emulator and Mac differ).
            writer.write("# wall_ms=${System.currentTimeMillis()} t_ms=${SystemClock.elapsedRealtime()}\n")
            writer.flush()
            var flushedMs = 0L // only touched on the collector's thread
            SignalCollector(context) { sample ->
                writer.write(sample.toCsv())
                writer.newLine()
                // check_signals.sh greps the file for each signal's first value before it starts the bridge.
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - flushedMs >= FLUSH_MS) {
                    writer.flush()
                    flushedMs = nowMs
                }
            }.use { collector ->
                collector.start()
                val startMs = SystemClock.elapsedRealtime()
                while (!stop.exists() && SystemClock.elapsedRealtime() - startMs < maxMs) Thread.sleep(POLL_MS)
            }
        }
        Log.i(TAG, "recorded ${out.length()} bytes to ${out.path}")
    }

    private companion object {
        const val TAG = "SignalRecord"
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val DEFAULT_MAX_S = 900L
        const val POLL_MS = 200L
        const val FLUSH_MS = 500L
    }
}
