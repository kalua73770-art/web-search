package com.ajmat.voiceflow.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ajmat.voiceflow.audio.AudioRecorder
import com.ajmat.voiceflow.logging.VoiceFlowLog
import java.util.concurrent.Executors

/**
 * Optional system voice provider. It does not install or replace a keyboard.
 * OEM keyboards decide whether to expose third-party RecognitionService providers.
 */
class VoiceRecognitionService : RecognitionService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var recorder: AudioRecorder? = null
    @Volatile private var activeLanguage = "auto"

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        VoiceFlowLog.info(this, "System voice provider: startListening received.")
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoiceFlowLog.warn(this, "System voice provider denied: microphone permission missing.")
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        activeLanguage = RecognitionPolicy.languageFromLocale(
            recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
                ?: recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
        )

        try {
            val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                createContext(
                    android.content.ContextParams.Builder()
                        .setNextAttributionSource(listener.callingAttributionSource)
                        .build()
                )
            } else {
                this
            }
            recorder = AudioRecorder(this, attributionContext).also { it.start() }
            listener.readyForSpeech(Bundle())
            listener.beginningOfSpeech()
            VoiceFlowLog.info(this, "System voice provider recording started; language=" + activeLanguage + ".")
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            VoiceFlowLog.error(this, "System voice provider could not start microphone.", error)
            listener.error(SpeechRecognizer.ERROR_AUDIO)
        }
    }

    override fun onStopListening(listener: Callback) {
        listener.endOfSpeech()
        val audio = try {
            recorder?.stop() ?: throw IllegalStateException("No active system voice recording.")
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            VoiceFlowLog.error(this, "System voice provider failed to stop microphone.", error)
            listener.error(SpeechRecognizer.ERROR_AUDIO)
            return
        }
        recorder = null
        val language = activeLanguage

        worker.execute {
            try {
                val result = TranscriptionPipeline.transcribe(this, audio, language)
                val bundle = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(result.text)
                    )
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.90f))
                }
                listener.results(bundle)
                VoiceFlowLog.info(this, "System voice provider returned a transcript.")
            } catch (error: Exception) {
                VoiceFlowLog.error(this, "System voice provider transcription failed.", error)
                listener.error(
                    if (error.message?.contains("No speech", ignoreCase = true) == true) {
                        SpeechRecognizer.ERROR_NO_MATCH
                    } else {
                        SpeechRecognizer.ERROR_CLIENT
                    }
                )
            }
        }
    }

    override fun onCancel(listener: Callback) {
        recorder?.cancel()
        recorder = null
        VoiceFlowLog.info(this, "System voice provider cancelled by caller.")
    }

    override fun onDestroy() {
        recorder?.cancel()
        recorder = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
