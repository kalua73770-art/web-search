# Ajmat VoiceFlow

Ajmat VoiceFlow is a native Android voice-typing companion designed to **keep the user's existing keyboard**. It does not replace Samsung Keyboard or install a second typing keyboard.

The app offers two independent ways to dictate:

1. **System voice-input provider** — registers an Android `RecognitionService`. In Android Voice Input settings, choose Ajmat VoiceFlow if the device exposes third-party providers. Samsung Keyboard firmware varies; some Samsung releases only expose Samsung Voice Input and Google Voice Typing.
2. **Floating voice panel** — works independently of the selected keyboard. Keep Samsung Keyboard selected, focus a text field, tap the floating mic, speak, then tap Stop. The transcript is copied to the clipboard; the optional Paste Helper can send Android's paste action to the currently focused text control. Without the helper, paste using Samsung Keyboard's clipboard/paste command.

## Recognition and models

- Multilingual Whisper using whisper.cpp, compiled for `arm64-v8a` and `x86_64`.
- Auto language detection plus Hindi and English selection.
- **Small Q5** (about 190 MB on disk) for stronger accuracy.
- **Tiny Q5** (about 32 MB) for lower memory use and faster loading.
- The models are downloaded into app-private storage and checked against the published SHA-1 hash.
- Long recordings are streamed into a temporary mono 16 kHz WAV file rather than buffered completely in memory.
- Optional online-first route uses a user-supplied HTTPS endpoint; failures/timeouts fall back to local Whisper.

The quantized model inventory and hashes are published by the official whisper.cpp model repository: https://huggingface.co/ggerganov/whisper.cpp

## First-time setup

1. Install the APK and open Ajmat VoiceFlow.
2. Allow microphone permission.
3. Download Small Q5, or choose Tiny Q5 if you prefer a smaller memory footprint.
4. Keep Samsung Keyboard selected as your normal keyboard.
5. Allow **Display over other apps** and tap **Start floating voice panel**.
6. Open WhatsApp, browser, document, or another app and focus the text field before starting dictation.
7. Tap Start, speak, then Stop. VoiceFlow shows processing status, copies the transcript, and tries the optional paste action.
8. To enable automatic paste into the focused text field, enable **VoiceFlow Paste Helper** in Android Accessibility settings.

### Try the Android/Samsung voice provider

Open **System Voice Input settings** from VoiceFlow. If Android/Samsung lists Ajmat VoiceFlow as a provider, select it. The app registers the standard Android `android.speech.RecognitionService` interface. Whether Samsung Keyboard offers the third-party provider in its voice button is controlled by the keyboard/OEM build and cannot be guaranteed by an app. The floating panel is the independent fallback.

### Accessibility Paste Helper privacy

The helper is optional. It does not subscribe to or store screen text. It only attempts `AccessibilityNodeInfo.ACTION_PASTE` on the currently focused input control after the user taps VoiceFlow's Paste action. If it is not enabled or an app blocks paste, the text remains in the clipboard for manual paste.

## Diagnostics

Use **View logs and diagnostics** in the app. Logs include:
- microphone start/stop, captured byte count, and recording duration;
- model download and checksum status;
- model load and inference duration;
- online fallback outcomes;
- app-level errors and Java exception stack traces.

The log deliberately does **not** include dictated text or audio content. Logs are kept in app-private storage and can be copied from the diagnostics screen.

## Online endpoint contract

Leave the URL field blank for fully offline operation. If configured, VoiceFlow sends:
- `audio`: temporary mono 16 kHz PCM WAV file
- `language`: `auto`, `hi`, or `en`

Expected JSON response:

    {"text":"recognized words here"}

The endpoint must use HTTPS. This repository does not host a speech-inference server. To retain privacy, configure a service that does not store incoming audio.

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

The CMake build fetches whisper.cpp tag `v1.7.1`. GitHub Actions runs unit tests, builds the debug APK, and uploads the artifact named `Ajmat-VoiceFlow-debug`.

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## Important limitations

- On-device recognition speed depends on the phone CPU, RAM, recording length, and model. Small Q5 may take longer on some devices; the floating panel shows elapsed processing time and the in-app logs capture exact timings.
- Third-party provider visibility depends on Android/Samsung Voice Input settings. The system provider and floating panel are independent routes.
- A physical Samsung handset is needed to verify Samsung's provider list, overlay behaviour, accessibility paste, and real microphone performance.
