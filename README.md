# STT Translator Android App

An Android app built with **Kotlin + Jetpack Compose** that combines **Google Sign-In**, **speech-to-text**, and **multilingual translation** in a single workflow.

Users sign in with a Google account, grant microphone permission, speak into the app, review the recognized text, choose a target language, and translate the result using **Google ML Kit**.

## Features

- Google account login before using translation features
- Microphone permission handling with rationale dialog and settings fallback
- Real-time speech recognition with partial-result preview
- Editable source text field after voice input
- Selectable target languages from the app UI
- Automatic source language identification
- On-device / model-download translation using ML Kit
- Reset flow and sign-out support

## Supported target languages

- Chinese
- English
- Japanese
- Korean
- French
- Spanish
- German

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose, Material 3
- **Authentication:** Google Sign-In
- **Speech:** Android `SpeechRecognizer`
- **Translation:** Google ML Kit Language ID + Translate API
- **Build tools:** Gradle Kotlin DSL
- **Minimum SDK:** 24

## Project structure

```text
STTTranslator/
├── app/
│   ├── src/main/java/com/example/stttranslator/
│   │   ├── MainActivity.kt
│   │   └── ui/theme/
│   ├── src/main/res/
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## How it works

1. User signs in with Google.
2. App checks microphone permission.
3. User taps **Start** to capture speech.
4. Speech is converted into text with Android speech recognition.
5. User selects a target language.
6. ML Kit identifies the source language and translates the text.

## Local setup

### 1) Open the project

Open the project in **Android Studio**.

### 2) Configure Google Sign-In

Because the app uses Google Sign-In, you need to configure an Android OAuth client in Google Cloud Console:

- Package name: `com.example.stttranslator`
- Add the SHA-1 certificate fingerprint for your debug or release keystore
- Enable the required Google Sign-In configuration for Android

Without correct SHA-1 + package configuration, Google sign-in may fail with errors such as **code 10**.

### 3) Run the app

- Connect an Android device or start an emulator
- Sync Gradle
- Build and run the app

## Notes / limitations

- Speech recognition behavior can vary by device, emulator, network, and installed Google services
- Some translation models may need to be downloaded on first use
- Emulator microphone capture may be less reliable than a physical device

## What I learned

This project helped strengthen my hands-on experience with:

- Android app architecture in Jetpack Compose
- Runtime permission handling
- Mobile speech-to-text workflows
- ML Kit language identification and translation
- Google authentication integration
- UI state management and error handling

## Future improvements

- Add translation history
- Add copy/share/export actions
- Support text-to-speech playback
- Save preferred target language
- Add automated UI tests

## Resume-ready project summary

Built an Android speech-to-text and translation app using Kotlin, Jetpack Compose, Google Sign-In, Android SpeechRecognizer, and ML Kit. Implemented runtime microphone permissions, partial-result voice capture, automatic source language detection, selectable target languages, and model-based multilingual translation.
