package com.scott.memento.ui.language

import com.scott.memento.domain.Detail

/** UI-only information. This never determines content eligibility, quotas or score identity. */
data class LanguageUiMetadata(val values:List<String>,val irregular:Boolean=false,val reflexive:Boolean=false)

interface LanguageUiProfile {
    fun metadata(word:Detail):LanguageUiMetadata

    /** Preserve the package's explicit form order and labels; never manufacture a conjugation. */
    fun keyForms(word:Detail):List<Pair<String,String>> =
        (if(word.formDetails.isEmpty()) word.forms else word.formDetails.sortedBy{it.order}.map{it.label to it.form})
            .filter{it.second.isNotBlank()}.take(4)
}

object LanguageUiProfiles {
    fun forTarget(target:String):LanguageUiProfile=when(target) {
        "fr" -> French
        "es" -> Spanish
        else -> Generic
    }

    private object French:LanguageUiProfile {
        override fun metadata(word:Detail)=LanguageUiMetadata(
            listOf(word.summary.partOfSpeech,word.gender,word.grammarFeatures["verb_group"].orEmpty()).filter(String::isNotBlank),
        )
    }

    private object Spanish:LanguageUiProfile {
        override fun metadata(word:Detail)=LanguageUiMetadata(
            listOf(word.summary.partOfSpeech,word.gender,word.grammarFeatures["verb_group"].orEmpty()).filter(String::isNotBlank),
            irregular=word.grammarFeatures["is_irregular"]=="1",
            reflexive=word.grammarFeatures["is_reflexive"]=="1",
        )
    }

    private object Generic:LanguageUiProfile {
        override fun metadata(word:Detail)=LanguageUiMetadata(
            listOf(word.summary.partOfSpeech,word.gender).filter(String::isNotBlank)+
                word.grammarFeatures.filterValues(String::isNotBlank).map{(label,value)->"$label: $value"},
        )
    }
}
