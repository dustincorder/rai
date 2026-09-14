<div align="center">

# Райя

### An open-source voice assistant for Android

A small, expressive AI assistant built with Kotlin, Jetpack Compose and Material 3.

Inspired by **Raya-Prime**, reimagined as a modern Android assistant rather than a replica of the original character.

</div>

---

> [!IMPORTANT]
> Райя is currently under active development.
> APIs, UI, architecture and features may change between versions.

## ✨ What is Райя?

Райя is an experimental open-source voice assistant for Android.

The goal is to build an assistant that feels like a character instead of another chat box:

- a simple expressive face;
- natural voice interaction;
- multilingual conversations;
- configurable LLM providers;
- local-first features where practical;
- explicit and privacy-conscious device integrations.

The interface follows **Material 3** and the Android system light/dark theme.

No fake sci-fi dashboard. No glowing terminal pretending to launch a spaceship.

Just an Android assistant with a face.

## 🚧 Current status

The project already includes:

- ✅ Jetpack Compose + Material 3 UI
- ✅ system light/dark theme support
- ✅ animated Raya face
- ✅ multiple visual emotions
- ✅ Android speech recognition
- ✅ partial and final speech transcription
- ✅ Android text-to-speech
- ✅ microphone runtime permission flow
- ✅ voice interaction state machine
- ✅ Russian speech support
- ✅ unit-tested orchestration layer

Currently in development:

- 🚧 configurable LLM providers
- 🚧 OpenAI-compatible API support
- 🚧 Anthropic-compatible API support
- 🚧 multilingual conversation mode
- 🚧 addressing Raya by name

Planned:

- ⏳ local neural TTS voice
- ⏳ downloadable offline voice model
- ⏳ conversation memory
- ⏳ device context and integrations
- ⏳ optional wake-word support
- ⏳ richer Raya personality and emotional behavior

## 🧠 Interaction flow

```text
Microphone
    ↓
Speech recognition
    ↓
Recognized text
    ↓
RayaOrchestrator
    ↓
LLM / reply provider
    ↓
Text-to-speech
    ↓
Raya speaks
```

The high-level interaction state is intentionally small:

```text
Idle → Listening → Thinking → Speaking → Idle
                         ↘ Error
```

Visual emotions are kept separate from the domain state.

This means Raya can eventually look curious, happy, concerned or surprised without turning every facial expression into application business logic.

## 🎨 Raya face

Raya's face is drawn directly with Jetpack Compose.

There are no copied character sprites or bundled artwork.

The visual language is intentionally minimal:

- cyan geometric eyes;
- no permanent mouth;
- simple emotion-driven shapes;
- lightweight animations;
- transparent background;
- no simulated monitor or robot body.

Current visual emotions include:

```text
Calm
Listening
Thinking
Speaking
Happy
Curious
Concerned
Surprised
Angry
Error
```

## 🏗 Architecture

The project intentionally avoids unnecessary framework-heavy architecture while it is still small.

```text
UI
│
├── Compose screens
├── Raya face renderer
└── presentation state
        │
        ▼
RayaViewModel
        │
        ▼
RayaOrchestrator
        │
        ├── SpeechRecognitionProvider
        ├── SpeechSynthesisProvider
        └── ReplyProvider
```

Android-specific implementations stay behind provider interfaces.

`RayaOrchestrator` does not directly depend on Android speech APIs, HTTP clients or Compose.

This keeps the voice pipeline testable and makes providers replaceable.

## 🛠 Tech stack

- Kotlin
- Jetpack Compose
- Material 3
- Android ViewModel
- Kotlin Coroutines
- StateFlow

Additional networking and persistence components are introduced only when required.

## 🤖 LLM providers

Raya is being designed around protocol compatibility rather than hardcoded vendor SDKs.

Planned built-in presets include:

- OpenAI
- Groq
- Anthropic
- Custom provider

The network layer will support:

```text
OpenAI-compatible APIs
Anthropic-compatible APIs
```

Custom providers will be able to use their own base URL and model ID.

API credentials are user-provided and must never be committed to the repository.

## 🎙 Voice

The current version uses Android's native:

- `SpeechRecognizer`
- `TextToSpeech`

The long-term plan is to keep Android TTS as a fallback while providing an optional downloadable local neural voice for Raya.

Voice processing should remain local whenever reasonably possible.

## 🔐 Privacy

Raya is designed with explicit user control in mind.

Principles:

- microphone access only after user interaction;
- no always-on microphone by default;
- no analytics by default;
- no Firebase dependency by default;
- no bundled API keys;
- no credentials committed to source control;
- external AI requests only through providers configured by the user.

Future device integrations should remain optional and use the minimum permissions required.

## 📦 Building

Requirements:

- Android Studio
- Android SDK
- JDK compatible with the project's Gradle configuration

Clone the repository:

```bash
git clone https://github.com/dustincorder/rai.git
cd rai
```

Run tests:

```bash
./gradlew test
```

Build the debug APK:

```bash
./gradlew assembleDebug
```

The APK will be generated under:

```text
app/build/outputs/apk/debug/
```

## 🗺 Roadmap

### Foundation
- [x] Compose application
- [x] interaction state machine
- [x] animated Raya face
- [x] Material 3 UI
- [x] system light/dark theme

### Voice
- [x] Android speech recognition
- [x] Android TTS
- [x] partial transcription
- [x] cancellation and error handling
- [ ] multilingual speech
- [ ] local neural Raya voice

### Intelligence
- [ ] OpenAI-compatible LLM provider
- [ ] Anthropic-compatible LLM provider
- [ ] provider settings
- [ ] conversation context
- [ ] long-term memory
- [ ] personality / lore behavior

### Interaction
- [ ] Raya name addressing
- [ ] optional wake word
- [ ] device context
- [ ] explicit device actions

## 🤝 Contributing

The project is still evolving quickly, so small focused pull requests are preferred.

Before submitting a change:

```bash
./gradlew test
./gradlew assembleDebug
```

Please keep:

- UI logic out of domain code;
- Android APIs behind appropriate boundaries;
- credentials and local files out of Git;
- changes focused rather than introducing architecture for hypothetical future requirements.

Bug reports and focused feature proposals are welcome.

## ⚠️ Disclaimer

Райя is an independent fan-inspired open-source project.

It is inspired by Raya-Prime from the Lololoshka story universe, but is not an official application and is not affiliated with, endorsed by, or sponsored by the original creators or rights holders.

The project does not include original game/show artwork, character assets or other copyrighted media.

## 📄 License

Licensed under the **GNU General Public License v3.0**.

See [`LICENSE`](LICENSE) for details.

---

<div align="center">

**Райя is still learning.**

Built for Android, one unnecessarily complicated human conversation at a time.

</div>
