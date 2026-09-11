/*
 * Adapted from ts-fsrs v5.4.2 (FSRS-6), Open Spaced Repetition, MIT.
 * See this directory's THIRD_PARTY_LICENSE.txt and README.md for provenance.
 */
package com.scott.frenchvocab.domain.fsrs

import com.scott.frenchvocab.domain.LearningCard
import com.scott.frenchvocab.domain.Rating
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** Upstream LongTermScheduler, fixed retention 0.90, no fuzz and no short-term steps. */
class FsrsScheduler {
    companion object {
        const val VERSION = "ts-fsrs-5.4.2/FSRS-6/long-term"
        const val DESIRED_RETENTION = 0.90
        const val DAY_MILLIS = 86_400_000L
        private const val S_MIN = 0.001
        private const val S_MAX = 36500.0
        private val W = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
            1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483,
            0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
    }

    private val decay = -W[20]
    private val factor = round8(exp(ln(0.9) / decay) - 1)
    private val intervalModifier = round8((DESIRED_RETENTION.pow(1 / decay) - 1) / factor)

    fun elapsedDays(card: LearningCard, now: Long): Long {
        val last = card.lastReviewAt ?: return 0
        // Upstream dateDiffInDays counts UTC calendar boundaries, not 24-hour durations.
        return (Math.floorDiv(now, DAY_MILLIS) - Math.floorDiv(last, DAY_MILLIS)).coerceAtLeast(0)
    }

    fun retrievability(elapsedDays: Long, stability: Double): Double {
        require(elapsedDays >= 0 && stability.isFinite() && stability > 0)
        return round8((1 + factor * elapsedDays / stability).pow(decay))
    }

    fun review(card: LearningCard, rating: Rating, now: Long): LearningCard {
        require(now >= 0) { "Review time must be a non-negative epoch millisecond value" }
        require(card.lastReviewAt == null || now >= card.lastReviewAt) { "Review cannot precede the previous review" }
        val isNew = card.lastReviewAt == null
        if (!isNew) {
            require(card.stability.isFinite() && card.stability in S_MIN..S_MAX) { "Invalid stability" }
            require(card.difficulty.isFinite() && card.difficulty in 1.0..10.0) { "Invalid difficulty" }
        }
        val elapsed = elapsedDays(card, now)
        val r = if (isNew) 1.0 else retrievability(elapsed, card.stability)
        val nextStates = Rating.entries.map { candidate ->
            val grade = candidate.ordinal + 1
            if (isNew) Memory(W[grade - 1].coerceAtLeast(0.1), initialDifficulty(grade).coerceIn(1.0, 10.0))
            else Memory(nextStability(card, candidate, r), nextDifficulty(card.difficulty, grade))
        }
        val intervals = nextStates.map { floor(it.stability * intervalModifier + 0.5).toLong().coerceIn(1, 36500) }.toMutableList()
        // Retain upstream ordering, including the possibility of 36501–36503 at the cap.
        intervals[0] = minOf(intervals[0], intervals[1])
        for (index in 1..3) intervals[index] = maxOf(intervals[index], intervals[index - 1] + 1)
        val selected = nextStates[rating.ordinal]
        val intervalMillis = intervals[rating.ordinal] * DAY_MILLIS
        require(now <= Long.MAX_VALUE - intervalMillis) { "Review date is outside the supported range" }
        return card.copy(
            stability = selected.stability, difficulty = selected.difficulty,
            dueAt = now + intervalMillis, lastReviewAt = now,
            repetitions = Math.addExact(card.repetitions, 1),
            lapses = if (!isNew && rating == Rating.AGAIN) Math.addExact(card.lapses, 1) else card.lapses,
            state = "REVIEW",
        )
    }

    private fun initialDifficulty(grade: Int) = round8(W[4] - exp((grade - 1) * W[5]) + 1)

    private fun nextDifficulty(difficulty: Double, grade: Int): Double {
        val delta = -W[6] * (grade - 3)
        val damped = round8(delta * (10 - difficulty) / 9)
        return round8(W[7] * initialDifficulty(4) + (1 - W[7]) * (difficulty + damped)).coerceIn(1.0, 10.0)
    }

    private fun nextStability(card: LearningCard, rating: Rating, r: Double): Double {
        val s = card.stability
        val d = card.difficulty
        return if (rating == Rating.AGAIN) {
            val afterFailure = round8((W[11] * d.pow(-W[12]) * ((s + 1).pow(W[13]) - 1) * exp((1 - r) * W[14])).coerceIn(S_MIN, S_MAX))
            minOf(round8(s).coerceAtLeast(S_MIN), afterFailure)
        } else {
            val hardPenalty = if (rating == Rating.HARD) W[15] else 1.0
            val easyBonus = if (rating == Rating.EASY) W[16] else 1.0
            round8((s * (1 + exp(W[8]) * (11 - d) * s.pow(-W[9]) * (exp((1 - r) * W[10]) - 1) * hardPenalty * easyBonus)).coerceIn(S_MIN, S_MAX))
        }
    }

    private fun round8(value: Double) = floor(value * 100_000_000.0 + 0.5) / 100_000_000.0
    private data class Memory(val stability: Double, val difficulty: Double)
}
