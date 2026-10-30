package com.kyusun.pleos.probe

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.property.CarPropertyManager.SENSOR_RATE_UI
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.widget.Toast
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kyusun.pleos.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * What a driving-time "감점 알림" can use on the emulator: UX restrictions per gear/speed, whether a
 * distraction-optimized activity (DoProbeActivity, debug only) stays in front while the non-DO MainActivity is
 * blocked, audio-focus grants per usage, prompt playback, toast / heads-up. Shell commands (dumpsys, cmd) run
 * through UiAutomation; screenshots and full audio dumps go to cache/ux-*.
 * Measured 10/28, when MainActivity was not DO; it is since 10/30 (5단계), so a rerun no longer shows the blocked case.
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.kyusun.pleos.probe.UxAudioProbeTest \
 *     com.kyusun.pleos.test/androidx.test.runner.AndroidJUnitRunner
 *   adb exec-out run-as com.kyusun.pleos --user 10 cat cache/probe-<test>.json   (also: adb logcat -s ProbeResult)
 */
class UxAudioProbeTest : ProbeHarness() {
    @get:Rule
    val notificationPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()

    /** M-ux-states: restrictions in Park / Drive 0 / Drive 30 (per driving_regulation setting if the command exists). */
    @Test
    fun uxStates() {
        val uxm = watchUx()
        watchProp(VehiclePropertyIds.PERF_VEHICLE_SPEED, SENSOR_RATE_UI)
        // `cmd` reports a missing service on stderr, which executeShellCommand drops; `service check` uses stdout.
        val probe = shell("service check hmg_car_service").trim()
        log.note("regulation.service", probe)
        val modes = if ("not found" in probe) listOf("unavailable") else listOf("off", "on")
        for (mode in modes) {
            if (mode != "unavailable") log.note("regulation.$mode", shell(regulationCmd(mode)).trim())
            log.note("$mode.park", describeUx(uxm.currentCarUxRestrictions))
            step("$mode gear Drive", GEAR, "gear" to "Drive")
            log.note("$mode.drive0", describeUx(uxm.currentCarUxRestrictions))
            step("$mode speed 30", CONTROLS, "speed" to 30, settleMs = SPEED_SETTLE_MS)
            log.note("$mode.drive30", describeUx(uxm.currentCarUxRestrictions))
            step("$mode speed 0", CONTROLS, "speed" to 0, settleMs = SPEED_SETTLE_MS)
            step("$mode gear Park", GEAR, "gear" to "Park")
            log.note("$mode.parkAgain", describeUx(uxm.currentCarUxRestrictions))
        }
        // ponytail: original regulation state is not queryable; "on" assumed to be the default.
        if ("unavailable" !in modes) shell(regulationCmd("on"))
    }

    /** M-ux-visible + M-audio-focus: which activity/window is in front, focus grants per context. */
    @Test
    fun screenAndAudio() {
        watchUx()
        watchLifecycle()
        watchProp(VehiclePropertyIds.PERF_VEHICLE_SPEED, SENSOR_RATE_UI)

        launch(MainActivity::class.java, "park-main")
        focusSweep("park-main")

        driveAt30()
        snapshot("drive30-main-afterShift")
        focusSweep("drive30-mainBlocked")

        launch(DoProbeActivity::class.java, "drive30-do-launched")
        focusSweep("drive30-do")

        launch(MainActivity::class.java, "drive30-main-launched")
        park()
        snapshot("park-afterDrive")

        launch(DoProbeActivity::class.java, "park-do")
        driveAt30()
        snapshot("drive30-do-afterShift")
        dumpAudio("screenAndAudio-end")
    }

    /**
     * M-toast: Toast and heads-up with a DO activity in front, Park vs Drive 30. AOSP CarHeadsUpNotificationManager
     * shows a third-party HUN only for CATEGORY_CALL / CATEGORY_NAVIGATION, so both "no category" and NAVIGATION are tried.
     */
    @Test
    fun notices() {
        watchUx()
        watchLifecycle()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(HUN_CHANNEL, "probe", NotificationManager.IMPORTANCE_HIGH))
        onCleanup { nm.cancel(HUN_ID); nm.deleteNotificationChannel(HUN_CHANNEL) }
        log.note("notificationsEnabled", nm.areNotificationsEnabled())

