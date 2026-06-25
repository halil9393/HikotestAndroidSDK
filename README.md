# Hikotest Android SDK

An Android SDK that fetches and runs **WebAssembly (WASM) business logic dynamically** — no app update required. The runtime is powered by [Chicory](https://github.com/dylibso/chicory) (pure-JVM, no JNI). The SDK pulls `release.wasm` from a GitHub Release, caches it locally, and hot-reloads automatically in the background.

---

## Requirements

- minSdk 29+
- JitPack repository access

---

## Installation

### 1. Add JitPack to your repositories

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency

`app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.halil9393:hikotest-sdk:1.0.0")
}
```

---

## Setup

### 3. Store credentials in `local.properties`

`local.properties` is gitignored by default — safe to put secrets here.

```properties
hikotest.github.token=ghp_YOUR_GITHUB_PAT
hikotest.repo.owner=your-github-username
hikotest.repo.name=your-wasm-repo
hikotest.environment=production
hikotest.is.beta=false
```

> Generate a GitHub PAT at **Settings → Developer settings → Personal access tokens**.  
> Minimum required scope: `repo` (for private repos) or `public_repo` (for public repos).

### 4. Inject credentials into `BuildConfig`

`app/build.gradle.kts`:
```kotlin
import java.util.Properties

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

android {
    // ...
    defaultConfig {
        // ...
        buildConfigField("String",  "HIKOTEST_GITHUB_TOKEN", "\"${localProps.getProperty("hikotest.github.token", "")}\"")
        buildConfigField("String",  "HIKOTEST_REPO_OWNER",   "\"${localProps.getProperty("hikotest.repo.owner", "")}\"")
        buildConfigField("String",  "HIKOTEST_REPO_NAME",    "\"${localProps.getProperty("hikotest.repo.name", "")}\"")
        buildConfigField("String",  "HIKOTEST_ENVIRONMENT",  "\"${localProps.getProperty("hikotest.environment", "production")}\"")
        buildConfigField("boolean", "HIKOTEST_IS_BETA",      "${localProps.getProperty("hikotest.is.beta", "false")}")
    }
    buildFeatures {
        buildConfig = true
    }
}
```

> **Important:** `import java.util.Properties` must be the very first line, before the `plugins {}` block.

### 5. Create `AppDelegate`

Create an `Application` subclass. This is where the SDK is configured and initialized — once, at app startup.

```kotlin
class AppDelegate : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Hikotest.configure(
            HikotestConfig.Builder()
                .githubToken(BuildConfig.HIKOTEST_GITHUB_TOKEN)
                .repoOwner(BuildConfig.HIKOTEST_REPO_OWNER)
                .repoName(BuildConfig.HIKOTEST_REPO_NAME)
                .environment(BuildConfig.HIKOTEST_ENVIRONMENT)
                .isBeta(BuildConfig.HIKOTEST_IS_BETA)
                .build()
        )

        appScope.launch {
            runCatching { Hikotest.initialize(this@AppDelegate) }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Hikotest.shutdown()
        appScope.cancel()
    }
}
```

### 6. Register `AppDelegate` in `AndroidManifest.xml`

```xml
<application
    android:name=".AppDelegate"
    ... >
```

---

## Usage

### Observe initialization state

The SDK exposes a `StateFlow` you can collect anywhere (Composable, ViewModel, Activity):

```kotlin
// In a Composable
val initState by Hikotest.initState.collectAsState()

when (initState) {
    is HikotestInitState.Idle,
    is HikotestInitState.Loading -> { /* show loading indicator */ }
    is HikotestInitState.Ready   -> { /* SDK ready, enable UI */ }
    is HikotestInitState.Error   -> { /* show error message */ }
}
```

### Call SDK functions

```kotlin
if (Hikotest.initState.value == HikotestInitState.Ready) {
    val result = Hikotest.getSumOf(3, 5) // → 8
}
```

---

## Configuration reference

| Field | Type | Default | Description |
|---|---|---|---|
| `githubToken` | String | — | GitHub PAT. **Required.** |
| `repoOwner` | String | — | GitHub username or org. **Required.** |
| `repoName` | String | — | Repository name. **Required.** |
| `environment` | String | `"production"` | Arbitrary label forwarded to the WASM context. |
| `isBeta` | Boolean | `false` | `true` fetches the latest release including pre-releases. |
| `updateIntervalMs` | Long | `60000` | Background polling interval in ms (minimum 10 000). |
| `wasmAssetName` | String | `"release.wasm"` | Asset filename to look for in GitHub Releases. |

---

## How it works

```
App start
  └─ AppDelegate.onCreate()
       ├─ Hikotest.configure(config)   ← sets credentials & options
       └─ Hikotest.initialize(context) ← downloads WASM, loads runtime
            └─ background loop (every updateIntervalMs)
                 └─ checks GitHub for a new release tag
                      └─ hot-reloads WASM if tag changed (no app restart needed)
```

WASM is cached at `filesDir/hikotest_cache/`. On subsequent launches the cached file is used immediately; the network check runs in the background.
