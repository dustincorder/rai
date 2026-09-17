# AGENTS.md — canonical agent instructions

Raya: Android AI voice/text assistant. Kotlin, Jetpack Compose, Material 3
(interaction/accessibility foundation only — Raya visual identity on top).
ViewModel + StateFlow + coroutines. Single app module. EN/RU/UK localized.

This file is the single source of truth. `docs/agent/*` is on-demand depth.
If docs conflict with source, source wins — report/fix the stale doc.

## Non-negotiable workflow

- NEVER merge a PR. NEVER enable auto-merge. A human merges.
- Do not rewrite unrelated systems while fixing one task.
- Inspect existing architecture before adding abstractions; prefer targeted
  reads over broad exploration.
- Do not claim physical-device testing unless actually performed.

## Exploration discipline (save tokens)

1. Read this file.
2. Find the subsystem in the file map below.
3. Open the listed entry files (2–6 files is usually enough).
4. Read `docs/agent/ARCHITECTURE.md` only if the map is insufficient.
5. Then use targeted `rg` for exact symbols. Broaden search only with
   evidence the map is stale.

Avoid as a first action: full-tree `find`, dumping source trees, reading
`build/` outputs, reading every Kotlin file, re-discovering build commands.

## Quick file map

| Concern | Start here |
|---|---|
| App bootstrap / navigation | `app/src/main/java/com/dustincorder/rai/RayaApplication.kt`, `MainActivity.kt`, `ui/RayaApp.kt` |
| Main Raya UI | `ui/RayaScreen.kt`, `ui/RayaUiPolicy.kt` |
| Drawer / chat list | `ui/RayaDrawer.kt` |
| Face renderer | `ui/raya/face/RayaFace.kt` |
| Face state mapping | `presentation/model/RayaFaceState.kt`, `presentation/RayaStateMapper.kt` |
| Settings UI | `ui/SettingsScreen.kt` |
| Settings persistence | `data/settings/SettingsRepository.kt`, `data/settings/AppSettings.kt`, `data/settings/OnboardingStore.kt`, `data/settings/OnboardingPolicies.kt` |
| ViewModels / UI state | `presentation/RayaViewModel.kt`, `presentation/SettingsViewModel.kt`, `presentation/RayaUiState.kt` |
| Interaction owner | `domain/RayaOrchestrator.kt` |
| Interaction state | `domain/RayaState.kt` |
| Chat sessions | `domain/ChatSession.kt`, `domain/ChatSessionCoordinator.kt` |
| Chat storage | `data/chat/FileChatSessionRepository.kt` |
| Titles | `domain/ChatSession.kt` (`shouldGenerateChatTitle`, `deriveChatTitle`, `sanitizeChatTitle`), `data/llm/ConfigurableReplyProvider.kt` (`generate`) |
| LLM provider clients | `data/llm/LlmProtocolClients.kt`, `data/llm/GeminiReplyProvider.kt`, `data/llm/LlmConnection.kt`, `data/llm/RayaResponseParser.kt`, `data/llm/ReplyStreaming.kt`, `data/llm/LlmErrorClassifier.kt` |
| Model discovery | `data/llm/ModelDiscovery.kt`, `data/llm/ModelCatalog.kt`, `data/llm/LlmModels.kt` |
| STT / Whisper | `data/stt/GroqWhisperTranscriptionProvider.kt`, `speech/WhisperSpeechRecognitionProvider.kt`, `speech/AndroidSpeechRecognitionProvider.kt` |
| VAD | `speech/SherpaSileroSpeechFrameClassifier.kt`, `domain/VoiceV2.kt`, `app/src/main/assets/silero_vad.onnx` |
| TTS | `speech/AndroidSpeechSynthesisProvider.kt`, `speech/RuntimeSpeechSynthesisProvider.kt`, `domain/TtsV2.kt` |
| Mic permission | `presentation/MicrophonePermissionPolicy.kt`, `MainActivity.kt` |
| Tail-follow policy | `ui/ConversationTailPolicy.kt` |
| Theme / design tokens | `ui/theme/Theme.kt`, `ui/theme/Color.kt`, `ui/designsystem/RayaDesignSystem.kt` |
| Localization | `app/src/main/res/values/strings.xml`, `values-ru/`, `values-uk/`, `xml/locales_config.xml` |
| Tests | `app/src/test/java/com/dustincorder/rai/{data,domain,presentation,speech,ui}/` |

