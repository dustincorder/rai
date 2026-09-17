# ARCHITECTURE (on-demand depth)

Read only when `AGENTS.md`'s file map is insufficient. Source is authoritative.

Path convention: same as `AGENTS.md` — Kotlin paths not starting with
`app/`, `docs/`, or `.github/` are relative to
`app/src/main/java/com/dustincorder/rai/`; a bare `X.kt` sits directly
under that base.

## Startup

- Purpose: DI-light composition root, onboarding routing, theme, navigation.
- Files: `RayaApplication.kt` (lazy providers: settings, keys, chat repo,
  reply provider, discovery, runtime speech providers), `MainActivity.kt`
  (edge-to-edge, permission state, ViewModel wiring), `ui/RayaApp.kt`
  (NavHost: Bootstrap → Onboarding → Main; drawer + Settings routes).
- State: `OnboardingStore.completed`, persisted settings / configured keys
  decide the initial destination (`data/settings/OnboardingPolicies.kt`).
- Invariants: no network on startup beyond lazy init; onboarding completion
  is explicit; do not bypass the bootstrap route.

## ViewModels / UI state

- `presentation/RayaViewModel.kt`: owns `RayaOrchestrator` + delegates chat
  persistence to `ChatSessionCoordinator`; exposes `uiState`,
  `chatSessions`, `activeConversation`. Text/voice/clear actions are thin
  pass-throughs to the orchestrator/coordinator.
- `presentation/SettingsViewModel.kt`: settings draft save, API-key
  status/write/delete, connection test, model-list refresh.
- `presentation/RayaUiState.kt` + `presentation/RayaStateMapper.kt` (+
  `presentation/model/RayaFaceState.kt`): derive face emotion, status
  strings, busy/speaking flags from orchestrator flows. Semantic emotion
  (`RayaEmotion`) and interaction state (`RayaState`) stay separate;
  `RayaFaceEmotion` is the render enum — `RayaEmotion.Thinking` maps to
  `SemanticThinking`, while `RayaState.Thinking/Listening/Error` map to the
  same-named face values.
- Invariants: UI never mutates conversation directly; ViewModels expose
  StateFlow + actions only.

## RayaOrchestrator (interaction owner)

- File: `domain/RayaOrchestrator.kt`. Owns: conversation list, text turns
  (`textTurnInFlight`), voice session/turns (`turnEpoch`, session job),
  streaming collection, emotion, inactivity teardown.
- Text turn: `submitText()` appends User message → `collectReply()` streams
  `ReplyEvent.TextDelta/Completed` → appends Assistant message or Error state.
- Voice turn: `startVoiceSession()` → `beginVoiceTurn()` → recognition
  events (`Final`/`Partial`/`Error`) → `onVoiceFinal()` appends User message
  → `collectVoiceReply()` with epoch checks → TTS speak → next turn.
- Stale protection: `StaleTurn` aborts superseded collectors; `isStaleEpoch`
  rejects late voice deltas; `canReplaceConversation()` is false during
  voice/text activity — coordinators must respect it.
- Do not break: epoch guards, barge-in handoff, inactivity monitor,
  `replaceConversation` refusal during activity.

## Conversation / streaming flow

- Types: `domain/ConversationMessage.kt` (`ConversationRole`:
  User/Assistant/Notice), `domain/RayaResponse.kt`, `domain/providers.kt`
  (`ReplyProvider`, `SpeechRecognitionProvider`, `SpeechSynthesisProvider`).
- Streaming protocol: `data/llm/ReplyStreaming.kt` (`ReplyEvent`);
  validation/parsing in `data/llm/RayaResponseParser.kt`; transport errors
  mapped by `data/llm/LlmErrorClassifier.kt`.
- UI presentation: `ui/RayaScreen.kt` transcript + dock; tail-follow policy
  in `ui/ConversationTailPolicy.kt` (`onManualScroll` disables follow-tail;
  `onUserTurnRevision` re-enables; chat switch resets).

## Chat persistence

- Coordinator: `domain/ChatSessionCoordinator.kt` — the only decider of
  whether visible conversation is `NewDraft` / `Persistent` / `Temporary`.
  Serializes mutations with an internal mutex; observes orchestrator
  conversation; creates session on first meaningful User turn; marks title
  attempt before launching generation.
- Storage: `data/chat/FileChatSessionRepository.kt` — app-private JSON
  (`chat-index.json` + per-session transcripts), injected IO dispatcher,
  mutation mutex, atomic temp→replace writes, corrupt-transcript tolerance,
  legacy empty-`Default` pruning on index load.
- Do not break: IO dispatcher (never main-thread file I/O), atomic writes,
  `updatedAt` = activity only, manual-title boundary.

## Title flow

- Eligibility: `shouldGenerateChatTitle()` — first meaningful User +
  Assistant pair, source `Default`, not yet attempted.
- Attempt: coordinator sets `titleGenerationAttempted`, then calls
  `ChatTitleGenerator.generate()` (implemented by
  `ConfigurableReplyProvider` — plain prompt, no emotion parsing, ≤60 chars).
