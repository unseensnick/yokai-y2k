package reikai.data.novel.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsUtteranceSplitter
import reikai.domain.novel.tts.TtsVoice
import java.util.Locale

/**
 * [NovelTtsEngine] backed by Android's [TextToSpeech]. Initialization is asynchronous, so callers
 * must wait for [onReady] (or check [isReady]) before [speak]; a speak before then no-ops its
 * `onDone` so the caller does not spin the chapter forward silently. One utterance is in flight at a
 * time (each [speak] flushes the previous), so a single pending callback slot is enough.
 * [TextToSpeech] fires its progress callbacks on a binder thread, and the caller marshals to the main
 * thread itself before touching the WebView.
 */
class SystemTtsEngine(
    context: Context,
    enginePackage: String,
    private val onReady: () -> Unit,
) : NovelTtsEngine {

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var pendingDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(
        context.applicationContext,
        { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) onReady()
        },
        enginePackage.ifBlank { null },
    ).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            // A paragraph can be several utterances, so only the last one finishes the caller's.
            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) fireDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = abort()
            override fun onError(utteranceId: String?, errorCode: Int) = abort()
        })
    }

    private fun fireDone() {
        val cb = pendingDone
        pendingDone = null
        cb?.invoke()
    }

    /** Ends the paragraph early. Whichever piece failed, the rest are dropped rather than read out of
     *  context, and the caller is told so it moves on instead of waiting for a callback never coming.
     *  The slot is cleared first, so the stop's own callbacks cannot come back around. */
    private fun abort() {
        val cb = pendingDone ?: return
        pendingDone = null
        runCatching { tts.stop() }
        cb()
    }

    override fun availableEngines(): List<TtsEngineInfo> =
        runCatching { tts.engines }.getOrNull().orEmpty()
            .map { TtsEngineInfo(it.name, it.label) }
            .sortedBy { it.label }

    override fun availableVoices(): List<TtsVoice> =
        runCatching { tts.voices }.getOrNull().orEmpty()
            .map { TtsVoice(it.name, "${it.locale.displayName} (${it.name})", it.locale.toLanguageTag()) }
            .sortedBy { it.displayName }

    override fun setVoice(voiceName: String) {
        if (voiceName.isBlank()) return
        val voice = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceName } ?: return
        runCatching { tts.voice = voice }
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(0.1f, 5.0f))
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch.coerceIn(0.1f, 5.0f))
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!isReady) {
            onDone()
            return
        }
        // The engine refuses an utterance past its own maximum outright, so a long paragraph arrives
        // as several and only the last carries the id the listener completes on.
        val pieces = TtsUtteranceSplitter.split(
            text = text,
            maxLength = TextToSpeech.getMaxSpeechInputLength(),
            locale = runCatching { tts.voice?.locale }.getOrNull() ?: Locale.getDefault(),
        )
        if (pieces.isEmpty()) {
            onDone()
            return
        }
        pendingDone = onDone
        pieces.forEachIndexed { index, piece ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (index == pieces.lastIndex) UTTERANCE_ID else PART_UTTERANCE_ID
            // A refusal is reported as a return value and never reaches the listener, so without this
            // nothing would clear the callback and playback would stop here for good.
            if (tts.speak(piece, queueMode, null, id) == TextToSpeech.ERROR) {
                abort()
                return
            }
        }
    }

    override fun stop() {
        pendingDone = null
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        pendingDone = null
        runCatching { tts.shutdown() }
    }

    private companion object {
        /** Carried by the piece that ends a paragraph, which is the one the caller waits on. */
        const val UTTERANCE_ID = "reikai-novel-tts"

        /** Carried by every piece before it, so finishing one does not finish the paragraph. */
        const val PART_UTTERANCE_ID = "reikai-novel-tts-part"
    }
}
