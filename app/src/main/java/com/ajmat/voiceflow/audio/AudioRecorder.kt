package com.ajmat.voiceflow.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.ajmat.voiceflow.logging.VoiceFlowLog
import java.io.File
import java.io.RandomAccessFile

/**
 * Records mono 16 kHz PCM into a temporary WAV file. The capture loop writes
 * continuously to disk, keeping long recordings out of the Java heap.
 *
 * Optional attributionContext is supplied by Android RecognitionService callers
 * on API 31+ so microphone access is attributed to the voice-input caller.
 */
class AudioRecorder(
    private val context: Context,
    private val attributionContext: Context? = null
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var outputFile: File? = null
    private var output: RandomAccessFile? = null
    @Volatile private var dataBytes = 0L
    @Volatile private var captureError: Throwable? = null
    private var recordingStartedAt = 0L

    private fun createAudioRecord(bufferSize: Int): Pair<AudioRecord, Int> {
        var lastFailure: Throwable? = null
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attributionContext != null) {
                    AudioRecord.Builder()
                        .setContext(attributionContext)
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .setEncoding(AUDIO_FORMAT)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                }
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate to source
                }
                candidate.release()
                candidate = null
                lastFailure = IllegalStateException("AudioRecord source " + source + " did not initialize.")
            } catch (error: Exception) {
                lastFailure = error
                try { candidate?.release() } catch (_: Exception) { }
            }
        }
        throw IllegalStateException("Android could not initialize voice-recognition or microphone capture.", lastFailure)
    }

    fun start() {
        check(!recording) { "Already recording." }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        check(minimum > 0) { "This device does not support 16 kHz mono microphone capture (code " + minimum + ")." }

        val bufferSize = maxOf(minimum * 2, SAMPLE_RATE)
        val (recorder, audioSource) = createAudioRecord(bufferSize)
        VoiceFlowLog.info(
            context,
            "Using microphone source=" +
                (if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "MIC") +
                ", sampleRate=" + SAMPLE_RATE + ", bufferBytes=" + bufferSize + "."
        )

        val directory = File(context.cacheDir, "voiceflow-audio").apply { mkdirs() }
        val file = File.createTempFile("voice-", ".wav", directory)
        val wav = RandomAccessFile(file, "rw")
        wav.setLength(0)
        wav.write(ByteArray(44))

        audioRecord = recorder
        outputFile = file
        output = wav
        dataBytes = 0L
        captureError = null
        recordingStartedAt = System.currentTimeMillis()
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            cancel()
            "Android did not enter the microphone recording state."
        }
        recording = true
        VoiceFlowLog.info(context, "Microphone recording started at 16 kHz mono.")

        captureThread = Thread({
            val samples = ShortArray(2048)
            val bytes = ByteArray(samples.size * 2)
            try {
                while (recording) {
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        var index = 0
                        for (i in 0 until count) {
                            val sample = samples[i].toInt()
                            bytes[index++] = (sample and 0xff).toByte()
                            bytes[index++] = ((sample shr 8) and 0xff).toByte()
                        }
                        wav.write(bytes, 0, index)
                        dataBytes += index
                    } else if (
                        count == AudioRecord.ERROR_DEAD_OBJECT ||
                        count == AudioRecord.ERROR_INVALID_OPERATION ||
                        count == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        throw IllegalStateException("Microphone read failed with AudioRecord code " + count + ".")
                    }
                }
            } catch (error: Throwable) {
                captureError = error
            }
        }, "AjmatVoiceFlow-Audio").apply { start() }
    }

    @Synchronized
    fun stop(): File {
        check(recording) { "Recording is not active." }
        recording = false
        try { audioRecord?.stop() } catch (_: IllegalStateException) { }

        captureThread?.join(5_000)
        check(captureThread?.isAlive != true) {
            cancel()
            "Microphone capture thread did not stop within 5 seconds."
        }
        audioRecord?.release()
        audioRecord = null
        captureThread = null

        val wav = output ?: throw IllegalStateException("Audio output is unavailable.")
        val file = outputFile ?: throw IllegalStateException("Audio file is unavailable.")
        val bytes = dataBytes
        val durationMs = System.currentTimeMillis() - recordingStartedAt
        if (bytes < 3_200L) {
            wav.close()
            file.delete()
            output = null
            outputFile = null
            throw IllegalStateException("No usable microphone audio was captured (" + bytes + " bytes). Check microphone access and try again.")
        }

        wav.seek(0)
        writeAscii(wav, "RIFF")
        writeIntLe(wav, (bytes + 36L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        writeAscii(wav, "WAVE")
        writeAscii(wav, "fmt ")
        writeIntLe(wav, 16)
        writeShortLe(wav, 1)
        writeShortLe(wav, 1)
        writeIntLe(wav, SAMPLE_RATE)
        writeIntLe(wav, SAMPLE_RATE * 2)
        writeShortLe(wav, 2)
        writeShortLe(wav, 16)
        writeAscii(wav, "data")
        writeIntLe(wav, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        wav.close()
        output = null
        outputFile = null

        VoiceFlowLog.info(context, "Recording stopped; durationMs=" + durationMs + ", pcmBytes=" + bytes + ".")
        captureError?.let {
            file.delete()
            VoiceFlowLog.error(context, "Microphone capture thread failed.", it)
            throw IllegalStateException("Microphone capture failed: " + it.message, it)
        }
        return file
    }

    @Synchronized
    fun cancel() {
        if (recording) {
            recording = false
            try { audioRecord?.stop() } catch (_: Exception) { }
            captureThread?.join(2_000)
        }
        try { audioRecord?.release() } catch (_: Exception) { }
        try { output?.close() } catch (_: Exception) { }
        audioRecord = null
        captureThread = null
        output = null
        outputFile?.delete()
        outputFile = null
        VoiceFlowLog.info(context, "Recording cancelled; temporary file removed.")
    }

    private fun writeAscii(file: RandomAccessFile, value: String) {
        file.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeIntLe(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write((value ushr 8) and 0xff)
        file.write((value ushr 16) and 0xff)
        file.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff)
        file.write((value ushr 8) and 0xff)
    }
}
