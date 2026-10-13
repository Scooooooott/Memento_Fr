package com.scott.memento.ui.language

import com.scott.memento.domain.Detail
import com.scott.memento.domain.Paradigm
import com.scott.memento.domain.ParadigmForm
import com.scott.memento.domain.WordSummary
import org.junit.Assert.*
import org.junit.Test

class GrammarTableModelsTest {
    private fun word(target: String, paradigms: List<Paradigm>) = Detail(
        WordSummary("$target:fixture:verb:1", "fixture", "verb", "", emptyMap()),
        "", "", emptyList(), emptyList(), emptyList(), paradigms = paradigms,
    )
    private fun paradigm(
        id: String, mood: String = "", tense: String = "", label: String = tense,
        forms: List<ParadigmForm> = emptyList(), region: String = "", note: String = "", order: Int = 0,
    ) = Paradigm(id, mood, tense, label, region, note, order, forms)
    private fun form(key: String, pronoun: String, value: String, order: Int = 0) =
        ParadigmForm(key, pronoun, value, order)

    @Test fun frenchUsesExactKeysEvenWhenRowsAreOutOfOrderAndIncomplete() {
        val model = projectGrammarTable(word("fr", listOf(paradigm("p", forms = listOf(
            form("6", "ils / elles", "vont", 0), form("1", "je", "vais", 1),
            form("3", "il / elle / on", "va", 2),
        )))))
        assertEquals(GrammarStyle.FRENCH, model.style)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), model.columns.map { it.key })
        val row = model.rows.single()
        assertEquals("vais", row.cells.getValue("1").single().form)
        assertEquals("vont", row.cells.getValue("6").single().form)
        assertNull(row.cells["2"])
    }

    @Test fun frenchCopiesBothApostrophesAndCompoundForms() {
        val model = projectGrammarTable(word("fr", listOf(paradigm("p", forms = listOf(
            form("1", "j’", "ai parlé"), form("2", "tu", "as parlé"), form("6", "j'", "avais parlé"),
        )))))
        val cells = model.rows.single().cells
        assertEquals("j’ai parlé", cells.getValue("1").single().phrase)
        assertEquals("tu as parlé", cells.getValue("2").single().phrase)
        assertEquals("j'avais parlé", cells.getValue("6").single().phrase)
        assertEquals("j’", cells.getValue("1").single().pronoun)
    }

    @Test fun frenchUnknownSubjectRemainsVisibleInsteadOfBeingDiscarded() {
        val model = projectGrammarTable(word("fr", listOf(paradigm("p", forms = listOf(
            form("future-person", "iel", "parle"), form("1", "je", "parle"),
        )))))
        assertEquals("future-person", model.columns.last().key)
        assertTrue(model.columns.last().label.contains("iel"))
        assertEquals("future-person", model.rows.single().cells.getValue("future-person").single().subjectKey)
    }

    @Test fun spanishPresenteKeepsMoodIdentityRegionNoteAndEveryRow() {
        val note = "Una nota regional completa. ".repeat(100)
        val model = projectGrammarTable(word("es", listOf(
            paradigm("ind", "indicativo", "presente", "Presente · 现在时", listOf(form("yo", "yo", "soy")), order = 1),
            paradigm("sub", "subjuntivo", "presente", "Presente · 现在时", listOf(form("yo", "yo", "sea")),
                region = "América", note = note, order = 2),
        )))
        assertEquals(2, model.rows.size)
        assertEquals(listOf("indicativo", "subjuntivo"), model.rows.map { it.mood })
        assertEquals(listOf("presente", "presente"), model.rows.map { it.tense })
        assertEquals(listOf("ind", "sub"), model.rows.map { it.paradigmId })
        assertEquals("América", model.rows.last().region)
        assertEquals(note, model.rows.last().note)
    }

    @Test fun spanishSubjectsAreDynamicWithStableKnownAndUnknownOrdering() {
        val keys = listOf("z-new", "ellos", "vos", "yo", "usted", "tu", "a-new")
        val model = projectGrammarTable(word("es", listOf(paradigm("p",
            forms = keys.map { form(it, it, "f-$it") }))))
        assertEquals(listOf("yo", "tu", "vos", "usted", "ellos", "a-new", "z-new"), model.columns.map { it.key })
        assertFalse(model.columns.any { it.key == "nosotros" })
        assertEquals(keys.toSet(), model.rows.single().cells.keys)
    }

    @Test fun imperativeMissingYoIsNotInventedFromAnotherParadigm() {
        val model = projectGrammarTable(word("es", listOf(
            paradigm("present", "indicativo", forms = listOf(form("yo", "yo", "soy"), form("tu", "tú", "eres"))),
            paradigm("command", "imperativo", forms = listOf(form("tu", "tú", "sé"))),
        )))
        assertEquals(listOf("yo", "tu"), model.columns.map { it.key })
        assertNull(model.rows.last().cells["yo"])
        assertEquals("sé", model.rows.last().cells.getValue("tu").single().form)
    }

    @Test fun repeatedSubjectKeysRetainAllActualFormsAndPronouns() {
        val model = projectGrammarTable(word("es", listOf(paradigm("p", forms = listOf(
            form("tu", "tú", "hablas", 2), form("tu", "tú regional", "hablás", 1),
        )))))
        val entries = model.rows.single().cells.getValue("tu")
        assertEquals(listOf("hablás", "hablas"), entries.map { it.form })
        assertEquals(listOf("tú regional", "tú"), entries.map { it.pronoun })
    }

    @Test fun genericUsesRealNonPersonKeysAndPreservesParadigmMetadata() {
        for (target in listOf("ja", "ko")) {
            val model = projectGrammarTable(word(target, listOf(paradigm("polite", "politeness", "present", "丁寧 / 정중",
                listOf(form("polite-form", "", "fixture-form")), region = "fixture", note = "original note"))))
            assertEquals(GrammarStyle.GENERIC, model.style)
            assertEquals(listOf("polite-form"), model.columns.map { it.key })
            assertEquals("fixture-form", model.rows.single().cells.getValue("polite-form").single().form)
            assertEquals("politeness", model.rows.single().mood)
            assertEquals("original note", model.rows.single().note)
        }
    }

    @Test fun emptyParadigmsHaveNoFabricatedColumnsOrForms() {
        for (target in listOf("fr", "es", "ja")) {
            val model = projectGrammarTable(word(target, emptyList()))
            assertTrue(model.columns.isEmpty())
            assertTrue(model.rows.isEmpty())
        }
    }
}
