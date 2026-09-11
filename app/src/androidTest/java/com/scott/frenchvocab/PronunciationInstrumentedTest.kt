package com.scott.frenchvocab

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.domain.Lexeme
import com.scott.frenchvocab.domain.audio.PronunciationPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PronunciationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val testAssets = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getAssets(): AssetManager = instrumentation.context.assets
    }
    private val word = Lexeme("qa", "bonjour", "/bɔ̃.ʒuʁ/", "interjection", "A1")

    @Test fun packagedAudioPlaysWithoutTtsFallback() {
        val result = play(word.copy(audioAsset = "qa-tone.wav"), false)
        assertNull("Bundled local asset must play without a TTS engine", result)
    }

    @Test fun missingOrInvalidRecordingReportsUnavailableWhenFallbackDisabled() {
        assertTrue(play(word, false)!!.contains("暂无本地录音"))
        assertTrue(play(word.copy(audioAsset = "missing.wav"), false)!!.contains("暂无本地录音"))
    }

    @Test fun fallbackEitherUsesInstalledFranceVoiceOrExplainsMissingVoice() {
        val result = play(word, true)
        // This AOSP emulator has no voice package. On a configured device playback is also valid.
        assertTrue(result == null || result.contains("fr-FR") || result.contains("语音"))
    }

    private fun play(lexeme: Lexeme, tts: Boolean): String? {
        val done = CountDownLatch(1)
        val message = AtomicReference<String?>("no callback")
        val player = PronunciationPlayer(testAssets)
        try {
            player.speak(lexeme, tts) { message.set(it); done.countDown() }
            assertTrue("Audio must report playback or a useful error", done.await(12, TimeUnit.SECONDS))
            return message.get()
        } finally { player.close(); instrumentation.waitForIdleSync() }
    }
}