- Result handling: current transcript match → `Generated`; stale/failed/
  null/blank → `Derived` via `deriveChatTitle()`; `Manual` always wins
  (repository boundary enforces it); failures never retry (attempt flag stays).
- Never let title writes change `updatedAt`.

## Temporary chat

- `ActiveConversation.Temporary`: memory-only; observer skips persistence;
  excluded from `sessions`/history; destroyed on switch/restart.
- `toggleTemporaryChat()`: header eye toggle; in-memory return target only
  (Persistent/NewDraft), falls back to `NewDraft` if target vanished;
  explicit open/new/save/delete clears it. Guarded by
  `canReplaceConversation()`.
- `saveTemporaryChat()`: creates exactly one session, saves full transcript,
  switches to `Persistent`, then runs the normal single title attempt.

## Settings / keys / models

- Persistence: `data/settings/SettingsRepository.kt` (+ DataStore impl),
  `data/settings/AppSettings.kt` (`provider`, protocol/base URL, `resolvedModelId()`,
  STT/TTS engines, appearance, `customAllowInsecureHttp`).
- Keys: `data/secrets/ApiKeyStore.kt` (Android impl) — secure storage only,
  never plaintext/logged.
- Discovery: `data/llm/ModelDiscovery.kt` + `data/llm/ModelCatalog.kt`/`data/llm/LlmModels.kt`;
  `chatModels()` filters to chat-capable models; cached list shown on failure;
  Custom Model escape hatch preserved.
- UI: `ui/SettingsScreen.kt` IA (Raya, AI, Voice, Appearance, Integrations,
  Privacy & diagnostics, About); appearance preview is a temporary override,
  reverted on exit without save.

## Voice pipeline (STT/VAD/TTS)

- Recognition: `speech/AndroidSpeechRecognitionProvider.kt` (system),
  `speech/WhisperSpeechRecognitionProvider.kt` + 
  `data/stt/GroqWhisperTranscriptionProvider.kt` (Whisper path),
  `RayaApplication.runtimeSpeechRecognitionProvider()` selects by settings.
- VAD: `speech/SherpaSileroSpeechFrameClassifier.kt` (+ `domain/VoiceV2.kt`,
  `app/src/main/assets/silero_vad.onnx`); recognition lifecycle in
  `domain/RecognitionAttemptLifecycle.kt`.
- Barge-in: `speech/AndroidBargeInMonitor.kt`, orchestrator handoff block.
  Keeps a 500 ms PCM pre-roll (`maxPreRollSamples = sampleRate / 2` at
  16 kHz) so confirmed speech handoff includes audio preceding the VAD
  confirmation.
- TTS: `speech/AndroidSpeechSynthesisProvider.kt` (production) via
  `speech/RuntimeSpeechSynthesisProvider.kt`; `LocalNeural*` + 
  `SherpaOnnxLocalNeuralTtsEngine` + `TtsModelCatalog` are dormant — do not
  productize without a new product decision.
- Permission: `presentation/MicrophonePermissionPolicy.kt` (pure policy) +
  `MainActivity.kt` wiring (rationale → retry message; permanent denial →
  App Settings; resume re-check).

## Theme / navigation / localization

- Theme: `ui/theme/Theme.kt` (Raya brand vs Dynamic/Monet via
  `effectiveAppearance`), `ui/theme/Color.kt`,
  `ui/designsystem/RayaDesignSystem.kt` (spacing/shapes/surfaces).
- Face: `ui/raya/face/RayaFace.kt` (`RayaFaceRenderMode.Normal/Temporary`;
  Temporary = outlined, same geometry/motion/accent).
- Navigation: drawer (`ui/RayaDrawer.kt`) — New Chat, history, bottom
  Settings; header toggle for Temporary; Settings route ends active voice first.
- Localization: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-ru/` +
  `app/src/main/res/values-uk/` + `app/src/main/res/xml/locales_config.xml`. All user-visible strings in 3 locales.

## Where to change X

| Need | Start at |
|---|---|
| Main UI / drawer / dock | `ui/RayaScreen.kt`, `ui/RayaDrawer.kt`, `ui/RayaApp.kt` |
| Face expression/motion | `ui/raya/face/RayaFace.kt`, `presentation/RayaStateMapper.kt` |
| Chat persistence/lifecycle | `domain/ChatSessionCoordinator.kt`, `data/chat/FileChatSessionRepository.kt` |
| Titles | `domain/ChatSession.kt`, `data/llm/ConfigurableReplyProvider.kt` |
| Provider/model selector | `ui/SettingsScreen.kt`, `data/settings/AppSettings.kt`, `data/llm/LlmModels.kt` |
| Mic permission | `presentation/MicrophonePermissionPolicy.kt`, `MainActivity.kt` |
| Recognition/voice | `domain/RayaOrchestrator.kt`, `speech/*`, `domain/VoiceV2.kt` |
| TTS | `speech/AndroidSpeechSynthesisProvider.kt`, `speech/RuntimeSpeechSynthesisProvider.kt` |
| Localization | `app/src/main/res/values*/strings.xml` |
| Theme | `ui/theme/Theme.kt`, `ui/designsystem/RayaDesignSystem.kt` |
| Tail-follow | `ui/ConversationTailPolicy.kt` |
