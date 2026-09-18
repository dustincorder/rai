# WORKFLOWS (verified operational cookbook)

## Prerequisites

- JDK 17, Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` set),
  release keystore configured per `app/build.gradle.kts` (`~/.glazegram`
  env files) for signed release builds. Never commit secrets.

## Mandatory checks

- Kotlin changes: `./gradlew test` then `./gradlew assembleDebug`.
- UI/build/release-path changes, additionally:
  `./gradlew compileDebugAndroidTestKotlin`, `./gradlew assembleRelease`,
  then `apksigner verify --verbose --print-certs
  app/build/outputs/apk/release/app-release.apk`.
- Docs-only changes are exempt from builds.

## Useful commands

```sh
./gradlew test                          # JVM unit tests (debug+release)
./gradlew assembleDebug                 # debug APK
./gradlew compileDebugAndroidTestKotlin # androidTest sources compile
./gradlew assembleRelease               # signed release APK (needs keystore)
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Outputs: `app/build/outputs/apk/{debug,release}/`.

## Install / smoke

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug and release (or differently signed) APKs cannot update each other:
a signature mismatch means uninstall first. Raya intentionally disables
Android backup/restore (`android:allowBackup="false"` with full domain
exclusions in backup rules), preventing stale preferences, onboarding state,
or chat history from being restored across reinstalls.

For a deterministic clean local app state during development/testing:

```sh
adb shell pm clear com.dustincorder.rai
```

## Localization

Strings: `app/src/main/res/values/strings.xml`,
`app/src/main/res/values-ru/strings.xml`,
`app/src/main/res/values-uk/strings.xml`. Add all three locales for every user-visible
string; keep keys identical across the three files.

## Adding tests

- Repository/policy: `app/src/test/java/com/dustincorder/rai/data/`
- Coordinator/domain: `app/src/test/java/com/dustincorder/rai/domain/`
  (`ChatSessionCoordinatorTest.kt`, `ChatSessionTest.kt`) — use `runTest` +
  test dispatchers, no wall-clock sleeps; controlled fakes for title
  generators/conversation owners.
- ViewModel/presentation: `app/src/test/java/com/dustincorder/rai/presentation/`,
  UI policy: `app/src/test/java/com/dustincorder/rai/ui/`
  (`RayaUiPolicyTest.kt`, `ConversationTailPolicyTest.kt`).
- Deterministic IO: `FileChatSessionRepository` accepts an injected
  dispatcher — pass the test dispatcher.

## PR workflow

- Branch from current `main`; keep the change scoped to one task.
- NEVER merge, NEVER enable auto-merge — a human merges.
- One PR per task; report: branch, commit, verification commands run,
  physical-device claims only if actually performed.

## Physical smoke (manual)

Drawer open/close, NewDraft→first message, title in history, switching,
deletion, Temporary text/voice/save/abandon/restart-loss, emotion preview,
face motion, Dynamic/Monet, IME open/close, mic denial→App Settings,
speaking interrupt, scroll-up during streaming, all Settings sections,
EN/RU/UK spot-check.
