# Ajmat VoiceFlow

Ajmat VoiceFlow is a native Android voice-typing keyboard. It can type dictated speech directly into the focused text field of messaging apps, browsers, documents, and other Android apps.

## Features

- Android Input Method Editor (IME), not a web page or floating overlay.
- Hindi, English, and Hinglish-friendly multilingual recognition.
- Whisper multilingual offline recognition through whisper.cpp.
- Two downloadable quantized models:
  - **Small Q5** — approximately 190 MB; preferred for accuracy.
  - **Tiny Q5** — approximately 32 MB; smaller and lighter.
- Language controls: Auto, Hindi, and English.
- Long recordings are streamed to a temporary WAV file instead of being held entirely in RAM.
- Optional online-first recognition through a user-configured HTTPS endpoint. Network failure or timeout falls back to the local model.
- Temporary recording files are deleted after transcription.
- No TTS, chat history, account, or audio archive.

## First-time setup

1. Install the APK produced by the Android CI workflow.
2. Open **Ajmat VoiceFlow** and allow microphone permission.
3. Select **Small Q5** (recommended) or **Tiny Q5**, then download it. The model is downloaded once and stored in the app's private storage.
4. Open **Input method settings** and enable **Ajmat VoiceFlow Keyboard**.
5. In any app, open the keyboard and select Ajmat VoiceFlow.
6. Tap the microphone button to start recording; tap **Stop and type** to insert the transcript at the cursor.

Small Q5 needs considerably more working memory than its 190 MB download size. On a device with memory pressure, switch to Tiny Q5 from the app screen and download it instead.

## Online fallback contract

Online mode is optional and is disabled when the endpoint field is empty. When configured, the app sends the WAV recording to the exact HTTPS URL you enter.

Request: `POST multipart/form-data`

| Field | Type | Value |
|---|---|---|
| `audio` | file | mono 16 kHz PCM WAV |
| `language` | text | `auto`, `hi`, or `en` |

Expected success response:

    {"text":"recognized words here"}

The online service should return a 2xx response and must not persist recordings if you want the same no-retention behavior as the offline path. If the service is unavailable, times out, returns an error, or provides an empty transcript, the app automatically tries the local Whisper model.

**This repository does not host a speech-inference server.** You must provide your own compatible HTTPS endpoint to use the online path. The offline keyboard works without that endpoint and without internet after the model has been downloaded.

## Build

Requirements:

- JDK 17
- Android SDK Platform 35
- Android NDK `27.2.12479018`
- CMake `3.22.1`
- Gradle `8.9`

Build and test:

    gradle --no-daemon :app:testDebugUnitTest
    gradle --no-daemon :app:assembleDebug

The native build uses CMake FetchContent to fetch whisper.cpp tag `v1.7.1` and compiles the JNI bridge for `arm64-v8a` and `x86_64`.

The debug APK is written to:

    app/build/outputs/apk/debug/app-debug.apk

GitHub Actions runs the unit tests and APK build on pushes and pull requests, then uploads the APK as the `Ajmat-VoiceFlow-debug` workflow artifact.

## Model sources and licenses

- Whisper.cpp: https://github.com/ggml-org/whisper.cpp (MIT)
- Quantized multilingual models: https://huggingface.co/ggerganov/whisper.cpp (MIT)

The app downloads official model files at first use. Model files are not committed to this repository.

## Notes

- Transcription performance depends on the handset CPU, available RAM, recording length, microphone quality, and chosen model.
- Auto language detection is the best starting option for mixed Hindi-English speech. Mixed-language output quality can still vary with accent and code-switching.
- The keyboard uses Android's microphone permission. Users must enable it from the app before recording from the IME.
- No physical handset is available in the build environment, so real-device microphone, keyboard insertion, and thermal/performance testing must be completed on an Android phone.
