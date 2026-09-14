# Райя

Open-source Android foundation for «Райя» — a stylized AI assistant inspired by Raya-Prime from the Lololoshka story seasons.

Current version includes Android speech recognition and Russian text-to-speech, but no LLM, network services, Firebase, analytics, or API keys.

## Stack

- Kotlin 2.0.21
- Jetpack Compose with Material 3
- ViewModel
- StateFlow and Coroutines
- Gradle 8.9 / Android Gradle Plugin 8.7.3

## Build

Requirements: JDK 17 and Android SDK with platform 35.

```bash
./gradlew test
./gradlew assembleDebug
```

Install debug APK from `app/build/outputs/apk/debug/app-debug.apk`.

## Demo

The main button runs:

```text
Idle -> Listening -> Thinking -> Speaking -> Idle
```

The transition sequence belongs to `RayaOrchestrator`, not a Composable. `AndroidSpeechRecognitionProvider` and `AndroidSpeechSynthesisProvider` hide Android APIs behind domain contracts. The ViewModel maps domain state to `RayaUiState`; `RayaScreen` only renders it and sends user actions upward.

The app requests `RECORD_AUDIO` only after the user presses the microphone button. Recognition uses `ru-RU` by default, and TTS keeps `Speaking` active until the utterance completes.

## Structure

```text
app/src/main/java/com/dustincorder/rai/
├── domain/
│   ├── RayaOrchestrator.kt
│   ├── RayaState.kt
│   └── providers.kt
├── presentation/
│   ├── RayaUiState.kt
│   └── RayaViewModel.kt
├── ui/
│   ├── RayaScreen.kt
│   └── theme/
└── MainActivity.kt
```

`ReplyProvider` is currently backed by `MockReplyProvider`; LLM integration remains out of scope. Speech providers are real Android implementations, while unit tests use fakes.

## License

Distributed under GNU GPL v3.0. See `LICENSE`.