## Ownership

- `RayaOrchestrator` owns high-level interaction/conversation behavior.
  UI never owns business state; ViewModels expose state + actions.
- Keep `domain/` Android-free where practical. Explicit optional
  integrations; no globals/singletons/premature modules.
- Semantic emotion (`RayaFaceEmotion`: Calm, Listening, Thinking,
  SemanticThinking, Happy, Excited, Playful, Curious, Skeptical, Confused,
  Concerned, Sad, Embarrassed, Surprised, Angry, Annoyed, Tired, Error) and
  runtime state (`RayaState`: Idle, Listening, Thinking, Speaking, Error)
  are separate dimensions. Never build an emotion × state cross-product type.

## Chat semantics (accepted)

- `ActiveConversation`: `NewDraft`, `Persistent(sessionId)`, `Temporary`.
- `NewDraft` is not persisted; first meaningful User turn creates the session.
- `Persistent` lives in app-private JSON repo, appears in history.
- `Temporary` is memory-only, excluded from history/storage, destroyed on
  switch/restart; `Save chat` converts it to exactly one persistent session.
- Return-to-normal target after Temporary is runtime-only, never persisted.

## Title policy (accepted)

- Eligible after first meaningful User + Assistant pair (no length threshold).
- Exactly one AI attempt (`titleGenerationAttempted` set before launch).
- `ChatTitleSource`: `Default`, `Generated`, `Derived`, `Manual`.
- Stale `Generated` result → `Derived` fallback from current transcript.
- Null/blank/failed AI result → `Derived` fallback. `Manual` always wins.
- Title writes must not change conversation `updatedAt`.

## Voice invariants

- VAD-gated STT (Sherpa Silero + configurable Whisper/system engine),
  streaming replies, barge-in with epoch guards, inactivity teardown.
- System TTS is production/default. Do NOT resurrect local-neural TTS as a
  product feature; dormant infra (`LocalNeuralSpeechSynthesisProvider`,
  `SherpaOnnxLocalNeuralTtsEngine`) stays unless a new product decision says so.
- Mic denial: rationale → retryable message; permanent denial → App Settings
  guidance. Active voice ends before Settings navigation.

## UI / provider rules

- Task 007 redesign: M3 underneath, Raya identity on top. No generic
  messenger look. Drawer: New Chat, history, bottom Settings. Header shows
  chat title + resolved model. Face → transcript fade, unified text/voice dock.
- Settings IA: Raya, AI, Voice, Appearance, Integrations, Privacy &
  diagnostics, About. Provider/model via exposed dropdowns;
  `chatModels()` capability filtering + cached fallback preserved.
- API keys: secure store only, never plaintext, never logged. No model-network
  request per keystroke.
- All user-visible strings in EN + RU + UK.

## Validation

Mandatory for Kotlin changes: `./gradlew test`, `./gradlew assembleDebug`.
Also when touching UI/build/release paths: `compileDebugAndroidTestKotlin`,
`assembleRelease`, then `apksigner verify --verbose --print-certs
app/build/outputs/apk/release/app-release.apk`. Docs-only changes are exempt.
APK output: `app/build/outputs/apk/{debug,release}/`. Never expose signing secrets.

## Change discipline

- Deterministic tests; no wall-clock sleeps in race tests.
- Preserve accepted lifecycle/race guards (title one-attempt, stale-snapshot
  checks, `canReplaceConversation()`, tail-follow manual override).
- No Firebase/analytics, no new dependencies to avoid small code, no giant
  speculative refactors.
