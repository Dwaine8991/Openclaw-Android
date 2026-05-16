# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Openclaw-Android is an Android fork of the OpenClaw companion app. It acts as a companion node for an OpenClaw AI gateway, with the unique capability of running a local gateway directly on the device (rooted-device support). Users can connect to external gateways or run an embedded local gateway, configuring their own model provider settings (Base URL, API key, model name).

- **Package name (applicationId):** `io.github.openclawcn.app`
- **Kotlin namespace:** `ai.openclaw.app`
- **Min SDK:** 31, **Target SDK:** 36
- **Status:** Experimental

## Build System

Gradle 9.4.1 with Kotlin DSL, version catalog in `gradle/libs.versions.toml`.

### Environment Setup

```bash
# Set Android SDK path
export ANDROID_HOME=/path/to/android/sdk

# Optional: China-friendly npm registry
export OPENCLAW_ANDROID_NPM_REGISTRY=https://registry.npmmirror.com
```

### Common Commands

```bash
# Build debug APKs
./gradlew :app:assemblePlayDebug
./gradlew :app:assembleThirdPartyDebug

# Install to device
./gradlew :app:installPlayDebug

# Run all tests
./gradlew :app:test

# Run unit tests for a specific flavor
./gradlew :app:testPlayDebugUnitTest
./gradlew :app:testThirdPartyDebugUnitTest

# Lint check
./gradlew :app:ktlintCheck
```

### Windows PowerShell

```powershell
$env:ANDROID_HOME = "Q:\Android"
.\gradlew :app:assemblePlayDebug
```

### Product Flavors

Dimension "store": `play` (Play Store) and `thirdParty` (third-party distribution). Build variants: `playDebug`, `playRelease`, `thirdPartyDebug`, `thirdPartyRelease`. The flavors differ in which sensitive features (call log / SMS handlers) are included.

### APK Output

Written to `app/build/outputs/apk/<flavor>/<buildType>/` with prefix `openclaw-android-<version>-<flavor>-<buildType>.apk`.

## Architecture

The app follows an **MVVM + MVI-inspired** architecture with Jetpack Compose UI.

### Core Components

1. **`NodeApp` (Application)** — Singleton that holds `SecurePrefs` and a lazily-created `NodeRuntime`.
2. **`MainViewModel` (AndroidViewModel)** — Central state manager exposing 80+ `StateFlow` properties. All UI state flows through the ViewModel, which wraps `NodeRuntime` and exposes its flows via `flatMapLatest`.
3. **`NodeRuntime`** — Core runtime engine managing gateway connections, chat, canvas, camera, voice, SMS, and notification forwarding. Owns the connection lifecycle and dispatches to various handlers.
4. **`MainActivity` (ComponentActivity)** — Thin activity using `setContent` with `OpenClawTheme` and `RootScreen`. Handles intent routing and starts `NodeForegroundService`.
5. **`NodeForegroundService`** — Foreground service with `dataSync|microphone` types, keeps the node running in the background.

### Key Packages

| Package | Responsibility |
|---------|---------------|
| `ai.openclaw.app` (top-level) | Application class, MainActivity, MainViewModel, NodeRuntime, domain enums |
| `ai.openclaw.app.gateway` | WebSocket session, gateway discovery (mDNS), device auth, TLS, protocol messages |
| `ai.openclaw.app.node` | Device capability handlers (Camera, Location, Contacts, Calendar, Notifications, etc.), command registry/dispatcher, canvas controller, local gateway launcher |
| `ai.openclaw.app.ui` | Compose screens — RootScreen, OnboardingFlow, ConnectTabScreen, ChatSheet, CanvasScreen, SettingsSheet, VoiceTabScreen, overlays, theming |
| `ai.openclaw.app.chat` | ChatController (session management, message streaming), ChatModels |
| `ai.openclaw.app.voice` | Local MNN-based ASR/TTS engines, audio capture, talk mode, wake word detection |
| `ai.openclaw.app.protocol` | Protocol constants and Canvas A2UI action types |
| `ai.openclaw.app.tools` | Tool call display rendering |

### Handler/Registry Pattern

The `node` package uses a registry-dispatcher pattern (`InvokeCommandRegistry` + `InvokeDispatcher`) where various `*Handler` classes register commands that the gateway can invoke remotely.

## Testing

- **Framework:** JUnit 5 (JUnit Platform) with Kotest assertions and Robolectric
- **Test location:** `app/src/test/` mirrors main package structure (30+ test files)
- **Flavor-specific tests:** `app/src/testThirdParty/`
- **Config:** `useJUnitPlatform()` enabled, `isIncludeAndroidResources = true`
- **Dependencies:** Kotest 6.1.11, Robolectric 4.16.1, kotlinx-coroutines-test, OkHttp MockWebServer

## Important Notes

- **Root requirement:** Root is only needed for phone-local gateway mode. Normal devices can still use external gateway connection mode.
- **Local gateway port:** `127.0.0.1:18790` (separate from upstream's `18789` to allow coexistence).
- **Runtime directory:** `/data/user/0/io.github.openclawcn.app/files/openclaw/` (app-private, not accessible via normal `adb shell`).
- **Kotlin namespace vs applicationId:** Source package is `ai.openclaw.app` while runtime applicationId is `io.github.openclawcn.app`. This reduces merge conflicts with upstream.
- **Build prerequisites:** JDK 21, Android SDK platform 36, Node.js and npm on PATH (required for embedded gateway runtime).
- **No committed secrets:** API keys, gateway tokens, and `.openclaw` runtime state must never be committed. Users bring their own model provider credentials.
- **Don't commit:** `build/`, `.gradle/`, `.kotlin/`, `local.properties`, `node_modules/`, APKs.
