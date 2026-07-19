# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build the project
./gradlew build

# Run unit tests for the SDK module
./gradlew :hikotest-sdk:test

# Run a single unit test class
./gradlew :hikotest-sdk:test --tests "com.hik.otest.WasmAbiTest"

# Assemble the SDK as an AAR
./gradlew :hikotest-sdk:assembleRelease
```

## Architecture

Single-module Android library project (`rootProject.name = "HikotestAndroidSDK"`):

- **`:hikotest-sdk`** — Distributable Android library (`namespace = "com.hik.otest.sdk"`). All SDK source lives under `hikotest-sdk/src/main/kotlin/com/hik/otest/`.

The repo contains ONLY the SDK itself (user decision, 2026-07-19): no demo app, no example activities. Integration/manual testing is done in separate consumer apps outside this repo. Pure-JVM unit tests (no emulator) stay in `hikotest-sdk/src/test` — they are the WASM ABI regression proof; fixtures come from the web panel repo (`scripts/build-android-fixtures.mjs`).

### Core SDK concept

The SDK runs **WebAssembly (WASM) business logic dynamically on Android** via Chicory (pure-JVM WASM runtime — no JNI). The flow:

1. **`WasmFetcher`** — fetches `release.wasm` from GitHub Releases (`halil9393/hikotest-raunt-project`) using OkHttp. Caches to `filesDir/hikotest_cache/` with a tag file for version tracking. `getWasmBytes()` for initial load; `checkForUpdate()` returns new bytes only when the remote tag differs.
2. **`WasmRunner`** — wraps a Chicory `Instance`. `load(ByteArray)` compiles and instantiates the module. `call(name, LongArray)` invokes a named WASM export. Instance field is `@Volatile` for safe hot-reload. Must import `env.abort` (AssemblyScript modules with strings require it).
3. **`Hikotest`** — public singleton API. `initialize(context)` downloads + loads WASM, then starts a background coroutine that polls for updates every 60 s and hot-reloads automatically. `shutdown()` cancels the loop. `execute(functionName, signature, args)` does typed named-export calls (string ABI: `hiko_alloc` + UTF-8 ptr/len, return = ptr with 4-byte LE length prefix).

### Key dependencies

- `com.dylibso.chicory:runtime:1.7.5` + `com.dylibso.chicory:wasm:1.7.5` — pure-JVM WASM runtime (`Parser.parse(ByteArray)` → `Instance.builder(module).build()`)
- `com.squareup.okhttp3:okhttp:4.12.0` — GitHub API calls and asset downloads
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` — background update loop + suspend initialize

### Build config

- AGP `9.1.1` (built-in Kotlin), `minSdk = 29`, Java 11 source/target compatibility
- The SDK module must not depend on Compose or any UI library
