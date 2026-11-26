package com.ajmat.voiceflow.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ajmat.voiceflow.audio.AudioRecorder
import com.ajmat.voiceflow.speech.ModelCatalog
import com.ajmat.voiceflow.speech.OnlineTranscriber
import com.ajmat.voiceflow.speech.RecognitionPolicy
import com.ajmat.voiceflow.speech.RecognitionRoute
import com.ajmat.voiceflow.speech.WhisperEngine
import java.io.File
import java.util.concurrent.Executors

class VoiceInputMethodService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recorder: AudioRecorder? = null
    private var recording = false
    private var shifted = false
    private var language = "auto"
    private var micButton: Button? = null
    private var languageButton: Button? = null
    private var statusText: TextView? = null

    private val preferences by lazy {
        getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
    }

    override fun onCreateInputView(): View {
        language = RecognitionPolicy.normalizeLanguage(preferences.getString("language", "auto"))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(7))
            setBackgroundColor(0xFFF1F3F5.toInt())
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micButton = keyButton("🎙  Start voice typing", 15f).apply {
            setOnClickListener { toggleRecording() }
        }
        languageButton = keyButton(languageLabel(), 13f).apply {
            setOnClickListener { cycleLanguage() }
        }
        val hideButton = keyButton("⌄", 18f).apply {
            setOnClickListener { requestHideSelf(0) }
        }
        controls.addView(micButton, weighted(5f, 48))
        controls.addView(languageButton, weighted(1.7f, 48))
        controls.addView(hideButton, weighted(0.8f, 48))
        panel.addView(controls)

        statusText = TextView(this).apply {
            text = "Tap the microphone, speak, then tap Stop."
            textSize = 11f
            setTextColor(0xFF52615F.toInt())
            setPadding(dp(8), dp(1), dp(8), dp(4))
        }
        panel.addView(statusText)

        addLetterRow(panel, "qwertyuiop")
        addLetterRow(panel, "asdfghjkl")

        val third = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        third.addView(keyButton("⇧", 15f).apply {
            setOnClickListener { shifted = !shifted; text = if (shifted) "⇧●" else "⇧" }
        }, weighted(1.3f, 43))
        "zxcvbnm".forEach { addLetterKey(third, it) }
        third.addView(keyButton("⌫", 17f).apply {
            setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        }, weighted(1.3f, 43))
        panel.addView(third)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton(",?!", 12f).apply {
            setOnClickListener { commitText(",") }
        }, weighted(1f, 45))
        bottom.addView(keyButton("☺", 15f).apply {
            setOnClickListener { commitText("🙂") }
        }, weighted(1f, 45))
        bottom.addView(keyButton("space", 12f).apply {
            setOnClickListener { commitText(" ") }
        }, weighted(4f, 45))
        bottom.addView(keyButton(".", 17f).apply {
            setOnClickListener { commitText(".") }
        }, weighted(1f, 45))
        bottom.addView(keyButton("↵", 17f).apply {
            setOnClickListener {
                val connection = currentInputConnection
                if (connection != null) {
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }, weighted(1f, 45))
        panel.addView(bottom)
        return panel
    }

    private fun addLetterRow(parent: LinearLayout, letters: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(1), 0, dp(1))
        }
        letters.forEach { addLetterKey(row, it) }
        parent.addView(row)
    }

    private fun addLetterKey(row: LinearLayout, letter: Char) {
        row.addView(keyButton(letter.toString(), 15f).apply {
            setOnClickListener {
                val output = if (shifted) letter.uppercaseChar() else letter
                commitText(output.toString())
                shifted = false
            }
        }, weighted(1f, 43))
    }

    private fun keyButton(title: String, size: Float) = Button(this).apply {
        text = title
        textSize = size
        isAllCaps = false
        minHeight = 0
        minimumHeight = dp(40)
        setPadding(dp(1), 0, dp(1), 0)
    }

    private fun weighted(weight: Float, height: Int) =
        LinearLayout.LayoutParams(0, dp(height), weight).apply {
            setMargins(dp(2), dp(1), dp(2), dp(1))
        }

    private fun cycleLanguage() {
        language = when (language) {
            "auto" -> "hi"
            "hi" -> "en"
            else -> "auto"
        }
        preferences.edit().putString("language", language).apply()
        languageButton?.text = languageLabel()
        statusText?.text = "Recognition language: " + languageLabel()
    }

    private fun languageLabel(): String = when (language) {
        "hi" -> "हिन्दी"
        "en" -> "EN"
        else -> "AUTO"
    }

    private fun toggleRecording() {
        if (recording) stopAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Open Ajmat VoiceFlow and allow microphone permission first.", Toast.LENGTH_LONG).show()
            return
        }

        val model = ModelCatalog.byId(preferences.getString("model_id", "small-q5"))
        val filename = preferences.getString("model_file", model.fileName) ?: model.fileName
        val modelFile = File(File(filesDir, "models"), filename)
        if (!modelFile.isFile || modelFile.length() < model.minimumBytes) {
            Toast.makeText(this, "Download the offline model in Ajmat VoiceFlow first.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            recorder = AudioRecorder(this).also { it.start() }
            recording = true
            micButton?.text = "■  Stop and type"
            statusText?.text = "Listening… tap Stop when you finish."
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            Toast.makeText(this, error.message ?: "Could not start microphone.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAndTranscribe() {
        recording = false
        micButton?.isEnabled = false
        micButton?.text = "Processing…"
        statusText?.text = "Transcribing audio…"
        val audioFile = try {
            recorder?.stop() ?: throw IllegalStateException("No active recording.")
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            micButton?.isEnabled = true
            micButton?.text = "🎙  Start voice typing"
            statusText?.text = error.message ?: "Recording failed."
            return
        }
        recorder = null

        val endpoint = preferences.getString("endpoint", "").orEmpty().trim()
        val timeout = preferences.getInt("timeout_ms", 20_000)
        val selectedLanguage = language
        val inputConnection: InputConnection? = currentInputConnection

        worker.execute {
            var transcript: String? = null
            var usedOffline = false
            var failureMessage: String? = null
            try {
                if (RecognitionPolicy.routeFor(endpoint) == RecognitionRoute.ONLINE_THEN_OFFLINE) {
                    transcript = OnlineTranscriber.transcribe(audioFile, endpoint, selectedLanguage, timeout)
                }
                if (transcript.isNullOrBlank()) {
                    usedOffline = true
                    transcript = WhisperEngine.transcribe(this, audioFile, selectedLanguage)
                }
            } catch (error: Exception) {
                failureMessage = error.message ?: "Transcription failed."
            } finally {
                audioFile.delete()
            }

            mainHandler.post {
                micButton?.isEnabled = true
                micButton?.text = "🎙  Start voice typing"
                if (!transcript.isNullOrBlank()) {
                    inputConnection?.commitText(transcript, 1)
                    statusText?.text = if (usedOffline) {
                        "Inserted using offline recognition."
                    } else {
                        "Inserted using online recognition."
                    }
                } else {
                    statusText?.text = failureMessage ?: "No speech detected. Please try again."
                    Toast.makeText(this, failureMessage ?: "No speech detected.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun commitText(value: String) {
        currentInputConnection?.commitText(value, 1)
    }

    override fun onWindowHidden() {
        if (recording) {
            recording = false
            recorder?.cancel()
            recorder = null
            micButton?.text = "🎙  Start voice typing"
            statusText?.text = "Recording cancelled."
        }
        super.onWindowHidden()
    }

    override fun onDestroy() {
        if (recording) recorder?.cancel()
        worker.shutdownNow()
        WhisperEngine.releaseModel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
