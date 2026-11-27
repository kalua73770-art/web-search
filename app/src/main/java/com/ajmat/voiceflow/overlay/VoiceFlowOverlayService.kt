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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ajmat.voiceflow.MainActivity
import com.ajmat.voiceflow.accessibility.VoiceFlowAccessibilityService
import com.ajmat.voiceflow.audio.AudioRecorder
import com.ajmat.voiceflow.logging.VoiceFlowLog
import com.ajmat.voiceflow.speech.ModelCatalog
import com.ajmat.voiceflow.speech.TranscriptionPipeline
import java.io.File
import java.util.concurrent.Executors

class VoiceFlowOverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.ajmat.voiceflow.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "voiceflow_overlay"
        private const val NOTIFICATION_ID = 7101
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var statusView: TextView? = null
    private var startStopButton: Button? = null
    private var pasteButton: Button? = null
    private var recorder: AudioRecorder? = null
    @Volatile private var recording = false
    @Volatile private var processing = false
    private var lastTranscript = ""
    private var processingStartedAt = 0L

    private val progressTicker = object : Runnable {
        override fun run() {
            if (processing) {
                val elapsedSeconds = (System.currentTimeMillis() - processingStartedAt) / 1000L
                setStatus(
                    "Recognizing on this phone… " + elapsedSeconds +
                        "s elapsed. Open VoiceFlow Logs for details."
                )
                mainHandler.postDelayed(this, 8_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        VoiceFlowLog.info(this, "Floating voice service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
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
            VoiceFlowLog.error(this, "Could not start microphone foreground service.", error)
            Toast.makeText(this, "VoiceFlow could not start. Open the app and allow microphone access.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            VoiceFlowLog.warn(this, "Overlay permission missing; stopping floating panel.")
            Toast.makeText(this, "Allow Display over other apps for Ajmat VoiceFlow.", Toast.LENGTH_LONG).show()
            stopEverything()
            return START_NOT_STICKY
        }
        showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF7F9FA.toInt())
            elevation = dp(10).toFloat()
        }
        val heading = TextView(this).apply {
            text = "Ajmat VoiceFlow"
            textSize = 15f
            setTextColor(0xFF162D2B.toInt())
            setPadding(0, 0, 0, dp(5))
        }
        statusView = TextView(this).apply {
            text = "Ready. Keep your cursor in a text field."
            textSize = 12f
            setTextColor(0xFF4D5D5B.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startStopButton = Button(this).apply {
            text = "🎙 Start"
            isAllCaps = false
            setOnClickListener { if (recording) stopAndTranscribe() else startRecording() }
        }
        pasteButton = Button(this).apply {
            text = "Paste"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { pasteTranscript() }
        }
        val closeButton = Button(this).apply {
            text = "×"
            isAllCaps = false
            setOnClickListener { stopEverything() }
        }
        buttons.addView(startStopButton, weighted(1.2f))
        buttons.addView(pasteButton, weighted(1f))
        buttons.addView(closeButton, weighted(0.45f))
        panel.addView(heading)
        panel.addView(statusView)
        panel.addView(buttons)

        val layout = WindowManager.LayoutParams(
            dp(300),
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
            y = dp(150)
        }

        try {
            windowManager?.addView(panel, layout)
            overlayView = panel
            VoiceFlowLog.info(this, "Floating panel attached; it does not take focus from Samsung Keyboard.")
        } catch (error: Exception) {
            VoiceFlowLog.error(this, "Unable to attach floating panel.", error)
            stopEverything()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone permission is missing. Open VoiceFlow and allow it.")
            return
        }

        val preferences = getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
        val model = ModelCatalog.byId(preferences.getString("model_id", "small-q5"))
        val filename = preferences.getString("model_file", model.fileName) ?: model.fileName
        val modelFile = File(File(filesDir, "models"), filename)
        if (!modelFile.isFile || modelFile.length() < model.minimumBytes) {
            setStatus("Offline model is missing. Download it from the VoiceFlow app first.")
            VoiceFlowLog.warn(this, "Recording blocked because selected model is missing.")
            return
        }

        try {
            recorder = AudioRecorder(this).also { it.start() }
            recording = true
            lastTranscript = ""
            pasteButton?.isEnabled = false
            startStopButton?.text = "■ Stop"
            setStatus("Listening… speak, then press Stop.")
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
        startStopButton?.isEnabled = false
        startStopButton?.text = "Processing…"

        val audioFile = try {
            recorder?.stop() ?: throw IllegalStateException("No active recording.")
        } catch (error: Exception) {
            recorder?.cancel()
            recorder = null
            processing = false
            startStopButton?.isEnabled = true
            startStopButton?.text = "🎙 Start"
            VoiceFlowLog.error(this, "Microphone stop failed.", error)
            setStatus(error.message ?: "Recording failed.")
            return
        }
        recorder = null
        val language = getSharedPreferences("voiceflow", Context.MODE_PRIVATE)
            .getString("language", "auto") ?: "auto"
        processingStartedAt = System.currentTimeMillis()
        setStatus("Audio captured. Loading model and recognizing speech…")
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.postDelayed(progressTicker, 8_000L)
        VoiceFlowLog.info(this, "Overlay stopped recording; starting transcription.")

        worker.execute {
            try {
                val result = TranscriptionPipeline.transcribe(this, audioFile, language) { message ->
                    mainHandler.post { setStatus(message) }
                }
                mainHandler.post {
                    processing = false
                    mainHandler.removeCallbacks(progressTicker)
                    lastTranscript = result.text
                    startStopButton?.isEnabled = true
                    startStopButton?.text = "🎙 Start"
                    pasteButton?.isEnabled = true
                    setClipboard(result.text)
                    setStatus("Done in " + (result.elapsedMs / 1000.0) + "s. Tap Paste.")
                    tryAutoPaste()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    processing = false
                    mainHandler.removeCallbacks(progressTicker)
                    startStopButton?.isEnabled = true
                    startStopButton?.text = "🎙 Start"
                    pasteButton?.isEnabled = false
                    setStatus("ERROR: " + (error.message ?: "Transcription failed.") + " Open Logs in the app.")
                    Toast.makeText(this, "VoiceFlow failed. Open Logs in the app for details.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun tryAutoPaste() {
        val service = VoiceFlowAccessibilityService.instance ?: return
        val pasted = service.pasteIntoFocusedField()
        if (pasted) {
            pasteButton?.isEnabled = false
            setStatus("Text pasted into the focused field.")
        } else {
            setStatus("Copied. Could not auto-paste here; tap Paste or use the field's Paste menu.")
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
            setStatus("Copied to clipboard. Enable Paste Helper in VoiceFlow or paste manually.")
            return
        }
        if (service.pasteIntoFocusedField()) {
            pasteButton?.isEnabled = false
            setStatus("Text pasted.")
        } else {
            setStatus("Clipboard ready, but the focused text field refused paste.")
        }
    }

    private fun setClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ajmat VoiceFlow", text))
        VoiceFlowLog.info(this, "Transcript copied to clipboard; text content is not written to logs.")
    }

    private fun setStatus(message: String) {
        statusView?.text = message
    }

    private fun stopEverything() {
        mainHandler.removeCallbacks(progressTicker)
        if (recording) recorder?.cancel()
        recording = false
        processing = false
        recorder = null
        try {
            if (overlayView != null) windowManager?.removeView(overlayView)
        } catch (_: Exception) { }
        overlayView = null
        statusView = null
        startStopButton = null
        pasteButton = null
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
            .setContentText("Floating voice typing is active. Tap Stop to close.")
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
        mainHandler.removeCallbacks(progressTicker)
        if (recording) recorder?.cancel()
        worker.shutdownNow()
        try { if (overlayView != null) windowManager?.removeView(overlayView) } catch (_: Exception) { }
        overlayView = null
        super.onDestroy()
    }
}
