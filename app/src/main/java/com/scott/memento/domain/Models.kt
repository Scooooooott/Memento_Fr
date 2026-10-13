package com.scott.memento.domain

enum class Rating { AGAIN, HARD, GOOD, EASY }
data class LearningCard(
    val lexemeUid: String, val stability: Double = 0.0, val difficulty: Double = 0.0,
    val dueAt: Long = 0L, val lastReviewAt: Long? = null, val repetitions: Int = 0,
    val lapses: Int = 0, val state: String = "NEW",
)
data class LanguageSpec(val id: String, val label: String, val asset: String, val locale: String,
                        val learningLanguages: Set<String>, val defaultBook: String)
object Languages {
    val available = listOf(
        LanguageSpec("fr", "法语", "content/fr.db", "fr-FR",
            linkedSetOf("zh-Hans", "en", "es"), "flelex-a1-high-1000"),
        LanguageSpec("es", "西班牙语", "content/es.db", "es-ES",
            linkedSetOf("zh-Hans", "en", "fr"), "elelex-a1-top1200-es"),
    )
    fun get(id: String) = available.single { it.id == LanguageRegistry.requireTargetId(id) }
    fun label(id: String) = when(id) { "zh-Hans" -> "中文"; "en" -> "英语"; "es" -> "西班牙语"; "fr" -> "法语"; else -> id }
}
data class Book(val id: String, val title: String, val description: String, val count: Int,val levelSystem:String="",val level:String="")
data class WordSummary(val uid: String, val lemma: String, val partOfSpeech: String, val level: String,
                       val translations: Map<String,String>,val levelSystem:String="CEFR")
data class Example(val id: String, val source: String, val translations: Map<String,String>)
data class Sense(val id: String, val translations: Map<String,String>, val examples: List<Example>)
data class Detail(val summary: WordSummary, val ipa: String, val gender: String,
                  val senses: List<Sense>, val forms: List<Pair<String,String>>, val conjugations: List<Pair<String,String>>,
                  val readings:List<Reading> = emptyList(),val grammarFeatures:Map<String,String> = emptyMap(),
                  val paradigms:List<Paradigm> = emptyList(),val formDetails:List<WordForm> = emptyList())

data class Reading(val locale:String,val type:String,val text:String,val audioAsset:String?)
data class ParadigmForm(val subjectKey:String,val pronoun:String,val form:String,val order:Int)
data class Paradigm(val id:String,val mood:String,val tense:String,val label:String,val region:String,val note:String,
                    val order:Int,val forms:List<ParadigmForm>)
data class WordForm(val label:String,val form:String,val order:Int,val featuresJson:String)
data class WordPage(val words: List<WordSummary>, val total: Int, val hasMore: Boolean)
