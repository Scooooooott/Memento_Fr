package com.scott.memento.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class VoiceSelectionTest {
    private fun voice(name:String,tag:String,network:Boolean=false,installed:Boolean=true,quality:Int=300)=VoiceCandidate(name,Locale.forLanguageTag(tag),network,installed,quality)
    @Test fun networkAndUninstalledVoicesNeverQualify() {
        assertNull(VoiceSelection.choose(Locale.FRANCE,listOf(voice("network","fr-FR",network=true),voice("missing","fr-FR",installed=false),voice("english","en-US"))))
    }
    @Test fun exactRegionPrecedesQualityThenInstalledSameLanguageCanFallback() {
        val france=voice("france","fr-FR",quality=100)
        val canada=voice("canada","fr-CA",quality=500)
        assertEquals(france,VoiceSelection.choose(Locale.FRANCE,listOf(canada,france)))
        assertEquals(canada,VoiceSelection.choose(Locale.FRANCE,listOf(canada)))
    }
    @Test fun switchToSpanishCannotReuseAFrenchVoice() {
        val spanish=voice("es","es-ES")
        assertEquals(spanish,VoiceSelection.choose(Locale.forLanguageTag("es-ES"),listOf(voice("fr","fr-FR"),spanish)))
        assertNull(VoiceSelection.choose(Locale.forLanguageTag("es-ES"),listOf(voice("fr","fr-FR"))))
    }
}
