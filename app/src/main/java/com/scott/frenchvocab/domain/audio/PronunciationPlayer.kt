package com.scott.frenchvocab.domain.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.scott.frenchvocab.domain.Lexeme
import java.util.Locale

/** Local recordings first. Only installed, non-network France French TTS voices are eligible. */
class PronunciationPlayer(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var initialized: Boolean? = null
    private var pending: (() -> Unit)? = null
    private var generation = 0L
    private var closed = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun speak(word: Lexeme, allowTts: Boolean, onResult: (String?) -> Unit) {
        main.post {
            if (closed) return@post
            stopCurrent()
            val request = generation
            fun report(message: String?) {
                if (!closed && generation == request) onResult(message)
            }
            fun fallback() {
                if (!allowTts) {
                    report("此词暂无本地录音；可在设置中启用离线法语语音。")
                    return
                }
                speakTts(word.lemma, request, ::report)
            }
            val asset = word.audioAsset
            if (asset.isNullOrBlank()) {
                fallback()
            } else {
                try {
                    val player = MediaPlayer()
                    media = player
                    player.setAudioAttributes(attributes)
                    context.assets.openFd(asset).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    player.setOnPreparedListener {
                        if (!closed && generation == request) {
                            it.start()
                            report(null)
                        }
                    }
                    player.setOnCompletionListener { if (media === it) { it.release(); media = null } }
                    player.setOnErrorListener { failed, _, _ ->
                        if (media === failed) { failed.release(); media = null }
                        if (generation == request && !closed) fallback()
                        true
                    }
                    player.prepareAsync()
                } catch (_: Exception) {
                    media?.release()
                    media = null
                    fallback()
                }
            }
        }
    }

    private fun speakTts(text: String, request: Long, report: (String?) -> Unit) {
        if (generation != request || closed) return
        when (initialized) {
            false -> report("未找到离线 fr-FR 语音。请在 Android 系统语音设置中安装法语（法国）语音包。")
            true -> {
                val engine = tts ?: return
                val voice = engine.voices?.filter {
                    it.locale.language == Locale.FRANCE.language && it.locale.country == Locale.FRANCE.country &&
                        !it.isNetworkConnectionRequired &&
                        !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }?.maxByOrNull { it.quality }
                if (voice == null || engine.setVoice(voice) == TextToSpeech.ERROR) {
                    report("未安装离线 fr-FR 语音；请在系统设置下载法语（法国）语音包后重试。")
                    return
                }
                engine.setAudioAttributes(attributes)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { main.post { report(null) } }
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Android callback")
                    override fun onError(utteranceId: String?) { main.post { report("离线法语发音失败，请检查系统语音包。") } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { report("离线法语发音失败，请检查系统语音包。") } }
                })
                if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fr-$request") == TextToSpeech.ERROR) {
                    report("系统语音无法播放此词，请检查离线法语语音包。")
                }
            }
            null -> {
                pending = { speakTts(text, request, report) }
                if (tts == null) {
                    tts = TextToSpeech(context) { status ->
                        main.post {
                            if (closed) return@post
                            initialized = status == TextToSpeech.SUCCESS
                            val next = pending
                            pending = null
                            next?.invoke()
                        }
                    }
                }
                main.postDelayed({
                    if (!closed && generation == request && initialized == null) {
                        pending = null
                        report("系统语音尚未就绪，请稍后重试。")
                    }
                }, 8_000)
            }
        }
    }

    fun stop() { main.post { stopCurrent() } }
    private fun stopCurrent() {
        generation++
        pending = null
        media?.release()
        media = null
        tts?.stop()
    }

    fun close() {
        main.post {
            closed = true
            stopCurrent()
            tts?.shutdown()
            tts = null
        }
    }
}
