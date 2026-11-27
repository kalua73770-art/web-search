package com.ajmat.voiceflow.speech

object WhisperRuntimePolicy {
    const val INFERENCE_TIMEOUT_MS = 120_000L

    fun hasTimedOut(startedAtMs: Long, nowMs: Long): Boolean =
        nowMs - startedAtMs >= INFERENCE_TIMEOUT_MS

    fun normalizeProgress(progress: Int): Int = progress.coerceIn(0, 100)
}
