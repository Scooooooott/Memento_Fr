package com.scott.memento.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.ui.SpeechStatus

/** A dismissible overlay: feedback never changes the card's layout or hides its fixed actions. */
@Composable
fun AppNotice(error:String?,speech:SpeechStatus?,locale:String,onDismissError:()->Unit,
              onDismissSpeech:()->Unit,modifier:Modifier=Modifier) {
    if(error==null && speech==null) return
    val dismiss=stringResource(if(error!=null) R.string.ui6_dismiss_error else R.string.ui6_dismiss_notice)
    Snackbar(modifier=modifier.padding(horizontal=12.dp).testTag("app_notice"),
        dismissAction={IconButton(onClick=if(error!=null) onDismissError else onDismissSpeech,
            modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)
                .semantics{contentDescription=dismiss}.testTag(if(error!=null) "error_dismiss" else "speech_dismiss")) {
            Text("×",style=MaterialTheme.typography.titleLarge)
        }}) {
        Column(Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(error!=null) stringResource(R.string.operation_failed,error) else when(checkNotNull(speech)) {
                SpeechStatus.LOCAL_PLAYING->stringResource(R.string.speech_local)
                SpeechStatus.OFFLINE_PLAYING->stringResource(R.string.speech_offline)
                SpeechStatus.NO_RECORDING->stringResource(R.string.speech_no_recording)
                SpeechStatus.NO_OFFLINE_VOICE->stringResource(R.string.speech_no_voice,locale)
                SpeechStatus.NOT_READY->stringResource(R.string.speech_not_ready)
                SpeechStatus.FAILED->stringResource(R.string.speech_failed)
            },style=MaterialTheme.typography.bodyMedium,
                modifier=Modifier.testTag(if(error!=null) "app_error" else "speech_status").semantics{liveRegion=LiveRegionMode.Polite})
        }
    }
}
