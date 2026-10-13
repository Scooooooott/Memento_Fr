package com.scott.memento.ui.settings

import androidx.compose.runtime.saveable.listSaver
import com.scott.memento.domain.AutoPlay
import com.scott.memento.domain.LanguagePreferences
import com.scott.memento.domain.Languages
import com.scott.memento.domain.StudyMode

/** UI drafts are separate for each target; opening a page never saves a policy. */
internal data class SettingsDraft(
    val learning:String,
    val goal:String,
    val reviewAll:Boolean,
    val book:String,
    val autoplay:String,
    val tts:Boolean,
    val reading:Boolean,
) {
    companion object {
        fun initial(target:String,saved:LanguagePreferences?,learning:String):SettingsDraft {
            val language=Languages.get(target)
            return SettingsDraft(
                learning=learning.takeIf{it in language.learningLanguages}
                    ?: "zh-Hans".takeIf{it in language.learningLanguages}
                    ?: language.learningLanguages.first(),
                goal=saved?.policy?.dailyTarget?.toString().orEmpty(),
                reviewAll=saved?.policy?.mode==StudyMode.REVIEW_ALL,
                book=saved?.book ?: language.defaultBook,
                autoplay=(saved?.playback?.autoPlay ?: AutoPlay.NEW_ONLY).name,
                tts=saved?.playback?.ttsFallback ?: true,
                reading=saved?.playback?.showReading ?: true,
            )
        }
    }
}

/** Primitive values only: SaveableStateHolder also retains these across the import page. */
internal val SettingsDraftsSaver=listSaver<Map<String,SettingsDraft>,String>(
    save={drafts->drafts.entries.flatMap{(target,draft)->
        listOf(target,draft.learning,draft.goal,draft.reviewAll.toString(),draft.book,
            draft.autoplay,draft.tts.toString(),draft.reading.toString())
    }},
    restore={values->
        if(values.size%8!=0) null else runCatching {
            values.chunked(8).associate{entry->
                val target=entry[0]
                require(entry[1] in Languages.get(target).learningLanguages)
                AutoPlay.valueOf(entry[5])
                target to SettingsDraft(entry[1],entry[2],entry[3].toBooleanStrict(),entry[4],
                    entry[5],entry[6].toBooleanStrict(),entry[7].toBooleanStrict())
            }
        }.getOrNull()
    },
)
