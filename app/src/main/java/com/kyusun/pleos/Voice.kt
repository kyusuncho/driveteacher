package com.kyusun.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Speaks with the Pleos TTS SDK on device (Mode.ON_DEVICE needs no Playground credentials; Android's own
 * TextToSpeech has no engine on Pleos). Main thread only.
 * Lines play one after another: a speak() while one is playing cuts it short (emulator, 10/30), so each waits for
 * the last one's end event, or for [GIVE_UP_MS] if that never comes. Lines said before the engine is ready wait too.
 * The speech service plays without audio focus (emulator, 10/30), so this holds transient focus that ducks media
 * while lines play, and skips a line when focus is refused (a call, say): the screen still shows it.
 * ponytail: no 0.15~0.2 s lead silence (Pleos audio guide): the SDK takes text, not audio; add it if the first
 * syllable gets clipped on a real car.
 */
class Voice(context: Context) : AutoCloseable {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(GUIDANCE)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = ended()
        override fun onError(type: ErrorType) = ended().also { Log.w(TAG, "TTS error: $type") }
        override fun onStop() = ended()
        override fun onUpdatedRms(rms: Double) = Unit
    }
    private val giveUp = Runnable {
        Log.w(TAG, "no end of speech after $GIVE_UP_MS ms; going on")
        next()
    }
    private val queue = ArrayDeque<String>()
    private var ready = false
    private var speaking = false
    private var closed = false

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    if (closed) return@post
                    tts.setLocale(Locale.KOREA)
                    tts.setAudioAttributes(GUIDANCE)
                    tts.addEventListener(events)
                    ready = true
                    next()
                }
            }

            override fun onFailure(type: ErrorType) {
                Log.e(TAG, "TTS init failed: $type; penalties are shown but not spoken")
            }
        })
    }

    fun say(text: String) {
        if (closed || text.isEmpty()) return
        queue += text
        if (ready && !speaking) next()
    }

    /** Callbacks come on the SDK's threads. */
    private fun ended() {
        main.post { if (!closed && speaking) next() }
    }

    /** Starts the next line that gets focus; gives focus back once none is left. */
    private fun next() {
        main.removeCallbacks(giveUp)
        while (true) {
            val text = queue.removeFirstOrNull() ?: break
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "audio focus refused; not spoken: '$text'")
                continue
            }
            // A failing speech service must not take the scoring screen down with it.
            val spoken = runCatching { tts.speak(text, true) }
            if (spoken.isSuccess) {
                speaking = true
                main.postDelayed(giveUp, GIVE_UP_MS)
                return
            }
            Log.e(TAG, "TTS speak failed: '$text'", spoken.exceptionOrNull())
        }
        speaking = false
        audio.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        closed = true
        main.removeCallbacks(giveUp)
        runCatching {
            tts.removeEventListener(events)
            tts.release()
        }.onFailure { Log.w(TAG, "TTS release failed", it) }
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val TAG = "Voice"

        /** Longer than a cold first sentence (~9 s on the emulator) plus a long line. */
        const val GIVE_UP_MS = 20_000L

        /** Spoken guidance over media, as navigation prompts are (PLAN 7절). */
        val GUIDANCE: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
