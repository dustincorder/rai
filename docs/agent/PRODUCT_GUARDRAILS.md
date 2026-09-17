# PRODUCT GUARDRAILS (stable decisions, not history)

## Identity

Raya is an AI entity first, messenger second. Material 3 is the
interaction/accessibility foundation; the visible identity is Raya's:
near-black theme, cyan/teal accent (dynamic primary-derived accent in
Dynamic mode), generous negative space, restrained surfaces, compact type,
geometric rounded face language. No generic messenger look, no purple stock
Material remnants, no decorative HUD/glassmorphism excess.

## States vs emotions (separate dimensions)

- Interaction state (`RayaState`): Idle, Listening, Thinking, Speaking, Error.
- Semantic emotion (`RayaFaceEmotion`): Calm, Listening, Thinking,
  SemanticThinking, Happy, Excited, Playful, Curious, Skeptical, Confused,
  Concerned, Sad, Embarrassed, Surprised, Angry, Annoyed, Tired, Error.
- Note: `RayaFaceEmotion` has both `Thinking` (interaction-derived) and
  `SemanticThinking` (content-derived). `RayaStateMapper` owns the mapping.
  Never build an emotion × state cross-product type.

## Responses

Replies are structured (`RayaResponse`: text + emotion + language tag);
streaming deltas render as plain text, same final layout as assistant
responses. No raw JSON in UI.

## Chat lifecycle

- `NewDraft`: unpersisted; first meaningful User turn creates the session
  (typed and voice-originated turns behave identically).
- `Persistent`: app-private JSON, shown in history newest-activity-first.
- `Temporary`: memory-only, never in history/storage until `Save chat`;
  destroyed on switch/restart; `Save chat` creates exactly one persistent
  session with the full transcript, then normal title policy runs.
- Return-to-normal target after Temporary is runtime-only.

## Titles

Eligible after the first meaningful User + Assistant pair. One AI attempt
only (`titleGenerationAttempted`). Sources: `Default`, `Generated`,
`Derived`, `Manual`. Stale Generated → `Derived` from the current
transcript; null/blank/failed → `Derived`; `Manual` always wins. Title
writes never change `updatedAt`.

## Multilingual

EN + RU + UK for every user-visible string. No raw Kotlin UI strings unless
genuinely non-localizable/debug-only.

## TTS decision

System TTS is production/default. Local-neural TTS infra is dormant and
stays dormant without an explicit new product decision. Do not remove it
casually either.

## Privacy

No Firebase/analytics. API keys in secure storage only, never logged.
Chats are local app-private JSON. Voice uses the configured Android/Whisper
providers. Diagnostics are local debug logs.

## Settings IA

Raya, AI, Voice, Appearance, Integrations (empty-state if none), Privacy &
diagnostics, About. Provider/model pickers live here (and onboarding), not
on the main screen. Appearance preview applies immediately, reverts on exit
without save.

## Onboarding

Six steps, unchanged: Welcome, Appearance, AI setup, Voice, Personality,
Done. Personality is default-only until presets exist — never invent fake
choices. Shares theme tokens/face treatment with the main app.

## Model discovery

`chatModels()` capability filtering, cached fallback on failure, Custom
Model escape hatch, no network request per API-key keystroke, insecure HTTP
only via the explicit custom opt-in.

## Physical review expectations

Do not claim physical-device testing unless actually performed. When
verifying on device, cover: drawer, NewDraft→first message, title in
history, chat switching/deletion, Temporary text/voice/save/abandon/restart,
emotion preview, animations, Dynamic/Monet, IME, mic denial→Settings,
interrupt, scroll-up during streaming, all Settings sections, EN/RU/UK.
