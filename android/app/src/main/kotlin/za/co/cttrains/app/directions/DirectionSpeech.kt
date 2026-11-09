package za.co.cttrains.app.directions

import android.content.Context
import android.speech.tts.TextToSpeech
import android.media.AudioAttributes
import java.util.Locale
import za.co.cttrains.core.*

class DirectionSpeech(context: Context,private val error: (String)->Unit) {
    private var ready=false
    private var closed=false
    private var pending: String?=null
    private val announced=mutableSetOf<String>()
    private var engine: TextToSpeech?=null
    init {
        engine=TextToSpeech(context.applicationContext) { status ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if(!closed) {
                    val tts=engine
                    val language=tts?.setLanguage(Locale.forLanguageTag("en-ZA")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ready=status==TextToSpeech.SUCCESS && language>=0
                    if(!ready) error("Speech unavailable. Install an English voice in your phone's Text-to-speech settings.")
                    else { tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()); pending?.let(::speak);pending=null }
                }
            }
        }
    }
    fun speak(text: String) {
        if(closed) return
        if(!ready) { pending=text;return }
        if(engine?.speak(text.take(1500),TextToSpeech.QUEUE_FLUSH,null,"directions")==TextToSpeech.ERROR) error("Could not play directions. Check your phone's speech engine and media volume.")
    }
    fun guidance(s: DirectionsSession,g: DirectionGuidance,enabled: Boolean) {
        if(!enabled || s.paused) { stop();return }
        val key="${s.plan.id}:${s.index}:${g.title}"
        if(announced.add(key)) {speak(g.title+". "+if(s.step?.kind==DirectionKind.BOARD) s.step?.detail.orEmpty() else if(g.offRoute) g.detail else "") }
    }
    fun stop() { pending=null;engine?.stop() }
    fun close() { closed=true;pending=null;engine?.stop();engine?.shutdown();engine=null }
    companion object {
        fun enabled(c: Context)=c.getSharedPreferences("directions-voice",Context.MODE_PRIVATE).getBoolean("enabled",true)
        fun enabled(c: Context,value: Boolean) { c.getSharedPreferences("directions-voice",Context.MODE_PRIVATE).edit().putBoolean("enabled",value).apply() }
    }
}
