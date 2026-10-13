package com.scott.memento.domain

import java.util.Locale

data class VoiceCandidate(val name:String,val locale:Locale,val network:Boolean,val installed:Boolean,val quality:Int)
object VoiceSelection {
    fun choose(locale:Locale,voices:List<VoiceCandidate>):VoiceCandidate?=voices.filter{
        it.locale.language==locale.language && !it.network && it.installed
    }.sortedWith(compareByDescending<VoiceCandidate>{it.locale.country==locale.country}.thenByDescending{it.quality}.thenBy{it.name}).firstOrNull()
}
