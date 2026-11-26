package com.ajmat.voiceflow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.ajmat.voiceflow.speech.ModelCatalog
import com.ajmat.voiceflow.speech.ModelDownloader
import java.io.File

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("voiceflow", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var modelPicker: Spinner
    private lateinit var endpointInput: EditText
    private lateinit var timeoutLabel: TextView
    private lateinit var timeoutSeek: SeekBar
    private lateinit var downloadButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        requestMicrophonePermissionIfNeeded()
        refreshStatus()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(content) }

        content.addView(label("Ajmat VoiceFlow", 27f, true))
        content.addView(label("Hindi • English • Hinglish", 15f, false))
        content.addView(space(18))
        content.addView(label("1. Choose your offline model", 18f, true))

        modelPicker = Spinner(this)
        val labels = ModelCatalog.models.map { it.title + " (" + it.approximateSize + ")" }
        modelPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val savedId = preferences.getString("model_id", "small-q5")
        modelPicker.setSelection(ModelCatalog.models.indexOfFirst { it.id == savedId }.coerceAtLeast(0))
        content.addView(modelPicker)

        downloadButton = button("Download selected model")
        downloadButton.setOnClickListener { downloadSelectedModel() }
        content.addView(downloadButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = ProgressBar.GONE
        }
        content.addView(progress)
        content.addView(space(8))
        status = label("Checking model…", 14f, false)
        content.addView(status)

        content.addView(space(22))
        content.addView(label("2. Optional online fallback", 18f, true))
        content.addView(label(
            "Leave this blank for fully offline typing. To use online-first recognition, enter your own HTTPS endpoint that accepts multipart audio and returns {\"text\":\"…\"}. If the request fails or times out, VoiceFlow automatically uses the downloaded offline model.",
            13f,
            false
        ))
        endpointInput = EditText(this).apply {
            hint = "https://your-server.example/transcribe"
            setSingleLine(true)
            textSize = 14f
            setText(preferences.getString("endpoint", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        content.addView(endpointInput)

        val savedTimeout = (preferences.getInt("timeout_ms", 20_000) / 1000).coerceIn(5, 60)
        timeoutLabel = label("Online timeout: " + savedTimeout + "s", 14f, false)
        content.addView(timeoutLabel)
        timeoutSeek = SeekBar(this).apply {
            max = 55
            progress = savedTimeout - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    timeoutLabel.text = "Online timeout: " + (value + 5) + "s"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        content.addView(timeoutSeek)

        content.addView(button("Save settings").apply {
            setOnClickListener {
                val endpoint = endpointInput.text.toString().trim()
                if (endpoint.isNotEmpty() && !endpoint.startsWith("https://")) {
                    Toast.makeText(
                        this@MainActivity,
                        "Use an HTTPS endpoint so audio is encrypted in transit.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString("endpoint", endpoint)
                    .putInt("timeout_ms", (timeoutSeek.progress + 5) * 1000)
                    .apply()
                Toast.makeText(this@MainActivity, "Settings saved.", Toast.LENGTH_SHORT).show()
            }
        })

        content.addView(space(22))
        content.addView(label("3. Enable the keyboard", 18f, true))
        content.addView(label(
            "Open Android keyboard settings, enable Ajmat VoiceFlow Keyboard, then select it from the keyboard picker in any app. Tap the microphone key to start and stop dictation.",
            13f,
            false
        ))
        content.addView(button("Open input method settings").apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        content.addView(button("Choose Ajmat VoiceFlow keyboard").apply {
            setOnClickListener {
                val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showInputMethodPicker()
            }
        })
        content.addView(space(12))
        content.addView(label(
            "Privacy: audio is recorded only after you tap the mic. With no online endpoint, transcription runs on this phone. Temporary audio is deleted after processing.",
            12f,
            false
        ))
        setContentView(scroll)
    }

    private fun downloadSelectedModel() {
        val model = ModelCatalog.models[modelPicker.selectedItemPosition]
        downloadButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        progress.progress = 0
        status.text = "Preparing " + model.title + "…"

        Thread {
            try {
                val file = ModelDownloader.download(this, model) { downloaded, total ->
                    runOnUiThread {
                        if (total > 0L) {
                            progress.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        }
                        status.text = "Downloaded " + (downloaded / 1_000_000) + " MB"
                    }
                }
                preferences.edit()
                    .putString("model_id", model.id)
                    .putString("model_file", file.name)
                    .apply()
                runOnUiThread {
                    progress.progress = 100
                    status.text = "Ready: " + file.name
                    downloadButton.isEnabled = true
                    Toast.makeText(this, "Offline model is ready.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Download error: " + (error.message ?: "unknown error")
                    downloadButton.isEnabled = true
                    progress.visibility = ProgressBar.GONE
                }
            }
        }.start()
    }

    private fun refreshStatus() {
        val model = ModelCatalog.byId(preferences.getString("model_id", "small-q5"))
        val filename = preferences.getString("model_file", model.fileName) ?: model.fileName
        val file = File(File(filesDir, "models"), filename)
        status.text = if (file.exists() && file.length() >= model.minimumBytes) {
            "Offline model ready: " + model.title
        } else {
            "Offline model not downloaded yet."
        }
    }

    private fun requestMicrophonePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7001)
        }
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFF182B2A.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun space(height: Int) = TextView(this).apply { this.height = dp(height) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
