package com.scott.memento.domain

import org.junit.Assert.*
import org.junit.Test

class LanguageRegistryTest {
    @Test fun allEnglishTargetVariantsAreRejected() {
        for(tag in listOf("en","en-US","en-GB","EN-latn-AU","en_US","eng","eng-GB")) {
            assertThrows(IllegalArgumentException::class.java){LanguageRegistry.requireTargetId(tag)}
        }
    }
    @Test fun regionalFrenchAndSpanishKeepOneProgressIdentity() {
        assertEquals("fr",LanguageRegistry.requireTargetId("fr-FR"))
        assertEquals("es",LanguageRegistry.requireTargetId("es-MX"))
        assertEquals(LearningIdentity.of("fr","fr:être:verb:1"),LearningIdentity.of("fr-FR","fr:être:verb:1"))
    }
    @Test fun englishLearningLanguageIsAllowedButUnsupportedPairsAreRejected() {
        assertEquals("en",LanguageRegistry.requireLearningId("fr","en-US"))
        assertEquals("zh-Hans",LanguageRegistry.requireLearningId("es","zh-CN"))
        assertThrows(IllegalArgumentException::class.java){LanguageRegistry.requireLearningId("fr","fr")}
        assertThrows(IllegalArgumentException::class.java){LanguageRegistry.requireLearningId("es","zh-Hant")}
    }
    @Test fun malformedUnavailableAndCrossLanguageIdentitiesAreRejected() {
        for(tag in listOf(""," en ","en--US","de","ja","x-private")) {
            assertThrows(IllegalArgumentException::class.java){LanguageRegistry.requireTargetId(tag)}
        }
        assertThrows(IllegalArgumentException::class.java){LearningIdentity.of("es","fr:être:verb:1")}
        val identity=LearningIdentity.of("fr","fr:être:verb:1")
        assertThrows(IllegalArgumentException::class.java){identity.copy(targetLanguage="en")}
        assertThrows(IllegalArgumentException::class.java){identity.copy(lexemeUid="es:ser:verb:1")}
    }
    @Test fun reviewAllHasNoArtificialQuotaAndStandardRequiresPositiveTarget() {
        assertEquals(null,StudyPolicy(StudyMode.REVIEW_ALL,null).dailyTarget)
        assertThrows(IllegalArgumentException::class.java){StudyPolicy(StudyMode.REVIEW_ALL,20)}
        assertThrows(IllegalArgumentException::class.java){StudyPolicy(StudyMode.STANDARD,0)}
        assertEquals(Int.MAX_VALUE,StudyPolicy(StudyMode.STANDARD,Int.MAX_VALUE).dailyTarget)
    }
    @Test fun scoringRequestRequiresCompletePersistedIdentity() {
        val identity=LearningIdentity.of("fr","fr:être:verb:1")
        assertThrows(IllegalArgumentException::class.java){RatingRequest(identity,0,1,1,"request")}
        assertThrows(IllegalArgumentException::class.java){RatingRequest(identity,1,0,1,"request")}
        assertThrows(IllegalArgumentException::class.java){RatingRequest(identity,1,1,1,"")}
    }
}
