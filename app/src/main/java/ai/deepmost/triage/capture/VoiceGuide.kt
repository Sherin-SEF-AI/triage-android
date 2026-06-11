package ai.deepmost.triage.capture

import android.content.Context
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/**
 * Hands-free spoken capture guidance via on-device Android TTS. Speaks the station to frame,
 * accept/reject feedback, etc., in the selected UI language when a voice is available (falls back
 * to the device default otherwise). Entirely on-device; lifecycle-managed by the capture screen.
 */
class VoiceGuide(context: Context, private val languageCode: String) {

    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyLocale()
        } else {
            Timber.w("TTS init failed: %d", status)
        }
    }

    private fun applyLocale() {
        val locale = when (languageCode) {
            "ml" -> Locale("ml", "IN")
            "hi" -> Locale("hi", "IN")
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        val res = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            runCatching { tts.setLanguage(Locale.ENGLISH) }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!ready || text.isBlank()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching { tts.speak(text, mode, null, text.hashCode().toString()) }
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
