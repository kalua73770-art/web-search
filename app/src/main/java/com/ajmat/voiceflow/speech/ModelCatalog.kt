package com.ajmat.voiceflow.speech

data class ModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSha1: String,
    val minimumBytes: Long,
    val approximateSize: String
)

object ModelCatalog {
    private const val REVISION = "5359861"

    val models = listOf(
        ModelSpec(
            id = "base-q5",
            title = "Base Q5 — balanced Hindi + English",
            fileName = "ggml-base-q5_1.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/" + REVISION + "/ggml-base-q5_1.bin",
            expectedSha1 = "a3733eda680ef76256db5fc5dd9de8629e62c5e7",
            minimumBytes = 55_000_000L,
            approximateSize = "60 MB"
        ),
        ModelSpec(
            id = "small-q5",
            title = "Small Q5 — best accuracy",
            fileName = "ggml-small-q5_1.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/" + REVISION + "/ggml-small-q5_1.bin",
            expectedSha1 = "6fe57ddcfdd1c6b07cdcc73aaf620810ce5fc771",
            minimumBytes = 150_000_000L,
            approximateSize = "190 MB"
        )
    )

    fun byId(id: String?): ModelSpec =
        models.firstOrNull { it.id == ModelSelectionPolicy.resolveModelId(id) } ?: models.first()
}
