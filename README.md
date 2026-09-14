# Райя

Open-source Android foundation for «Райя» — a stylized AI assistant inspired by Raya-Prime from the Lololoshka story seasons.

Current version is a local UI demo. It intentionally does not connect real LLM, speech recognition, speech synthesis, network services, Firebase, analytics, or API keys.

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

The transition sequence belongs to `RayaOrchestrator`, not a Composable. The ViewModel maps domain state to `RayaUiState`; `RayaScreen` only renders it and sends user actions upward.

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

`LlmProvider`, `SpeechRecognitionProvider`, and `SpeechSynthesisProvider` define future integration boundaries only. No implementations or permissions are included yet.

## License

Distributed under GNU GPL v3.0. See `LICENSE`.
