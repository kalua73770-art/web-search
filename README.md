# Ajmat VoiceFlow 1.2.0

Ajmat VoiceFlow is a native Android voice-typing companion built to keep the user's existing keyboard. It does **not** install or replace Samsung Keyboard.

## Main workflow

1. Keep Samsung Keyboard selected as your normal keyboard.
2. Open Ajmat VoiceFlow once and download Tiny Q5 or Small Q5.
3. Allow **Display over other apps**.
4. Focus a text field in WhatsApp, browser, documents, or another app.
5. Start the VoiceFlow floating panel, speak, then press Stop.
6. VoiceFlow copies the recognized text. If the optional Paste Helper is enabled, it also attempts Android's paste action into the focused field. Otherwise use Samsung Keyboard's clipboard/paste key.

The floating panel is the primary voice-typing route. A standard Android `RecognitionService` is also registered; the Samsung/OEM keyboard decides whether a third-party provider is exposed in its voice-input menu.

## Version 1.2 changes

- Native Whisper inference is compiled with `-O3` even for the debug-signed APK. The previous debug APK used an unoptimized native build, which can make CPU inference much slower on phones.
- Whisper's progress callback is now connected to the floating panel and local diagnostics.
- The overlay now has a cooperative **Cancel** action; it requests a safe native abort rather than leaving a permanent Processing state.
- Native inference has a 120-second safety timeout. On expiry the app cancels the current graph computation and shows a useful error instead of waiting indefinitely.
- Decoding uses greedy `best_of=1` and no temperature-retry loop to reduce unnecessary CPU work for voice typing.
- Native logs include CPU/system information, inference start/end, progress, segment count, and native error codes. Dictated text is never written to logs.
- Optional Paste Helper permission state is shown in the app. Instructions for Android's **Allow restricted settings** gate are included; clipboard paste remains usable without Accessibility access.
- App version is 1.2.0 / versionCode 3.

## Models

- **Tiny Q5** — approximately 32 MB download; lighter and quicker to load, but less accurate for Hindi and mixed-language speech.
- **Small Q5** — approximately 190 MB download; generally preferable for Hindi accuracy, but requires more RAM and CPU time.

Download models from the app screen. Model files are stored inside app-private storage and checked against SHA-1 before being accepted. The currently selected model is not included in the APK.

## Language selection

- **Auto detect (Hindi + English)**: best starting point for Hinglish/code-switching.
- **Hindi only**: use when speaking mostly Hindi.
- **English only**: use when speaking English.

Automatic language detection is not perfect on very short clips. For best results, speak a complete phrase for at least 2–3 seconds, avoid background noise, and keep the phone microphone unobstructed.

## Permissions

Required for floating dictation:
- Microphone
- Display over other apps
- Notifications (on Android 13+; needed for the foreground microphone service notification)

Optional:
- Accessibility / Paste Helper. This permission is **not required** for recording or transcription. Without it, the recognized text is placed on the clipboard and can be pasted through Samsung Keyboard.

### Android restricted-settings warning

On some Android/Samsung versions, enabling an Accessibility service for a sideloaded app is blocked until the user explicitly allows it:

1. Settings → Apps → Ajmat VoiceFlow → tap the three-dot menu.
2. Choose **Allow restricted settings**.
3. Return to Settings → Accessibility → Installed apps → Ajmat VoiceFlow Paste Helper → Allow.

The exact wording/menu placement depends on the Android/Samsung version. **Turning Play Protect off does not grant Accessibility access** and is not required for VoiceFlow to run. Keep Play Protect enabled when possible.

Some secure or protected text fields block clipboard paste or Accessibility paste. VoiceFlow does not bypass those protections.

## Diagnostics and recovery

Open **View logs and diagnostics** to view:
- recording start/stop, duration, and captured audio bytes;
- model download, checksum, and load timing;
- native CPU/system information;
- Whisper progress percentages and number of recognized segments;
- native inference duration and error codes;
- cancellation/timeout events and stack traces.

The logs intentionally do not contain dictated text or recorded audio. They are stored locally in app-private storage. Use **Copy diagnostic logs** to share them.

The overlay shows:
- live engine progress when Whisper reports it;
- elapsed inference time;
- a Cancel button while recognition is running;
- actionable error text on failures.

Native cancellation is cooperative: Whisper stops at a safe compute boundary, so the overlay may take a short moment to return after Cancel is pressed. Normal inference is limited to 120 seconds.

## Online fallback

Leave the endpoint empty for fully offline recognition. If configured, VoiceFlow first sends a temporary WAV to the supplied HTTPS endpoint using multipart fields:
- `audio`: mono 16 kHz PCM WAV
- `language`: `auto`, `hi`, or `en`

Expected response:

    {"text":"recognized words here"}

Any connection/HTTP/response failure falls back to the downloaded offline model. This repository does not host a speech-inference service. Use a no-retention endpoint if you want recordings not to be stored remotely.

## Build

Requirements:
- JDK 17
- Android SDK Platform 35
- Android NDK `27.2.12479018`
- CMake `3.22.1`
- Gradle `8.9`

Commands:

    gradle --no-daemon :app:testDebugUnitTest
    gradle --no-daemon :app:assembleDebug

The native build fetches whisper.cpp tag `v1.7.1`, compiles for `arm64-v8a` and `x86_64`, and uses optimized native compile flags even for the debug APK. GitHub Actions runs unit tests and assembles the APK artifact `Ajmat-VoiceFlow-debug`.

Output:

    app/build/outputs/apk/debug/app-debug.apk

## Real-device testing

CI verifies Kotlin unit tests and Android native APK assembly. It cannot exercise a physical Samsung microphone, measure live recognition latency on the handset, or guarantee that One UI lists third-party recognition providers. These require handset verification.

Whisper.cpp is MIT licensed: https://github.com/ggml-org/whisper.cpp
Multilingual quantized model files: https://huggingface.co/ggerganov/whisper.cpp
