package com.scott.memento.domain

import org.junit.Assert.*
import org.junit.Test

class LanguageCapabilitiesTest {
    private val japanese=LanguageSpec("ja","测试日语","p7/ja.db","ja-JP",setOf("zh-Hans","en","de"),"p7-ja")
    @Test fun explicitFixtureRegistryDoesNotChangeFormalChoices() {
        val scoped=LanguageCapabilities(listOf(japanese))
        assertEquals("ja",scoped.requireTargetId("ja-JP"));assertEquals("de",scoped.requireLearningId("ja","de-DE"))
        assertEquals(listOf("fr","es"),LanguageRegistry.formal.languages.map{it.id})
        assertThrows(IllegalArgumentException::class.java){LanguageRegistry.requireTargetId("ja")}
        assertThrows(IllegalArgumentException::class.java){Selection("ja","en")}
        assertEquals("ja",Selection("ja","en",scoped).target)
        assertEquals("ja:stable:1",LearningIdentity.of("ja","ja:stable:1",scoped).lexemeUid)
    }
    @Test fun englishCannotBeRegisteredOrRestoredInAnyScope() {
        for(tag in listOf("en","en-US","EN","en_GB","eng","eng-Latn-GB")) {
            assertThrows(IllegalArgumentException::class.java){LanguageCapabilities(listOf(japanese.copy(id=tag,locale=tag)))}
            assertThrows(IllegalArgumentException::class.java){LanguageCapabilities(listOf(japanese)).requireTargetId(tag)}
        }
        val scoped=LanguageCapabilities(listOf(japanese))
        assertThrows(IllegalArgumentException::class.java){LearningIdentity.of("ja","ja:stable:1",scoped).copy(targetLanguage="en")}
    }
    @Test fun registrationRejectsNoncanonicalPairsMismatchedLocaleAndDuplicateIdentity() {
        for(spec in listOf(japanese.copy(id="JA"),japanese.copy(locale="ko-KR"),japanese.copy(learningLanguages=setOf("ja")),japanese.copy(learningLanguages=setOf("en-US")),japanese.copy(asset="../formal.db")))
            assertThrows(IllegalArgumentException::class.java){LanguageCapabilities(listOf(spec))}
        assertThrows(IllegalArgumentException::class.java){LanguageCapabilities(listOf(japanese,japanese))}
    }
    @Test fun nonLatinSearchKeepsDistinctKanaHangulAndVowelMarks() {
        for((left,right) in listOf("か" to "が","가" to "각","क" to "कि","مدرس" to "مُدَرِّس"))assertNotEquals(SearchKey.normalize(left),SearchKey.normalize(right))
        assertEquals(SearchKey.normalize("が"),SearchKey.normalize("か\u3099"))
        assertEquals(SearchKey.normalize("ガ"),SearchKey.normalize("ｶﾞ"))
        assertEquals(SearchKey.normalize("한글"),SearchKey.normalize("한글"))
    }
    @Test fun offlineVoiceSelectionSupportsJapaneseAndKoreanWithoutFrenchSpanishBranches() {
        val voices=listOf(VoiceCandidate("ja",java.util.Locale.JAPAN,false,true,200),VoiceCandidate("ko",java.util.Locale.KOREA,false,true,200),VoiceCandidate("online",java.util.Locale.JAPAN,true,true,500))
        assertEquals("ja",VoiceSelection.choose(java.util.Locale.JAPAN,voices)!!.name)
        assertEquals("ko",VoiceSelection.choose(java.util.Locale.KOREA,voices)!!.name)
        assertNull(VoiceSelection.choose(java.util.Locale.FRANCE,voices))
    }
}
