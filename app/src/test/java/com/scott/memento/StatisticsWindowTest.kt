package com.scott.memento

import com.scott.memento.domain.HistoryDay
import com.scott.memento.ui.statistics.StatisticsWindow
import com.scott.memento.ui.statistics.contentCoverage
import org.junit.Assert.*
import org.junit.Test

class StatisticsWindowTest {
    private fun day(words:Int,ratings:Int=words)=HistoryDay("2026-10-10",words,0,words,ratings,0)

    @Test fun historicalBarsUseSelectedWindowPeakAndNeverAnExternalGoal() {
        val today=day(3,9)
        assertEquals(0.5f,StatisticsWindow(listOf(today,day(6))).fraction(today),0f)
        assertEquals(0.25f,StatisticsWindow(listOf(today,day(12))).fraction(today),0f)
        assertEquals(1f,StatisticsWindow(listOf(today)).fraction(today),0f)
    }
    @Test fun emptyAndZeroHistoryHaveZeroVolumeInsteadOfDivisionByZero() {
        assertEquals(0,StatisticsWindow(emptyList()).peak)
        assertEquals(0f,StatisticsWindow(listOf(day(0))).fraction(day(0)),0f)
        assertEquals(0L,StatisticsWindow(emptyList()).ratings)
    }
    @Test fun dailySumIsNotWindowUidDeduplicationAndUsesLongToPreventOverflow() {
        val window=StatisticsWindow(listOf(day(Int.MAX_VALUE),day(Int.MAX_VALUE,3)))
        assertEquals(4294967294L,window.dailyWords)
        assertEquals(2147483650L,window.ratings)
    }
    @Test fun coverageUsesAvailableContentAndSafelyBoundsOnlyDrawing() {
        assertEquals(0f,contentCoverage(3,0),0f)
        assertEquals(0.25f,contentCoverage(1,4),0f)
        assertEquals(1f,contentCoverage(8,4),0f)
    }
}
