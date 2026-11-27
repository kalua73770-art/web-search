package com.ajmat.voiceflow.logging

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Local diagnostics only. Never records dictated text or microphone samples.
 */
object VoiceFlowLog {
    private const val FILE_NAME = "voiceflow.log"
    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun info(context: Context, message: String) = write(context, "INFO", message, null)
    fun warn(context: Context, message: String) = write(context, "WARN", message, null)
    fun error(context: Context, message: String, throwable: Throwable? = null) =
        write(context, "ERROR", message, throwable)

    fun read(context: Context): String = synchronized(lock) {
        logFile(context).let { if (it.exists()) it.readText() else "No logs yet." }
    }

    fun clear(context: Context) = synchronized(lock) {
        logFile(context).delete()
        info(context, "Diagnostic log cleared.")
    }

    fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            VoiceFlowLog.error(context, "Uncaught exception on " + thread.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, level: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(file.parentFile, FILE_NAME + ".1")
                backup.delete()
                file.renameTo(backup)
            }
            file.appendText(
                formatter.format(LocalDateTime.now()) + " [" + level + "] " + message + "\n" +
                    (throwable?.let { stackTrace(it) + "\n" } ?: "")
            )
        }
    }

    private fun logFile(context: Context) =
        File(File(context.filesDir, "logs"), FILE_NAME)

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
