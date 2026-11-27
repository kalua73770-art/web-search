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
            id = "small-q5",
            title = "Small Q5 — best accuracy",
            fileName = "ggml-small-q5_1.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/" + REVISION + "/ggml-small-q5_1.bin",
            expectedSha1 = "6fe57ddcfdd1c6b07cdcc73aaf620810ce5fc771",
            minimumBytes = 150_000_000L,
            approximateSize = "190 MB"
        ),
        ModelSpec(
            id = "tiny-q5",
            title = "Tiny Q5 — lighter / faster",
            fileName = "ggml-tiny-q5_1.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/" + REVISION + "/ggml-tiny-q5_1.bin",
            expectedSha1 = "2827a03e495b1ed3048ef28a6a4620537db4ee51",
            minimumBytes = 25_000_000L,
            approximateSize = "32 MB"
        )
    )

    fun byId(id: String?): ModelSpec =
        models.firstOrNull { it.id == id } ?: models.first()
}
