package com.ajmat.voiceflow.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/**
 * Records mono 16 kHz PCM straight to a temporary WAV file. Long recordings
 * are streamed to disk instead of being retained in memory.
 */
class AudioRecorder(private val context: Context) {
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

    fun start() {
        check(!recording) { "Already recording." }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        check(minimum > 0) { "AudioRecord does not support 16 kHz mono capture on this device." }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            maxOf(minimum * 2, SAMPLE_RATE)
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Microphone could not be initialized."
        }

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
        recording = true
        recorder.startRecording()

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
                        break
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
        check(captureThread?.isAlive != true) { "Audio capture did not stop cleanly." }
        audioRecord?.release()
        audioRecord = null
        captureThread = null

        val wav = output ?: throw IllegalStateException("Audio output is unavailable.")
        val file = outputFile ?: throw IllegalStateException("Audio file is unavailable.")
        val bytes = dataBytes
        if (bytes < 3_200L) {
            wav.close()
            file.delete()
            output = null
            outputFile = null
            throw IllegalStateException("Recording was too short. Please speak for a little longer.")
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

        captureError?.let {
            file.delete()
            throw IllegalStateException("Microphone capture failed: " + it.message)
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
