package com.scott.memento.domain

import java.text.Normalizer
import java.util.Locale

object SearchKey {
    const val VERSION="nfkc-lower-latin-nfd-1"
    fun normalize(text:String):String {
        val decomposed=Normalizer.normalize(Normalizer.normalize(text.trim{it.code<=32},Normalizer.Form.NFKC).lowercase(Locale.ROOT),Normalizer.Form.NFD)
        var latin=false
        val output=StringBuilder()
        decomposed.codePoints().forEach { point ->
            val mark=Character.getType(point) in listOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt(),Character.ENCLOSING_MARK.toInt())
            if(mark) { if(!latin) output.appendCodePoint(point) }
            else { latin=Character.UnicodeScript.of(point)==Character.UnicodeScript.LATIN;output.appendCodePoint(point) }
        }
        return Normalizer.normalize(output,Normalizer.Form.NFC)
    }
}
