package com.ajmat.voiceflow

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
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
import com.ajmat.voiceflow.logging.VoiceFlowLog
import com.ajmat.voiceflow.overlay.VoiceFlowOverlayService
import com.ajmat.voiceflow.speech.ModelCatalog
import com.ajmat.voiceflow.speech.ModelDownloader
import java.io.File

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("voiceflow", MODE_PRIVATE) }
    private lateinit var modelStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var modelPicker: Spinner
    private lateinit var languagePicker: Spinner
    private lateinit var endpointInput: EditText
    private lateinit var timeoutLabel: TextView
    private lateinit var timeoutSeek: SeekBar
    private lateinit var downloadButton: Button
    private lateinit var overlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceFlowLog.installCrashHandler(applicationContext)
        val savedModelId = preferences.getString("model_id", null)
        val resolvedModelId = com.ajmat.voiceflow.speech.ModelSelectionPolicy.resolveModelId(savedModelId)
        if (savedModelId != resolvedModelId) {
            preferences.edit().putString("model_id", resolvedModelId).putString("model_file", ModelCatalog.byId(resolvedModelId).fileName).apply()
            VoiceFlowLog.info(this, "Legacy model preference migrated to " + resolvedModelId + ".")
        }
        VoiceFlowLog.info(this, "Ajmat VoiceFlow v2 settings opened.")
        buildScreen()
        requestPermissionsIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) updatePermissionStatus()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(content) }

        content.addView(label("Ajmat VoiceFlow", 27f, true))
        content.addView(label("Use your Samsung Keyboard. VoiceFlow is a voice tool, not a replacement keyboard.", 15f, false))
        content.addView(space(14))

        content.addView(label("1. Prepare offline speech recognition", 18f, true))
        content.addView(label("Base Q5 is the balanced default for Hindi + English. Small Q5 is larger and prioritizes accuracy.", 13f, false))
        modelPicker = Spinner(this)
        val labels = ModelCatalog.models.map { it.title + " (" + it.approximateSize + ")" }
        modelPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val savedId = preferences.getString("model_id", "base-q5")
        modelPicker.setSelection(ModelCatalog.models.indexOfFirst { it.id == savedId }.coerceAtLeast(0))
        content.addView(modelPicker)

        downloadButton = button("Download / verify selected model")
        downloadButton.setOnClickListener { downloadSelectedModel() }
        content.addView(downloadButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        content.addView(progress)
        modelStatus = label("Checking model…", 13f, false)
        content.addView(modelStatus)

        content.addView(label("Recognition language", 14f, true))
        languagePicker = Spinner(this)
        val languageLabels = listOf("Auto detect (Hindi + English)", "Hindi only", "English only")
        languagePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageLabels)
        val savedLanguage = preferences.getString("language", "auto")
        languagePicker.setSelection(when (savedLanguage) {
            "hi" -> 1
            "en" -> 2
            else -> 0
        })
        languagePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val language = when (position) {
                    1 -> "hi"
                    2 -> "en"
                    else -> "auto"
                }
                preferences.edit().putString("language", language).apply()
                VoiceFlowLog.info(this@MainActivity, "Recognition language set to " + language + ".")
            }
        }
        content.addView(languagePicker)

        content.addView(space(18))
        content.addView(label("2. Start floating voice typing", 18f, true))
        content.addView(label(
            "Keep Samsung Keyboard selected. Enable Paste Helper in Android Accessibility settings once, then start VoiceFlow once. The compact mic appears when the keyboard is open in an editable field. Tap the mic to speak; tap it again to finish. VoiceFlow transcribes and inserts the text. The foreground notification keeps the service active; microphone capture starts only after you tap the mic.",
            13f,
            false
        ))
        permissionStatus = label("", 13f, false)
        content.addView(permissionStatus)

        overlayButton = button("Start floating voice panel")
        overlayButton.setOnClickListener { startOverlay() }
        content.addView(overlayButton)

        content.addView(button("Allow Display over other apps").apply {
            setOnClickListener { openOverlayPermissionSettings() }
        })

        content.addView(space(18))
        content.addView(label("3. Optional Samsung / Android voice input provider", 18f, true))
        content.addView(label(
            "VoiceFlow also registers as an Android speech-recognition service. Open system Voice Input settings and select Ajmat VoiceFlow if Android/Samsung lists it. Some Samsung Keyboard versions only allow Samsung or Google voice providers; in that case use the floating panel above. Samsung Keyboard itself is never replaced.",
            13f,
            false
        ))
        content.addView(button("Open system Voice Input settings").apply {
            setOnClickListener { openVoiceInputSettings() }
        })

        content.addView(space(18))
        content.addView(label("4. Keyboard detection and automatic insertion", 18f, true))
        content.addView(label(
            "Enable Ajmat VoiceFlow under Android Accessibility settings. This opt-in helper detects only whether a keyboard and an editable field are present, and pastes the final transcript into the focused field. It does not read or store what you type. If Android blocks the service for a sideloaded app, open App info > three-dot menu > Allow restricted settings, then enable VoiceFlow in Accessibility. Without this helper, manual floating mode and clipboard paste remain available.",
            13f,
            false
        ))
        content.addView(button("Open App info — Allow restricted settings").apply {
            setOnClickListener {
                VoiceFlowLog.info(this@MainActivity, "Opening app info for Android restricted-settings permission.")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            }
        })
        content.addView(button("Open Accessibility settings").apply {
            setOnClickListener {
                VoiceFlowLog.info(this@MainActivity, "Opening Accessibility settings at user request.")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        content.addView(space(18))
        content.addView(label("5. Optional online fallback", 18f, true))
        content.addView(label(
            "Leave this empty for fully offline recognition. To use online-first mode, enter your own HTTPS endpoint that accepts multipart fields audio and language and returns a JSON response with a text field. Errors/timeouts fall back to the downloaded offline model.",
            13f,
            false
        ))
        endpointInput = EditText(this).apply {
            hint = "https://your-server.example/transcribe"
            setSingleLine(true)
            textSize = 14f
            setText(preferences.getString("endpoint", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        content.addView(endpointInput)

        val savedTimeout = (preferences.getInt("timeout_ms", 20_000) / 1000).coerceIn(5, 60)
        timeoutLabel = label("Online timeout: " + savedTimeout + "s", 13f, false)
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
        content.addView(button("Save online settings").apply {
            setOnClickListener { saveSettings() }
        })

        content.addView(space(18))
        content.addView(label("6. Diagnostics", 18f, true))
        content.addView(label(
            "VoiceFlow records app status, timings, audio byte counts, model failures, and full exception details here. It does not log your spoken words or save recordings.",
            13f,
            false
        ))
        content.addView(button("View logs and diagnostics").apply {
            setOnClickListener { showLogs() }
        })
        content.addView(button("Copy diagnostic logs").apply {
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Ajmat VoiceFlow diagnostics", VoiceFlowLog.read(this@MainActivity)))
                Toast.makeText(this@MainActivity, "Logs copied. Share them when reporting an error.", Toast.LENGTH_LONG).show()
            }
        })
        content.addView(button("Clear diagnostic logs").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear logs?")
                    .setMessage("This removes the local diagnostic history.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        VoiceFlowLog.clear(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Logs cleared.", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        })

        content.addView(space(14))
        content.addView(label(
            "Privacy: audio is recorded only after you start the mic. Temporary files are deleted after recognition. Offline mode sends no audio over the network.",
            12f,
            false
        ))
        setContentView(scroll)
    }

    private fun saveSettings() {
        val endpoint = endpointInput.text.toString().trim()
        if (endpoint.isNotEmpty() && !endpoint.startsWith("https://")) {
            Toast.makeText(this, "Online endpoint must start with https://", Toast.LENGTH_LONG).show()
            return
        }
        preferences.edit()
            .putString("endpoint", endpoint)
            .putInt("timeout_ms", (timeoutSeek.progress + 5) * 1000)
            .apply()
        VoiceFlowLog.info(this, "Online settings saved; endpointConfigured=" + endpoint.isNotEmpty() + ".")
        Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
    }

    private fun startOverlay() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7001)
            Toast.makeText(this, "Allow microphone permission, then tap Start again.", Toast.LENGTH_LONG).show()
            return
        }
        val model = ModelCatalog.byId(preferences.getString("model_id", "small-q5"))
        val filename = preferences.getString("model_file", model.fileName) ?: model.fileName
        val modelFile = File(File(filesDir, "models"), filename)
        if (!modelFile.isFile || modelFile.length() < model.minimumBytes) {
            Toast.makeText(this, "Download the offline model first.", Toast.LENGTH_LONG).show()
            VoiceFlowLog.warn(this, "Overlay launch blocked: selected model is not installed.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
            Toast.makeText(this, "Allow Display over other apps, then tap Start again.", Toast.LENGTH_LONG).show()
            return
        }

        preferences.edit().putBoolean("overlay_enabled", true).apply()
        val intent = Intent(this, VoiceFlowOverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            VoiceFlowLog.info(this, "User requested floating voice panel.")
        } catch (error: Exception) {
            VoiceFlowLog.error(this, "Unable to start floating voice panel.", error)
            Toast.makeText(this, error.message ?: "Could not start VoiceFlow.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlayPermissionSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (error: Exception) {
            VoiceFlowLog.error(this, "Could not open overlay permission settings.", error)
            Toast.makeText(this, "Open Settings > Apps > Special access > Display over other apps.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openVoiceInputSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (error: Exception) {
            VoiceFlowLog.error(this, "Could not open Voice Input settings.", error)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showLogs() {
        val text = TextView(this).apply {
            this.text = VoiceFlowLog.read(this@MainActivity)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scroll = ScrollView(this).apply {
            addView(text)
            layoutParams = LinearLayout.LayoutParams(-1, dp(440))
        }
        AlertDialog.Builder(this)
            .setTitle("VoiceFlow diagnostics")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VoiceFlow logs", text.text))
                Toast.makeText(this, "Logs copied.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun downloadSelectedModel() {
        val model = ModelCatalog.models[modelPicker.selectedItemPosition]
        downloadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        modelStatus.text = "Downloading " + model.title + "…"
        VoiceFlowLog.info(this, "Model download started: " + model.id + ".")

        Thread {
            try {
                val file = ModelDownloader.download(this, model) { downloaded, total ->
                    runOnUiThread {
                        if (total > 0L) {
                            progress.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        }
                        modelStatus.text = "Downloaded " + (downloaded / 1_000_000) + " MB"
                    }
                }
                preferences.edit()
                    .putString("model_id", model.id)
                    .putString("model_file", file.name)
                    .apply()
                VoiceFlowLog.info(this, "Model download/verification complete: " + model.id + ", bytes=" + file.length() + ".")
                runOnUiThread {
                    progress.progress = 100
                    modelStatus.text = "Ready: " + model.title
                    downloadButton.isEnabled = true
                    Toast.makeText(this, "Offline model is ready.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                VoiceFlowLog.error(this, "Model download failed: " + model.id + ".", error)
                runOnUiThread {
                    modelStatus.text = "Download error: " + (error.message ?: "unknown error")
                    downloadButton.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun refreshStatus() {
        if (!::modelStatus.isInitialized) return
        val model = ModelCatalog.byId(preferences.getString("model_id", "small-q5"))
        val filename = preferences.getString("model_file", model.fileName) ?: model.fileName
        val file = File(File(filesDir, "models"), filename)
        modelStatus.text = if (file.exists() && file.length() >= model.minimumBytes) {
            "Offline model ready: " + model.title
        } else {
            "Offline model not downloaded yet."
        }
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        if (!::permissionStatus.isInitialized) return
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val pasteHelper = isPasteHelperEnabled()
        permissionStatus.text = "Microphone: " + (if (microphone) "allowed" else "not allowed") +
            "  •  Display over apps: " + (if (overlay) "allowed" else "not allowed") +
            "  •  Paste Helper: " + (if (pasteHelper) "enabled" else "optional / off")
    }

    private fun isPasteHelperEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val expected = packageName + "/com.ajmat.voiceflow.accessibility.VoiceFlowAccessibilityService"
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestPermissionsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7001)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7002)
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
