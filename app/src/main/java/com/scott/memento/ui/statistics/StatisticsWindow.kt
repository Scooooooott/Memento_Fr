package com.scott.memento.ui.statistics

import com.scott.memento.domain.HistoryDay

/** A sum of daily distinct word counts is not a distinct-word total across multiple days. */
data class StatisticsWindow(val days:List<HistoryDay>) {
    val peak:Int get()=days.maxOfOrNull {it.words}?.coerceAtLeast(0) ?: 0
    val dailyWords:Long get()=days.sumOf {it.words.toLong()}
    val ratings:Long get()=days.sumOf {it.ratings.toLong()}
    fun fraction(day:HistoryDay):Float=if(peak==0) 0f else (day.words.toFloat()/peak).coerceIn(0f,1f)
}

fun contentCoverage(learned:Int,available:Int):Float=
    if(available<=0) 0f else (learned.toFloat()/available).coerceIn(0f,1f)