        launch(DoProbeActivity::class.java, "park-do")
        toast("park-do")
        headsUp(nm, "park-do-navigation", Notification.CATEGORY_NAVIGATION)
        driveAt30()
        toast("drive30-do")
        headsUp(nm, "drive30-do-noCategory", null)
        headsUp(nm, "drive30-do-navigation", Notification.CATEGORY_NAVIGATION)
        launch(MainActivity::class.java, "drive30-main")
        toast("drive30-mainBlocked")
    }

    /** M-audio-play: NAVIGATION_GUIDANCE prompt (200 ms pre-roll + 1 s tone) alone, over in-app media, matrix probe. */
    @Test
    fun audioPlay() {
        watchUx()
        watchLifecycle()
        driveAt30()
        launch(DoProbeActivity::class.java, "drive30-do")
        log.note("audio.mode", audio.mode)

        playPrompt("prompt-alone")
        withMediaPlaying { playPrompt("prompt-overMedia") }

        // Pleos matrix v1.0: VOICE_COMMAND holder rejects MUSIC (AOSP 14 grants it); both reject NAVIGATION.
        val assistant = requestFocus("matrix.assistantHold", AudioAttributes.USAGE_ASSISTANT, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        val nav = requestFocus("matrix.navWhileAssistant", AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        val media = requestFocus("matrix.mediaWhileAssistant", AudioAttributes.USAGE_MEDIA, AudioManager.AUDIOFOCUS_GAIN)
        dumpAudio("matrix")
        listOfNotNull(media, nav, assistant).forEach(audio::abandonAudioFocusRequest)
        dumpAudio("audioPlay-end")
    }

    // ---- UX restrictions ----

    private fun watchUx(): CarUxRestrictionsManager {
        val uxm = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        log.record("ux", describeUx(uxm.currentCarUxRestrictions))
        uxm.registerListener { log.record("ux", describeUx(it)) }
        onCleanup { uxm.unregisterListener() }
        return uxm
    }

    private fun describeUx(r: CarUxRestrictions): String {
        val flags = UX_FLAGS.filterKeys { r.activeRestrictions and it != 0 }.values.joinToString("|").ifEmpty { "BASELINE" }
        return "DO=${r.isRequiresDistractionOptimization} 0x${Integer.toHexString(r.activeRestrictions)} $flags " +
            "maxStr=${r.maxRestrictedStringLength} maxItems=${r.maxCumulativeContentItems} maxDepth=${r.maxContentDepth}"
    }

    private fun regulationCmd(mode: String) = "cmd hmg_car_service --service drivingstate driving_regulation $mode"

    private fun driveAt30() {
        step("gear Drive", GEAR, "gear" to "Drive")
        step("speed 30", CONTROLS, "speed" to 30, settleMs = SPEED_SETTLE_MS)
    }

    private fun park() {
        step("speed 0", CONTROLS, "speed" to 0, settleMs = SPEED_SETTLE_MS)
        step("gear Park", GEAR, "gear" to "Park")
    }

    // ---- screen ----

    private fun watchLifecycle() {
        val callback = ActivityLifecycleCallback { a, stage -> log.record("lifecycle", "${a.javaClass.simpleName}:$stage") }
        lifecycle.addLifecycleCallback(callback)
        onCleanup { lifecycle.removeLifecycleCallback(callback) }
        onCleanup { instrumentation.runOnMainSync { Stage.values().forEach { s -> lifecycle.getActivitiesInStage(s).forEach(Activity::finish) } } }
    }

    /** Async start (startActivitySync would hang if the launch is blocked), then snapshot. */
    private fun launch(cls: Class<out Activity>, label: String) {
        val sent = now()
        context.startActivity(Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Thread.sleep(LAUNCH_SETTLE_MS)
        val resumed = log.events("lifecycle", sent).firstOrNull { it.value == "${cls.simpleName}:RESUMED" }
        log.note("launch.$label", resumed?.let { "RESUMED after ${SignalLog.ms(it.tNs - sent)} ms" }
            ?: "not RESUMED within $LAUNCH_SETTLE_MS ms")
        snapshot(label)
    }

    /** Resumed activities (system view), task tops/visibility, in-process lifecycle stages, screenshot. */
    private fun snapshot(label: String) {
        val resumed = shell("dumpsys activity activities").lines().filter { RESUMED_LINE.containsMatchIn(it) }.map(String::trim).distinct()
        val tasks = shell("cmd activity stack list").lines().filter { "taskId=" in it }.map(String::trim)
        val inProcess = mutableListOf<String>()
        instrumentation.runOnMainSync {
            Stage.values().forEach { s -> lifecycle.getActivitiesInStage(s).forEach { inProcess += "${it.javaClass.simpleName}:$s" } }
        }
        log.note("screen.$label", JSONObject().put("resumed", JSONArray(resumed)).put("tasks", JSONArray(tasks))
            .put("inProcess", JSONArray(inProcess)).put("visibleWindows", windows(visibleOnly = true)))
        screenshot(label)
    }

    /** `dumpsys window windows` headers (matching [re] if given) with their isVisible flag. */
    private fun windows(re: Regex? = null, visibleOnly: Boolean = false): JSONArray {
        val blocks = shell("dumpsys window windows").split(Regex("\n(?=  Window #)")).drop(1)
        return JSONArray(blocks.mapNotNull { block ->
            val header = block.lineSequence().first().trim()
            val visible = "isVisible=true" in block
            if ((re != null && !re.containsMatchIn(header)) || (visibleOnly && !visible)) null else "$header visible=$visible"
        })
    }

    private fun screenshot(label: String) {
        val full = instrumentation.uiAutomation.takeScreenshot() ?: return log.note("screenshot.$label", "null")
        val half = Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
        File(context.cacheDir, "ux-$label.png").outputStream().use { half.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** LENGTH_SHORT toast, sampled at 0.3 s and 1.3 s, then waits it out so the next toast is not queued behind it. */
    private fun toast(label: String) {
        instrumentation.runOnMainSync { Toast.makeText(context, "토스트 probe: 3점 감점되었습니다", Toast.LENGTH_SHORT).show() }
        for ((waitMs, at) in listOf(300L to "0.3s", 1_000L to "1.3s")) {
            Thread.sleep(waitMs)
            log.note("toast.$label.$at", windows(TOAST_WINDOW))
            screenshot("toast-$label-$at")
        }
        Thread.sleep(TOAST_GAP_MS)
    }

    private fun headsUp(nm: NotificationManager, label: String, category: String?) {
        nm.notify(HUN_ID, Notification.Builder(context, HUN_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(category).setContentTitle("감점 알림 probe").setContentText("3점 감점되었습니다").build())
        Thread.sleep(HUN_WAIT_MS)
        log.note("headsUp.$label", windows(HUN_WINDOW))
        screenshot("hun-$label")
        nm.cancel(HUN_ID)
        Thread.sleep(HUN_WAIT_MS)
    }

    // ---- audio ----

    /** Request MAY_DUCK focus per usage, note the result, abandon. */
    private fun focusSweep(label: String) {
        for ((name, usage) in USAGES) {
            requestFocus("$label.$name", usage)?.let { Thread.sleep(FOCUS_HOLD_MS); audio.abandonAudioFocusRequest(it) }
        }
    }

    /** Notes focus.<key> = GRANTED/FAILED/DELAYED or the exception; returns the request to abandon unless it failed. */
    private fun requestFocus(key: String, usage: Int, gain: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK): AudioFocusRequest? {
        val result = runCatching {
            val request = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes(usage))
                .setOnAudioFocusChangeListener { log.record("focusChange.$key", FOCUS_CHANGES[it] ?: it.toString()) }.build()
            request to audio.requestAudioFocus(request)
        }
        log.note("focus.$key", result.fold({ GRANTS[it.second] ?: it.second.toString() }, { it.toString() }))
        return result.getOrNull()?.takeIf { it.second != AudioManager.AUDIOFOCUS_REQUEST_FAILED }?.first
    }

    /** USAGE_ANNOUNCEMENT is @SystemApi: public setUsage() rejects it, so the hidden setSystemUsage() is tried too. */
    private fun attributes(usage: Int): AudioAttributes {
        val builder = AudioAttributes.Builder().setContentType(CONTENT_TYPES[usage] ?: AudioAttributes.CONTENT_TYPE_SPEECH)
        if (usage != USAGE_ANNOUNCEMENT) return builder.setUsage(usage).build()
        runCatching { builder.setUsage(usage) }.onFailure { log.note("announcement.setUsage", it.toString()) }.onSuccess { return it.build() }
        AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Int::class.javaPrimitiveType).invoke(builder, usage)
        return builder.build()
    }

    private fun playPrompt(label: String) {
        val usage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        val request = requestFocus(label, usage) ?: return
        val pcm = tone(PROMPT_HZ, PROMPT_MS, PRE_ROLL_MS)
        val track = staticTrack(attributes(usage), pcm)
        try {
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = log.record("$label.marker", "reached")
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            }, Handler(Looper.getMainLooper()))
            val start = now()
            track.play()
            val dump = thread { Thread.sleep(DUMP_DELAY_MS); dumpAudio("$label-playing") }
            while (track.playbackHeadPosition < pcm.size && now() - start < PLAY_TIMEOUT_NS) {
                log.record("$label.head", track.playbackHeadPosition.toString())
                Thread.sleep(POLL_MS)
            }
            val device = track.routedDevice
            log.note("$label.play", "frames=${pcm.size} head=${track.playbackHeadPosition} after ${SignalLog.ms(now() - start)} ms " +
                "playState=${track.playState} routed=${device?.type}/${device?.address}")
            dump.join()
            Thread.sleep(POLL_MS) // let onMarkerReached land
        } finally {
            track.release()
            audio.abandonAudioFocusRequest(request)
        }
        dumpAudio("$label-after")
    }

    /** In-app "music": GAIN focus + a looping USAGE_MEDIA tone while [block] runs; focusChange.media records its losses. */
    private fun withMediaPlaying(block: () -> Unit) {
        val request = requestFocus("media", AudioAttributes.USAGE_MEDIA, AudioManager.AUDIOFOCUS_GAIN)
        val pcm = tone(MEDIA_HZ, MEDIA_LOOP_MS, 0)
        val track = staticTrack(attributes(AudioAttributes.USAGE_MEDIA), pcm).apply { setLoopPoints(0, pcm.size, -1); play() }
        try {
            Thread.sleep(MEDIA_LEAD_MS)
            block()
            log.note("media.playStateAfter", track.playState)
        } finally {
            track.release()
            request?.let(audio::abandonAudioFocusRequest)
        }
    }

    private fun staticTrack(attrs: AudioAttributes, pcm: ShortArray) = AudioTrack.Builder()
        .setAudioAttributes(attrs)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        .apply { write(pcm, 0, pcm.size) }

    /** Silence for [leadMs] (Pleos pre-roll guide: 150–200 ms in the data), then a sine at [hz] for [toneMs]. */
    private fun tone(hz: Int, toneMs: Int, leadMs: Int): ShortArray {
        val lead = SAMPLE_RATE * leadMs / 1000
        return ShortArray(lead + SAMPLE_RATE * toneMs / 1000) { i ->
            if (i < lead) 0 else (sin(2 * PI * hz * (i - lead) / SAMPLE_RATE) * AMPLITUDE).toInt().toShort()
        }
    }

    /** Saves full dumpsys audio / CarAudioService to cache and notes our players, ducked players, zone-0 holders, our focus events. */
    private fun dumpAudio(label: String) {
        val audioDump = shell("dumpsys audio")
        val carDump = shell("dumpsys car_service --services CarAudioService")
        File(context.cacheDir, "ux-$label-audio.txt").writeText(audioDump)
        File(context.cacheDir, "ux-$label-caraudio.txt").writeText(carDump)
        val uid = "u/pid:${Process.myUid()}/"
        log.note("audio.$label", JSONObject()
            .put("ourPlayers", JSONArray(audioDump.lines().filter { uid in it }.map(String::trim)))
            .put("ducked", between(audioDump, "ducked players piids:", "faded out"))
            .put("zone0Holders", between(carDump, "Current Focus Holders:", "Queued Delayed Focus"))
            .put("ourFocusEvents", JSONArray(carDump.lines().filter { "kyusun" in it }.map(String::trim).takeLast(MAX_LINES))))
    }

    private fun between(text: String, from: String, to: String) =
        JSONArray(text.substringAfter(from, "").substringBefore(to).lines().map(String::trim).filter(String::isNotEmpty))

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private companion object {
        /** AudioAttributes.USAGE_ANNOUNCEMENT (@SystemApi): SYSTEM_USAGE_OFFSET 1000 + 3. */
        const val USAGE_ANNOUNCEMENT = 1003
        val USAGES = linkedMapOf(
            "NAV_GUIDANCE" to AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            "ANNOUNCEMENT" to USAGE_ANNOUNCEMENT,
            "ASSISTANT" to AudioAttributes.USAGE_ASSISTANT,
            "NOTIFICATION" to AudioAttributes.USAGE_NOTIFICATION,
            "MEDIA" to AudioAttributes.USAGE_MEDIA,
        )
        val CONTENT_TYPES = mapOf(
            AudioAttributes.USAGE_NOTIFICATION to AudioAttributes.CONTENT_TYPE_SONIFICATION,
            AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_MUSIC,
        )
        val GRANTS = mapOf(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED to "GRANTED",
            AudioManager.AUDIOFOCUS_REQUEST_FAILED to "FAILED",
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED to "DELAYED",
        )
        val FOCUS_CHANGES = mapOf(
            AudioManager.AUDIOFOCUS_GAIN to "GAIN",
            AudioManager.AUDIOFOCUS_LOSS to "LOSS",
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to "LOSS_TRANSIENT",
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK to "LOSS_TRANSIENT_CAN_DUCK",
        )
        val UX_FLAGS = linkedMapOf(
            CarUxRestrictions.UX_RESTRICTIONS_NO_DIALPAD to "NO_DIALPAD",
            CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING to "NO_FILTERING",
            CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH to "LIMIT_STRING_LENGTH",
            CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD to "NO_KEYBOARD",
            CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO to "NO_VIDEO",
            CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT to "LIMIT_CONTENT",
            CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP to "NO_SETUP",
            CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE to "NO_TEXT_MESSAGE",
            CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION to "NO_VOICE_TRANSCRIPTION",
        )
        val RESUMED_LINE = Regex("Resumed(Activity)?[:=]")
        const val HUN_CHANNEL = "probe_hun"
        const val HUN_ID = 4201
        const val SAMPLE_RATE = 48_000
        const val AMPLITUDE = 0.25 * Short.MAX_VALUE
        const val PROMPT_HZ = 880
        const val PROMPT_MS = 1_000
        const val PRE_ROLL_MS = 200
        const val MEDIA_HZ = 330
        const val MEDIA_LOOP_MS = 1_000
        const val MEDIA_LEAD_MS = 800L
        const val DUMP_DELAY_MS = 400L
        const val POLL_MS = 100L
        const val PLAY_TIMEOUT_NS = 4_000_000_000L
        const val FOCUS_HOLD_MS = 150L
        const val LAUNCH_SETTLE_MS = 2_500L
        const val SPEED_SETTLE_MS = 2_500L
        val TOAST_WINDOW = Regex("(?i)toast")
        val HUN_WINDOW = Regex("(?i)heads|notification")
        const val TOAST_GAP_MS = 1_500L
        const val HUN_WAIT_MS = 1_200L
        const val MAX_LINES = 40
    }
}
