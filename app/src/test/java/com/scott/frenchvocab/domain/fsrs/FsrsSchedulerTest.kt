package com.scott.frenchvocab.domain.fsrs

import com.scott.frenchvocab.domain.LearningCard
import com.scott.frenchvocab.domain.Rating
import org.junit.Assert.*
import org.junit.Test

class FsrsSchedulerTest {
    private val scheduler = FsrsScheduler()

    @Test fun publishedPackageOracleMatchesAllMemoryStatesAndIntervals() {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("fsrs-5.4.2-reference.csv"))
        val lines = stream.bufferedReader().use { it.readLines().drop(1) }
        assertEquals(36, lines.size)
        for (line in lines) {
            val c = line.split(',')
            val card = LearningCard("fixture", c[1].toDouble(), c[2].toDouble(), lastReviewAt = c[3].toLongOrNull(),
                repetitions = c[4].toInt(), lapses = c[5].toInt(), state = if (c[3].isEmpty()) "NEW" else "REVIEW")
            val next = scheduler.review(card, Rating.valueOf(c[6]), c[7].toLong())
            assertEquals(c[0] + " stability", c[8].toDouble(), next.stability, 0.00000002)
            assertEquals(c[0] + " difficulty", c[9].toDouble(), next.difficulty, 0.00000002)
            assertEquals(c[0] + " due", c[10].toLong(), next.dueAt)
            assertEquals(c[0] + " repetitions", c[11].toInt(), next.repetitions)
            assertEquals(c[0] + " lapses", c[12].toInt(), next.lapses)
            assertEquals("REVIEW", next.state)
        }
    }

    @Test fun retentionIsNinetyPercentAtStability() {
        assertEquals(.9, scheduler.retrievability(10, 10.0), 0.00000001)
        assertEquals(1.0, scheduler.retrievability(0, 10.0), 0.0)
        assertTrue(scheduler.retrievability(20, 10.0) < .9)
    }

    @Test fun againDoesNotCountAnInitialLapseAndIsAtLeastOneDayLater() {
        val initial = scheduler.review(LearningCard("word"), Rating.AGAIN, 1000)
        assertEquals(0, initial.lapses)
        assertEquals(1000 + FsrsScheduler.DAY_MILLIS, initial.dueAt)
        val forgotten = scheduler.review(initial, Rating.AGAIN, initial.dueAt)
        assertEquals(1, forgotten.lapses)
        assertTrue(forgotten.dueAt >= initial.dueAt + FsrsScheduler.DAY_MILLIS)
    }

    @Test fun invalidStatesAndTimeFailBeforePersistence() {
        val valid = LearningCard("word", stability = 2.0, difficulty = 5.0, lastReviewAt = 1000, state = "REVIEW")
        assertThrows(IllegalArgumentException::class.java) { scheduler.review(valid, Rating.GOOD, 999) }
        assertThrows(IllegalArgumentException::class.java) { scheduler.review(valid.copy(stability = Double.NaN), Rating.GOOD, 1000) }
        assertThrows(IllegalArgumentException::class.java) { scheduler.review(valid.copy(difficulty = 11.0), Rating.GOOD, 1000) }
        assertThrows(IllegalArgumentException::class.java) { scheduler.review(LearningCard("word"), Rating.GOOD, -1) }
        assertThrows(IllegalArgumentException::class.java) { scheduler.review(LearningCard("word"), Rating.EASY, Long.MAX_VALUE) }
    }

    @Test fun elapsedDaysUseUtcBoundaryConsistentWithUpstream() {
        val card = LearningCard("word", lastReviewAt = FsrsScheduler.DAY_MILLIS - 1)
        assertEquals(1L, scheduler.elapsedDays(card, FsrsScheduler.DAY_MILLIS))
        assertEquals(0L, scheduler.elapsedDays(card, FsrsScheduler.DAY_MILLIS - 1))
    }
}
