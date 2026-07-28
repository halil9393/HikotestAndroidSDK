# Hikotest Android SDK

An Android SDK that fetches and runs **WebAssembly (WASM) business logic dynamically** — no app update required. The runtime is powered by [Chicory](https://github.com/dylibso/chicory) (pure-JVM, no JNI). The SDK pulls `release.wasm` from the Hikotest panel's edge-cached OTA endpoint (or directly from a GitHub Release on the legacy path), caches it locally, and hot-reloads automatically in the background.

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
                // Preferred: poll the panel's edge-cached OTA endpoint. No GitHub
                // token ships in the app, and N devices share one edge cache instead
                // of each hitting GitHub's 5 000/h rate limit.
                .panelBaseUrl(BuildConfig.HIKOTEST_PANEL_BASE_URL) // e.g. "https://hikotest.app"
                .projectId(BuildConfig.HIKOTEST_PROJECT_ID)         // panel projects.id (UUID)
                .environment(BuildConfig.HIKOTEST_ENVIRONMENT)
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

### Hybrid mode — OTA lock (device-side guarantee)

Some functions (payments, pricing, authorization) should **never** change
over-the-air. Download a release you trust from the panel
(`release.wasm` + `manifest.json`), ship it in your app's `assets/` and pass
it at configure time:

```kotlin
Hikotest.configure(HikotestConfig.Builder()
    .githubToken(token).repoOwner("acme").repoName("hikotest-build")
    .localBundle(
        assets.open("release.wasm").readBytes(),
        assets.open("manifest.json").bufferedReader().readText(),
    )
    .lockedFunctions("applyPayment") // device-side lock list (optional)
    .build())
```

- A function runs from the **embedded local bundle** when the live manifest
  marks it `ota:false` (locked in the panel) or it is in your `lockedFunctions`
  list. Everything else keeps hot-reloading from the live OTA bundle.
- **The device wins.** The union is one-way: even if the panel unlocks a
  function remotely, anything in `lockedFunctions` keeps running from the
  embedded bundle until you ship an app update.
- A locked function missing from the embedded bundle raises a clear error —
  there is no silent OTA fallback.
- **Fully offline mode:** configure only `localBundle` (no repo) and call
  `Hikotest.initialize()` (no context needed) — the SDK never touches the network.
- `Hikotest.isLocallyPinned(name)` tells you where calls are served from.

---

## Configuration reference

Configure **either** the panel path (preferred) **or** the legacy GitHub path — not both.

| Field | Type | Default | Description |
|---|---|---|---|
| `panelBaseUrl` | String | — | **Preferred.** Panel origin (e.g. `https://hikotest.app`). Devices poll the edge-cached `/api/ota/:projectId/latest`; no token on the client. **Required with `projectId`.** |
| `projectId` | String | — | Panel `projects.id` (UUID). **Required with `panelBaseUrl`.** |
| `githubToken` | String | — | *Deprecated (legacy GitHub path).* GitHub PAT. Required with a repo. |
| `repoOwner` | String | — | *Deprecated.* GitHub username or org. Required with a repo. |
| `repoName` | String | — | *Deprecated.* Repository name. Required with a repo. |
| `environment` | String | `"production"` | Arbitrary label forwarded to the WASM context. |
| `isBeta` | Boolean | `false` | `true` fetches the latest release including pre-releases. |
| `updateIntervalMs` | Long | `60000` | Background polling interval in ms (minimum 10 000). |
| `wasmAssetName` | String | `"release.wasm"` | Asset filename to look for in GitHub Releases. |
| `manifestAssetName` | String | `"manifest.json"` | Manifest asset filename (OTA lock flags come from here). |
| `localBundle(bytes, manifestJson?)` | — | — | Embedded pinned release for hybrid/offline mode. |
| `lockedFunctions(vararg names)` | — | empty | Device-side lock list; requires `localBundle`. |

---

## How it works

```
App start
  └─ AppDelegate.onCreate()
       ├─ Hikotest.configure(config)   ← sets credentials & options
       └─ Hikotest.initialize(context) ← downloads WASM, loads runtime
            └─ background loop (every updateIntervalMs)
                 └─ checks the panel's edge OTA endpoint (or GitHub, legacy) for a new tag
                      └─ hot-reloads WASM if tag changed (no app restart needed)
```

WASM is cached at `filesDir/hikotest_cache/`. On subsequent launches the cached file is used immediately; the network check runs in the background.
