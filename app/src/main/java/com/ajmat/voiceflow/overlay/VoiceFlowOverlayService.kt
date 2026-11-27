package com.ajmat.voiceflow.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.ajmat.voiceflow.MainActivity
import com.ajmat.voiceflow.accessibility.VoiceFlowAccessibilityService
import com.ajmat.voiceflow.audio.AudioRecorder
import com.ajmat.voiceflow.logging.VoiceFlowLog
import com.ajmat.voiceflow.speech.ModelCatalog
import com.ajmat.voiceflow.speech.TranscriptionPipeline
import com.ajmat.voiceflow.speech.WhisperEngine
import java.io.File
import java.util.concurrent.Executors

class VoiceFlowOverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.ajmat.voiceflow.action.STOP_OVERLAY"
        @Volatile private var activeInstance: VoiceFlowOverlayService? = null
        private const val CHANNEL_ID = "voiceflow_overlay"

        fun onKeyboardVisibilityChanged(visible: Boolean) {
            val service = activeInstance ?: return
            service.mainHandler.post {
                val enabled = service.getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
                    .getBoolean("overlay_enabled", false)
                if (!enabled) return@post
                if (visible) {
                    service.showOverlay()
                } else if (!service.recording && !service.processing) {
                    service.hideOverlay()
                }
            }
        }
        private const val NOTIFICATION_ID = 7101
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var statusView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var startStopButton: Button? = null
    private var cancelButton: Button? = null
    private var pasteButton: Button? = null
    private var recorder: AudioRecorder? = null
    @Volatile private var recording = false
    @Volatile private var processing = false
    @Volatile private var closeWhenFinished = false
    @Volatile private var cancellationRequested = false
    @Volatile private var currentProgress = 0
    private var lastTranscript = ""
    private var processingStartedAt = 0L

    private val progressTicker = object : Runnable {
        override fun run() {
            if (processing) {
                val elapsedSeconds = (System.currentTimeMillis() - processingStartedAt) / 1000L
                if (cancellationRequested) {
                    setStatus("Stopping safely… " + elapsedSeconds + "s elapsed.")
                } else {
                    val progressText = if (currentProgress > 0) {
                        "Whisper: " + currentProgress + "% · "
                    } else {
                        "Whisper is warming up · "
                    }
                    setStatus(progressText + elapsedSeconds + "s elapsed. Cancel is available.")
                }
                mainHandler.postDelayed(this, 2_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        activeInstance = this
        VoiceFlowLog.info(this, "Floating voice service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("voiceflow", Context.MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply()
            stopEverything()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (error: Exception) {
            getSharedPreferences("voiceflow", Context.MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply()
            VoiceFlowLog.error(this, "Could not start microphone foreground service.", error)
            Toast.makeText(this, "VoiceFlow could not start. Open the app and allow microphone access.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            VoiceFlowLog.warn(this, "Overlay permission missing; stopping floating panel.")
            Toast.makeText(this, "Allow Display over other apps for Ajmat VoiceFlow.", Toast.LENGTH_LONG).show()
            finishAndStop()
            return START_NOT_STICKY
        }
        showOverlay()
        VoiceFlowAccessibilityService.instance?.refreshKeyboardState()
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(0xEE172B2A.toInt())
            elevation = dp(12).toFloat()
        }
        statusView = TextView(this).apply {
            text = "READY"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            visibility = ProgressBar.GONE
        }
        startStopButton = Button(this).apply {
            text = "🎙"
            contentDescription = "Start voice typing"
            textSize = 17f
            minWidth = dp(48)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnClickListener { if (recording) stopAndTranscribe() else startRecording() }
        }
        cancelButton = Button(this).apply {
            text = "×"
            contentDescription = "Cancel recognition / close"
            minWidth = dp(38)
            isAllCaps = false
            isEnabled = true
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                when {
                    processing -> cancelTranscription()
                    recording -> stopAndTranscribe()
                    else -> hideOverlay()
                }
            }
        }
        pasteButton = Button(this).apply {
            text = "✓"
            contentDescription = "Insert transcript"
            minWidth = dp(38)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnClickListener { if (recording) stopAndTranscribe() else pasteTranscript() }
        }
        panel.addView(statusView, LinearLayout.LayoutParams(dp(48), dp(36)))
        panel.addView(startStopButton, LinearLayout.LayoutParams(dp(48), dp(42)))
        panel.addView(cancelButton, LinearLayout.LayoutParams(dp(38), dp(42)))
        panel.addView(pasteButton, LinearLayout.LayoutParams(dp(38), dp(42)))
        overlayView = panel

        val layout = WindowManager.LayoutParams(
            dp(184),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(180)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        statusView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(4)) moved = true
                    if (moved) {
                        layout.x = (startX - dx).coerceAtLeast(0)
                        layout.y = (startY + dy).coerceAtLeast(0)
                        try { windowManager?.updateViewLayout(panel, layout) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        try {
            windowManager?.addView(panel, layout)
            VoiceFlowLog.info(this, "Compact draggable voice control attached; Samsung Keyboard focus is preserved.")
        } catch (error: Exception) {
            overlayView = null
            VoiceFlowLog.error(this, "Unable to attach compact floating control.", error)
            finishAndStop()
        }
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        overlayView = null
        statusView = null
        progressBar = null
        startStopButton = null
        cancelButton = null
        pasteButton = null
        VoiceFlowLog.info(this, "Floating control hidden; background service remains enabled.")
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone permission missing. Open VoiceFlow and allow Microphone.")
            return
        }

        val preferences = getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
        val model = ModelCatalog.byId(preferences.getString("model_id", "base-q5"))
        val modelFile = File(File(filesDir, "models"), model.fileName)
        if (!modelFile.isFile || modelFile.length() < model.minimumBytes) {
            setStatus("Offline model missing. Download it from the app first.")
            VoiceFlowLog.warn(this, "Recording blocked because selected model is missing.")
            return
        }

        try {
            recorder = AudioRecorder(this).also { it.start() }
            recording = true
            lastTranscript = ""
            currentProgress = 0
            progressBar?.progress = 0
            progressBar?.visibility = ProgressBar.GONE
            pasteButton?.isEnabled = true
            pasteButton?.contentDescription = "Finish recording and transcribe"
            cancelButton?.isEnabled = true
            startStopButton?.text = "■ Stop"
            startStopButton?.isEnabled = true
            setStatus("Listening")
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            VoiceFlowLog.error(this, "Could not start microphone from floating panel.", error)
            setStatus(error.message ?: "Could not start microphone.")
        }
    }

    private fun stopAndTranscribe() {
        if (!recording || processing) return
        recording = false
        processing = true
        closeWhenFinished = false
        cancellationRequested = false
        currentProgress = 0
        progressBar?.progress = 0
        progressBar?.visibility = ProgressBar.VISIBLE
        startStopButton?.isEnabled = false
        startStopButton?.text = "…"
        startStopButton?.contentDescription = "Transcribing"
        pasteButton?.isEnabled = false
        cancelButton?.isEnabled = true
        cancelButton?.text = "×"
        cancelButton?.contentDescription = "Cancel recognition"

        val audioFile = try {
            recorder?.stop() ?: throw IllegalStateException("No active recording.")
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            processing = false
            startStopButton?.isEnabled = true
            startStopButton?.text = "🎙 Start"
            cancelButton?.isEnabled = true
            cancelButton?.text = "×"
            VoiceFlowLog.error(this, "Microphone stop failed.", error)
            setStatus(error.message ?: "Recording failed.")
            return
        }

        recorder = null
        val language = getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
            .getString("language", "auto") ?: "auto"
        processingStartedAt = System.currentTimeMillis()
        setStatus("Audio captured. Starting optimized Whisper…")
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.postDelayed(progressTicker, 2_000L)
        VoiceFlowLog.info(this, "Overlay stopped recording; starting optimized transcription.")

        worker.execute {
            try {
                val result = TranscriptionPipeline.transcribe(
                    this,
                    audioFile,
                    language,
                    onStatus = { message -> mainHandler.post { if (processing) setStatus(message) } },
                    onProgress = { value ->
                        mainHandler.post {
                            currentProgress = value
                            progressBar?.progress = value
                            if (processing && !cancellationRequested) {
                                setStatus("Whisper is processing audio: " + value + "%")
                            }
                        }
                    }
                )
                mainHandler.post {
                    processing = false
                    cancellationRequested = false
                    mainHandler.removeCallbacks(progressTicker)
                    if (closeWhenFinished) {
                        finishAndStop()
                        return@post
                    }
                    lastTranscript = result.text
                    startStopButton?.isEnabled = true
                    startStopButton?.text = "🎙 Start"
                    cancelButton?.isEnabled = true
                    cancelButton?.text = "×"
                    pasteButton?.isEnabled = true
                    progressBar?.progress = 100
                    setClipboard(result.text)
                    setStatus("Done in " + (result.elapsedMs / 1000.0) + "s. Text copied. Tap Paste.")
                    tryAutoPaste()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    processing = false
                    cancellationRequested = false
                    mainHandler.removeCallbacks(progressTicker)
                    if (closeWhenFinished) {
                        finishAndStop()
                        return@post
                    }
                    startStopButton?.isEnabled = true
                    startStopButton?.text = "🎙 Start"
                    cancelButton?.isEnabled = true
                    cancelButton?.text = "×"
                    pasteButton?.isEnabled = false
                    val message = error.message ?: "Transcription failed."
                    setStatus("ERROR: " + message)
                    VoiceFlowLog.error(this, "Overlay transcription UI received failure.", error)
                    Toast.makeText(this, "VoiceFlow: " + message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cancelTranscription() {
        if (!processing) return
        cancellationRequested = true
        cancelButton?.isEnabled = false
        cancelButton?.text = "…" 
        setStatus("Cancellation requested. Waiting for the native engine to stop safely…")
        VoiceFlowLog.warn(this, "User requested inference cancellation.")
        WhisperEngine.cancelTranscription()
    }

    private fun tryAutoPaste() {
        val service = VoiceFlowAccessibilityService.instance ?: return
        val pasted = service.pasteIntoFocusedField()
        if (pasted) {
            pasteButton?.isEnabled = false
            setStatus("Text pasted into the focused field.")
        } else {
            setStatus("Text copied. Tap Paste or use Samsung Keyboard's clipboard.")
        }
    }

    private fun pasteTranscript() {
        if (lastTranscript.isBlank()) {
            setStatus("There is no transcript to paste yet.")
            return
        }
        setClipboard(lastTranscript)
        val service = VoiceFlowAccessibilityService.instance
        if (service == null) {
            setStatus("Copied to clipboard. Use Samsung Keyboard's clipboard/paste key.")
            return
        }
        if (service.pasteIntoFocusedField()) {
            pasteButton?.isEnabled = false
            setStatus("Text pasted.")
        } else {
            setStatus("Clipboard ready, but this field refused automatic paste.")
        }
    }

    private fun setClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ajmat VoiceFlow", text))
        VoiceFlowLog.info(this, "Transcript copied to clipboard; dictated content withheld from logs.")
    }

    private fun setStatus(message: String) {
        val compactStatus = when {
            message.startsWith("Listening", ignoreCase = true) -> "REC"
            message.contains("Whisper", ignoreCase = true) ||
                message.contains("transcri", ignoreCase = true) ||
                message.contains("Audio captured", ignoreCase = true) -> "WORK"
            message.contains("pasted", ignoreCase = true) -> "DONE"
            message.startsWith("Done", ignoreCase = true) -> "DONE"
            message.contains("copied", ignoreCase = true) -> "COPY"
            message.startsWith("ERROR", ignoreCase = true) -> "ERROR"
            message.contains("cancel", ignoreCase = true) -> "STOP"
            else -> "READY"
        }
        statusView?.text = compactStatus
    }

    private fun stopEverything() {
        if (processing) {
            closeWhenFinished = true
            setStatus("Stopping recognition before closing…")
            cancelTranscription()
            return
        }
        if (recording) {
            recorder?.cancel()
            recording = false
        }
        finishAndStop()
    }

    private fun finishAndStop() {
        getSharedPreferences("voiceflow", Context.MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply()
        mainHandler.removeCallbacks(progressTicker)
        hideOverlay()
        processing = false
        recording = false
        cancellationRequested = false
        recorder = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VoiceFlowLog.info(this, "Floating voice panel closed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VoiceFlow microphone", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this, 2, Intent(this, VoiceFlowOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ajmat VoiceFlow is ready")
            .setContentText("VoiceFlow stays ready. Mic runs only after you tap it.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopService).build())
            .build()
    }

    private fun weighted(weight: Float) =
        LinearLayout.LayoutParams(0, dp(44), weight).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        mainHandler.removeCallbacks(progressTicker)
        if (recording) recorder?.cancel()
        if (processing) WhisperEngine.cancelTranscription()
        worker.shutdownNow()
        try { if (overlayView != null) windowManager?.removeView(overlayView) } catch (_: Exception) { }
        overlayView = null
        super.onDestroy()
    }
}
