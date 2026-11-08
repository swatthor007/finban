package com.yourteam.guard.harness.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yourteam.guard.harness.calls.CallStateTracker
import com.yourteam.guard.harness.log.EventLog
import com.yourteam.guard.harness.log.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Tamil TTS availability check + playback, and bundled-clip playback from assets/. */
object TtsManager {
    const val SAMPLE_TA = "உங்கள் பாதுகாப்புக்காக, இந்த அழைப்பாளரை நம்ப வேண்டாம். வங்கி விவரங்களை யாரிடமும் சொல்ல வேண்டாம்."
    val status = MutableStateFlow("not checked")
    val tamilOk = MutableStateFlow(false)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var mp: MediaPlayer? = null

    fun check(ctx: Context) {
        if (tts != null) return
        status.value = "initialising engine…"
        tts = TextToSpeech(ctx.applicationContext) { code ->
            if (code != TextToSpeech.SUCCESS) { status.value = "engine init FAILED ($code)"; EventLog.log(EventType.TTS, "init", "TTS init failed code=$code"); return@TextToSpeech }
            ready = true
            val t = tts!!
            val avail = t.isLanguageAvailable(Locale("ta", "IN"))
            val name = when (avail) {
                TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE"; TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE"
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE"; TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA (voice data not installed)"
                TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"; else -> "?$avail"
            }
            tamilOk.value = avail >= TextToSpeech.LANG_AVAILABLE
            status.value = "ta-IN: $name; engine=${t.defaultEngine}"
            EventLog.log(EventType.TTS, "check", "ta-IN availability=$name engine=${t.defaultEngine} voices=${try { t.voices?.count { it.locale.language == "ta" } } catch (_: Throwable) { null }}")
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { EventLog.log(EventType.TTS, "speak", "started") }
                override fun onDone(id: String?) { EventLog.log(EventType.TTS, "speak", "done") }
                @Deprecated("") override fun onError(id: String?) { EventLog.log(EventType.TTS, "speak-error", "utterance error") }
            })
        }
    }

    fun speakSample(ctx: Context) {
        check(ctx)
        val t = tts
        if (!ready || t == null) { EventLog.log(EventType.TTS, "speak", "engine not ready yet, try again"); return }
        val r = t.setLanguage(Locale("ta", "IN"))
        if (r < TextToSpeech.LANG_AVAILABLE) { EventLog.log(EventType.TTS, "speak-error", "setLanguage(ta-IN) returned $r; not speaking"); return }
        EventLog.log(EventType.TTS, "speak", "playing Tamil sample (audioMode=${CallStateTracker.modeName(CallStateTracker.audioMode)} cellular=${CallStateTracker.stateName(CallStateTracker.cellularState)})")
        t.speak(SAMPLE_TA, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    fun playAsset(ctx: Context, name: String = "sample_ta.mp3") {
        try {
            mp?.release()
            val am = ctx.getSystemService(AudioManager::class.java)
            ctx.assets.openFd(name).use { fd ->
                mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setOnCompletionListener { EventLog.log(EventType.TTS, "asset", "$name finished") }
                    setOnErrorListener { _, w, e -> EventLog.log(EventType.TTS, "asset-error", "$name error what=$w extra=$e"); true }
                    prepare(); start()
                }
            }
            EventLog.log(EventType.TTS, "asset", "playing $name (musicVol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, audioMode=${CallStateTracker.modeName(CallStateTracker.audioMode)})")
        } catch (t: Throwable) { EventLog.log(EventType.TTS, "asset-error", "$name: $t") }
    }
}
