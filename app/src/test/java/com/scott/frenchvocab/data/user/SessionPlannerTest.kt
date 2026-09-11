package com.scott.frenchvocab.data.user

import com.scott.frenchvocab.domain.LearningCard
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SessionPlannerTest {
    @Test fun dueWordsFromEveryBookAreCombinedWithOnlyCurrentBookNewWords() {
        val cards = listOf(
            LearningCard("outside-book", dueAt = 10, lastReviewAt = 1),
            LearningCard("future", dueAt = 11, lastReviewAt = 1),
            LearningCard("removed-content", dueAt = 1, lastReviewAt = 1),
        ).associateBy { it.lexemeUid }
        val plan = SessionPlanner.plan(listOf("outside-book", "future", "new", "other-new"), listOf("new", "new"), cards, 10, 0, 10, Random(1))
        assertEquals(setOf("outside-book", "new"), plan.map { it.lexemeUid }.toSet())
        assertEquals(1, plan.count { it.isNew })
        assertEquals(2, plan.size)
    }

    @Test fun repeatedSessionsShareOneDailyAllowance() {
        assertEquals(3, SessionPlanner.remainingNew(10, 7))
        assertEquals(0, SessionPlanner.remainingNew(10, 10))
        assertEquals(0, SessionPlanner.remainingNew(3, 7))
        assertEquals(0, SessionPlanner.remainingNew(0, 0))
        assertEquals(150, SessionPlanner.remainingNew(150, 0))
        val plan = SessionPlanner.plan((1..20).map(Int::toString), (1..20).map(Int::toString), emptyMap(), 10, 7, 0, Random(1))
        assertEquals(3, plan.size)
    }

    @Test fun quantitiesAboveOneHundredArePreservedAndIntBoundariesDoNotOverflow() {
        assertEquals(Int.MAX_VALUE, SessionPlanner.remainingNew(Int.MAX_VALUE, 0))
        assertEquals(Int.MAX_VALUE - 7, SessionPlanner.remainingNew(Int.MAX_VALUE, 7))
        assertEquals(0, SessionPlanner.remainingNew(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(0, SessionPlanner.remainingNew(1, Int.MAX_VALUE))
        assertEquals(0, SessionPlanner.remainingNew(Int.MIN_VALUE, 0))
        assertEquals(Int.MAX_VALUE, SessionPlanner.remainingNew(Int.MAX_VALUE, Int.MIN_VALUE))
        val ids = (1..175).map(Int::toString)
        val aboveOldLimit = SessionPlanner.plan(ids, ids, emptyMap(), 150, 10, 0, Random(1))
        assertEquals(140, aboveOldLimit.size)
        val allAvailable = SessionPlanner.plan(ids, ids, emptyMap(), Int.MAX_VALUE, 1, 0, Random(1))
        assertEquals(ids.toSet(), allAvailable.map { it.lexemeUid }.toSet())
        assertEquals(ids.size, allAvailable.size)
    }

    @Test fun exhaustedQuotaStillAllowsDueReviewAndEmptyQueueDoesNotInventWork() {
        val cards = mapOf("due" to LearningCard("due", dueAt = 5, lastReviewAt = 1))
        val plan = SessionPlanner.plan(listOf("due", "new"), listOf("new"), cards, 0, 0, 5, Random(0))
        assertEquals(listOf("due"), plan.map { it.lexemeUid })
        assertFalse(plan.single().isNew)
        assertTrue(SessionPlanner.plan(listOf("new"), listOf("new"), emptyMap(), 0, 0, 5, Random(0)).isEmpty())
    }

    @Test fun fixedSeedReproducesAQueueWithoutDuplicates() {
        val ids = (1..20).map(Int::toString)
        val first = SessionPlanner.plan(ids, ids, emptyMap(), 10, 0, 0, Random(42))
        val second = SessionPlanner.plan(ids, ids, emptyMap(), 10, 0, 0, Random(42))
        assertEquals(first, second)
        assertEquals(10, first.map { it.lexemeUid }.toSet().size)
        assertNotEquals(ids.take(10), first.map { it.lexemeUid })
    }

    @Test fun streakIncludesYesterdayBeforeTodayHasStarted() {
        val today = LocalDate.parse("2026-09-08")
        assertEquals(0, SessionPlanner.streak(today, emptySet()))
        assertEquals(2, SessionPlanner.streak(today, setOf("2026-09-07", "2026-09-06")))
        assertEquals(3, SessionPlanner.streak(today, setOf("2026-09-08", "2026-09-07", "2026-09-06")))
        assertEquals(0, SessionPlanner.streak(today, setOf("2026-09-06")))
    }
}
