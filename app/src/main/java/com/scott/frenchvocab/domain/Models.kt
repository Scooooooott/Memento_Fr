package com.scott.frenchvocab.domain

data class Sense(val english: String, val spanish: String, val chinese: String = "")
data class Example(val french: String, val english: String, val spanish: String, val chinese: String = "")
data class WordForm(val label: String, val value: String)
data class Conjugation(val tense: String, val pronoun: String, val form: String)
data class Lexeme(
    val uid: String, val lemma: String, val ipa: String, val partOfSpeech: String,
    val level: String, val gender: String = "", val verbGroup: String = "",
    val auxiliary: String = "", val senses: List<Sense> = emptyList(),
    val examples: List<Example> = emptyList(), val forms: List<WordForm> = emptyList(),
    val conjugations: List<Conjugation> = emptyList(), val audioAsset: String? = null,
)
data class VocabularyBook(val id: String, val title: String, val description: String, val lexemeUids: List<String>)
enum class Rating { AGAIN, HARD, GOOD, EASY }
enum class AutoPlayMode { NEVER, NEW_ONLY, ALL }
data class UserSettings(
    val dailyNewLimit: Int = 10, val bookId: String = "essential-fr",
    val autoPlay: AutoPlayMode = AutoPlayMode.NEW_ONLY, val ttsFallback: Boolean = true,
    val showIpa: Boolean = true, val showEnglish: Boolean = true, val showSpanish: Boolean = true,
    val showChinese: Boolean = true,
)
data class LearningCard(
    val lexemeUid: String, val stability: Double = 0.0, val difficulty: Double = 0.0,
    val dueAt: Long = 0L, val lastReviewAt: Long? = null, val repetitions: Int = 0,
    val lapses: Int = 0, val state: String = "NEW",
)
data class SessionItem(val lexemeUid: String, val isNew: Boolean, val rating: Rating? = null)
data class StudySession(
    val id: Long, val items: List<SessionItem>, val position: Int,
    val answerRevealed: Boolean, val startedAt: Long, val completedAt: Long? = null,
    val settings: UserSettings = UserSettings(),
) {
    val current: SessionItem? get() = items.getOrNull(position)
    val completed: Boolean get() = completedAt != null
}
data class DailyCount(val date: String, val reviews: Int)
data class StudyStats(
    val dueCount: Int = 0, val newCount: Int = 0, val learnedCount: Int = 0,
    val todayReviews: Int = 0, val todayNew: Int = 0, val streakDays: Int = 0,
    val lastSevenDays: List<DailyCount> = emptyList(),
)
data class AppSnapshot(
    val settings: UserSettings, val session: StudySession?, val stats: StudyStats,
    val cards: Map<String, LearningCard> = emptyMap(), val favorites: Set<String> = emptySet(),
    val latestCompletedSession: StudySession? = null,
)
