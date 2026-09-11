package com.scott.frenchvocab.data.user

import com.scott.frenchvocab.domain.LearningCard
import com.scott.frenchvocab.domain.SessionItem
import java.time.LocalDate
import kotlin.random.Random

/** Pure planning policy; the repository persists the resulting order exactly once. */
internal object SessionPlanner {
    fun remainingNew(limit: Int, alreadyReviewedToday: Int): Int =
        (limit.coerceAtLeast(0).toLong() - alreadyReviewedToday.coerceAtLeast(0).toLong()).coerceAtLeast(0).toInt()

    fun plan(allUids: List<String>, bookUids: List<String>, cards: Map<String, LearningCard>,
             dailyNewLimit: Int, todayNew: Int, now: Long, random: Random): List<SessionItem> {
        val validUids = allUids.toSet()
        val reviews = cards.values.filter { it.lexemeUid in validUids && it.lastReviewAt != null && it.dueAt <= now }
            .map { SessionItem(it.lexemeUid, false) }
        val candidates = bookUids.distinct().filter { it in validUids && cards[it]?.lastReviewAt == null }
        val newWords = candidates.take(minOf(candidates.size, remainingNew(dailyNewLimit, todayNew)))
            .map { SessionItem(it, true) }
        return (reviews + newWords).shuffled(random)
    }

    fun streak(today: LocalDate, studiedDays: Set<String>): Int {
        var day = if (today.toString() in studiedDays) today else today.minusDays(1)
        var count = 0
        while (day.toString() in studiedDays) { count++; day = day.minusDays(1) }
        return count
    }
}
