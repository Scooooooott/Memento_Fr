package com.scott.memento.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.scott.memento.domain.*
import java.util.Locale

enum class SpeechStatus { LOCAL_PLAYING, OFFLINE_PLAYING, NO_RECORDING, NO_OFFLINE_VOICE, NOT_READY, FAILED }

/** One player for every target. Only installed, offline voices can be selected. */
class PronunciationPlayer(context:Context):AutoCloseable {
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private var media:MediaPlayer?=null
    private var tts:TextToSpeech?=null
    private var initialized:Boolean?=null
    private var pending:(()->Unit)?=null
    private var generation=0L
    private var closed=false
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    fun speak(word:Detail,target:String,allowTts:Boolean,onResult:(SpeechStatus)->Unit) = speakText(word.summary.lemma,Languages.get(target).locale,
        word.readings.firstOrNull{it.locale==Languages.get(target).locale && it.audioAsset!=null}?.audioAsset,allowTts,onResult)
    fun speakText(text:String,localeTag:String,asset:String?=null,allowTts:Boolean=true,onResult:(SpeechStatus)->Unit) {
        main.post {
            if(closed) return@post
            stopCurrent()
            val request=generation
            fun report(status:SpeechStatus) {if(!closed && generation==request) onResult(status)}
            fun fallback() {if(allowTts) speakTts(text,Locale.forLanguageTag(localeTag),request,::report) else report(SpeechStatus.NO_RECORDING)}
            if(asset.isNullOrBlank()) fallback() else try {
                require(!asset.startsWith('/') && !asset.contains("..") && !asset.contains('\\'))
                val player=MediaPlayer();media=player;player.setAudioAttributes(attributes)
                context.assets.openFd(asset).use{player.setDataSource(it.fileDescriptor,it.startOffset,it.length)}
                player.setOnPreparedListener {if(!closed && generation==request){it.start();report(SpeechStatus.LOCAL_PLAYING)}}
                player.setOnCompletionListener{if(media===it){it.release();media=null}}
                player.setOnErrorListener{failed,_,_->if(media===failed){failed.release();media=null};if(!closed && generation==request) fallback();true}
                player.prepareAsync()
            } catch(_:Exception){media?.release();media=null;fallback()}
        }
    }
    private fun speakTts(text:String,locale:Locale,request:Long,report:(SpeechStatus)->Unit) {
        if(closed || request!=generation) return
        when(initialized) {
            false -> report(SpeechStatus.NO_OFFLINE_VOICE)
            true -> {
                val engine=tts ?: return
                val voices=engine.voices.orEmpty().map {VoiceCandidate(it.name,it.locale,it.isNetworkConnectionRequired,
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty(),it.quality)}
                val selected=VoiceSelection.choose(locale,voices)
                val voice=engine.voices.orEmpty().firstOrNull{it.name==selected?.name}
                if(voice==null || engine.setVoice(voice)==TextToSpeech.ERROR) {report(SpeechStatus.NO_OFFLINE_VOICE);return}
                engine.setAudioAttributes(attributes)
                val utterance="memento-$request"
                engine.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                    override fun onStart(id:String?){if(id==utterance) main.post{report(SpeechStatus.OFFLINE_PLAYING)}}
                    override fun onDone(id:String?)=Unit
                    @Deprecated("Android callback") override fun onError(id:String?){if(id==utterance) main.post{report(SpeechStatus.FAILED)}}
                    override fun onError(id:String?,errorCode:Int){if(id==utterance) main.post{report(SpeechStatus.FAILED)}}
                })
                if(engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,utterance)==TextToSpeech.ERROR) report(SpeechStatus.FAILED)
            }
            null -> {
                pending={speakTts(text,locale,request,report)}
                if(tts==null) tts=TextToSpeech(context) {status->main.post{if(!closed){initialized=status==TextToSpeech.SUCCESS;val next=pending;pending=null;next?.invoke()}}}
                main.postDelayed({if(!closed && request==generation && initialized==null){pending=null;report(SpeechStatus.NOT_READY)}},8000)
            }
        }
    }
    fun stop(){main.post{stopCurrent()}}
    /** Returning from Android voice settings may make a previously absent engine available. */
    fun refreshAvailability(){main.post{if(initialized==false){stopCurrent();tts?.shutdown();tts=null;initialized=null}}}
    private fun stopCurrent(){generation++;pending=null;media?.release();media=null;tts?.stop()}
    override fun close(){main.post{closed=true;stopCurrent();tts?.shutdown();tts=null}}
}
