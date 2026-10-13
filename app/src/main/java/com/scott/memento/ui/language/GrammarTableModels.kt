package com.scott.memento.ui.language

import com.scott.memento.domain.Detail

/** Read-only UI projection. Keys and grammar values remain those of the verified package. */
enum class GrammarStyle { FRENCH, SPANISH, GENERIC }

data class GrammarColumn(val key: String, val label: String)

data class GrammarCell(val subjectKey: String, val pronoun: String, val form: String) {
    /** Apostrophes are copied from the package; no conjugation or elision is generated here. */
    val phrase: String
        get() = if (pronoun.isBlank()) form else pronoun +
            (if (pronoun.endsWith("’") || pronoun.endsWith("'")) "" else " ") + form
}

data class GrammarRow(
    val paradigmId: String,
    val header: String,
    val mood: String,
    val tense: String,
    val region: String,
    val note: String,
    // Lists retain even repeated subject keys from a future package instead of overwriting a form.
    val cells: Map<String, List<GrammarCell>>,
)

data class GrammarTableModel(
    val style: GrammarStyle,
    val columns: List<GrammarColumn>,
    val rows: List<GrammarRow>,
)

private val frenchColumns = listOf(
    GrammarColumn("1", "je / j’"), GrammarColumn("2", "tu"),
    GrammarColumn("3", "il / elle / on"), GrammarColumn("4", "nous"),
    GrammarColumn("5", "vous"), GrammarColumn("6", "ils / elles"),
)

private val spanishColumns = listOf(
    GrammarColumn("yo", "yo"), GrammarColumn("tu", "tú"), GrammarColumn("vos", "vos"),
    GrammarColumn("usted", "usted"), GrammarColumn("el", "él / ella / usted"),
    GrammarColumn("nosotros", "nosotros / nosotras"),
    GrammarColumn("vosotros", "vosotros / vosotras"), GrammarColumn("ustedes", "ustedes"),
    GrammarColumn("ellos", "ellos / ellas / ustedes"),
)

fun projectGrammarTable(word: Detail): GrammarTableModel {
    val style = when (word.summary.uid.substringBefore(':')) {
        "fr" -> GrammarStyle.FRENCH
        "es" -> GrammarStyle.SPANISH
        else -> GrammarStyle.GENERIC
    }
    val paradigms = word.paradigms.sortedBy { it.order }
    val forms = paradigms.flatMap { it.forms.sortedBy { form -> form.order } }
    val keys = forms.map { it.subjectKey }.distinct()
    fun actualColumn(key: String): GrammarColumn {
        val pronouns = forms.filter { it.subjectKey == key }.map { it.pronoun }
            .filter(String::isNotBlank).distinct()
        val label = pronouns.joinToString(" / ")
        return GrammarColumn(key, if (label.isBlank() || label == key) key else "$label ($key)")
    }
    val columns = when (style) {
        GrammarStyle.FRENCH -> if (paradigms.isEmpty()) emptyList() else frenchColumns +
            keys.filter { key -> frenchColumns.none { it.key == key } }.sorted().map(::actualColumn)
        GrammarStyle.SPANISH -> spanishColumns.filter { it.key in keys } +
            keys.filter { key -> spanishColumns.none { it.key == key } }.sorted().map(::actualColumn)
        GrammarStyle.GENERIC -> keys.map(::actualColumn)
    }
    val rows = paradigms.map { paradigm ->
        GrammarRow(
            paradigm.id, paradigm.label.ifBlank { paradigm.tense.ifBlank { paradigm.id } },
            paradigm.mood, paradigm.tense, paradigm.region, paradigm.note,
            paradigm.forms.sortedBy { it.order }.groupBy({ it.subjectKey }) {
                GrammarCell(it.subjectKey, it.pronoun, it.form)
            },
        )
    }
    return GrammarTableModel(style, columns, rows)
}
