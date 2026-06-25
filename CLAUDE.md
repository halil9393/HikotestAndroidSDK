# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build the project
./gradlew build

# Run unit tests for the app module
./gradlew :app:test

# Run unit tests for the SDK module
./gradlew :hikotest-sdk:test

# Run a single unit test class
./gradlew :app:test --tests "com.hik.otest.ExampleUnitTest"

# Run instrumented (on-device) tests
./gradlew :app:connectedAndroidTest

# Assemble a debug APK
./gradlew :app:assembleDebug

# Assemble the SDK as an AAR
./gradlew :hikotest-sdk:assembleRelease
```

## Architecture

This is a two-module Android project (`rootProject.name = "HikotestAndroidSDK"`):

- **`:app`** — Jetpack Compose demo app (`applicationId = "com.hik.otest"`). Hosts and tests the SDK. `MainActivity.kt` renders the test UI (two number inputs + button → calls `Hikotest.getSumOf`).

- **`:hikotest-sdk`** — Distributable Android library (`namespace = "com.hik.otest.sdk"`). All SDK source lives under `hikotest-sdk/src/main/kotlin/com/hik/otest/`.

### Core SDK concept

The SDK runs **WebAssembly (WASM) business logic dynamically on Android** via Chicory (pure-JVM WASM runtime — no JNI). The flow:

1. **`WasmFetcher`** — fetches `release.wasm` from GitHub Releases (`halil9393/hikotest-raunt-project`) using OkHttp. Caches to `filesDir/hikotest_cache/` with a tag file for version tracking. `getWasmBytes()` for initial load; `checkForUpdate()` returns new bytes only when the remote tag differs.
2. **`WasmRunner`** — wraps a Chicory `Instance`. `load(ByteArray)` compiles and instantiates the module. `call(name, LongArray)` invokes a named WASM export. Instance field is `@Volatile` for safe hot-reload.
3. **`Hikotest`** — public singleton API. `initialize(context)` downloads + loads WASM, then starts a background coroutine that polls for updates every 60 s and hot-reloads automatically. `shutdown()` cancels the loop.

### Key dependencies

- `com.dylibso.chicory:runtime:1.7.5` + `com.dylibso.chicory:wasm:1.7.5` — pure-JVM WASM runtime (`Parser.parse(ByteArray)` → `Instance.builder(module).build()`)
- `com.squareup.okhttp3:okhttp:4.12.0` — GitHub API calls and asset downloads
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` — background update loop + suspend initialize

### Build config

- AGP `9.1.1`, Kotlin `2.2.10`, Compose BOM `2026.02.01`
- `minSdk = 29`, `compileSdk = 37`, Java 11 source/target compatibility
- The SDK module must not depend on Compose — only `:app` does
