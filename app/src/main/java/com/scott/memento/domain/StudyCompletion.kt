package com.scott.memento.domain

/** A presentation event emitted after a committed final rating or overflow decision. */
data class StudyCompletion(val target:String,val planId:Long,val revision:Int,val token:String)
