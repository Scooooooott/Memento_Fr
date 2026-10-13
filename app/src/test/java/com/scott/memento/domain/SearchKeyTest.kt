package com.scott.memento.domain

import org.junit.Assert.*
import org.junit.Test

class SearchKeyTest {
    @Test fun latinAccentsCompatibilityAndCaseShareThePackagedSearchContract() {
        for((input,expected) in listOf(" ÊTRE " to "etre","negociación" to "negociacion","ＡＢＣ" to "abc","ÉCOLE" to "ecole","100%_\\" to "100%_\\"))
            assertEquals(expected,SearchKey.normalize(input))
    }
    @Test fun nonLatinMarksAndSupplementaryCharactersArePreserved() {
        for(text in listOf("日本語","が","مُدَرِّس","😀中文")) assertEquals(text,SearchKey.normalize(text))
    }
}
