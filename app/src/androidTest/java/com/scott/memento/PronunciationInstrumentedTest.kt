package com.scott.memento

import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PronunciationInstrumentedTest {
    @Test fun absentRecordingWithFallbackDisabledReportsAndStoppedInitializationCannotReportOldTarget() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PronunciationPlayer(context).use {player->
            val result=AtomicReference<SpeechStatus>();val latch=CountDownLatch(1)
            player.speakText("bonjour","fr-FR",allowTts=false){result.set(it);latch.countDown()}
            assertTrue(latch.await(3,TimeUnit.SECONDS));assertEquals(SpeechStatus.NO_RECORDING,result.get())
            val oldCallbacks=java.util.concurrent.atomic.AtomicInteger()
            // These commands enter the same main queue; stop precedes any deferred TTS initialization callback.
            player.speakText("bonjour","fr-FR"){oldCallbacks.incrementAndGet()};player.stop()
            val barrier=CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post{barrier.countDown()}
            assertTrue(barrier.await(3,TimeUnit.SECONDS));assertEquals(0,oldCallbacks.get())
        }
    }
    @Test fun probeActualInstalledOfflineVoicesWithoutClaimingPlaybackWhenUnavailable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val results=JSONArray()
        for((locale,text) in listOf("fr-FR" to "bonjour","es-ES" to "hola")) {
            PronunciationPlayer(context).use {player->
                val status=AtomicReference<SpeechStatus>();val latch=CountDownLatch(1)
                player.speakText(text,locale){status.set(it);latch.countDown()}
                assertTrue("TTS must finish initialization or report timeout",latch.await(12,TimeUnit.SECONDS))
                assertTrue(status.get() in setOf(SpeechStatus.OFFLINE_PLAYING,SpeechStatus.NO_OFFLINE_VOICE,SpeechStatus.NOT_READY))
                results.put(JSONObject().put("locale",locale).put("status",status.get().name).put("offline_playback_started",status.get()==SpeechStatus.OFFLINE_PLAYING))
            }
        }
        val out=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
        File(out,"p5-offline-voice-probe.json").writeText(JSONObject().put("voices",results).toString(2))
    }
}
