# AGENTS.md

## Pantry — persistent notes

You have access to a persistent note storage system via the `pantry` MCP tools.

**Session start — MANDATORY**: Before doing any work, retrieve notes from previous sessions:

- Call `pantry_context` to get recent notes for this project
- If the request relates to a specific topic, also call `pantry_search` with relevant terms

**Session end — MANDATORY**: After any task that involved changes, decisions, bugs, or learnings, call `pantry_store` with:

- `title`: short descriptive title
- `what`: what happened or was decided
- `why`: reasoning behind it
- `impact`: what changed
- `category`: one of `decision`, `pattern`, `bug`, `context`, `learning`
- `details`: full context for a future agent with no prior knowledge

Do not skip either step. Notes are how context survives across sessions.

## Token Usage Optimization

To keep context windows clean and save tokens:
- Use **Grep** to find specific functions/variables instead of reading entire large files.
- Restrict file reading bounds if the file is massive.
- Do not proactively load generated code (`build/`, `.gradle/`, minified scripts) into context.
- Be concise in your thoughts and responses.

## Cursor Cloud specific instructions

### Project overview

This is **CWS (CrossWord Sync)** — a Kotlin/Jetpack Compose Android application. **Kotlin / manifest namespace** stays `space.u2re.cws`; **application ids** are chosen per product flavor (see below). It is a multi-device synchronization system with clipboard sharing, encrypted communication, and command forwarding.

### Prerequisites

- **JDK 21** at `/usr/lib/jvm/java-21-openjdk-amd64` — must set `JAVA_HOME`.
- **Android SDK** at `/opt/android-sdk` — requires `platforms;android-36`, `build-tools;36.0.0`, and `platform-tools`.
- `local.properties` with `sdk.dir=/opt/android-sdk` must exist in the project root (gitignored).

### Key commands

| Task | Command |
|---|---|
| Assemble debug (**default hybrid** `space.u2re.cwsp` — CWSP + embedded WebView) | `./gradlew :app:assembleCwspDebug` or `npm run build` |
| Assemble debug (standalone `space.u2re.cws` — Kotlin-only) | `./gradlew :app:assembleCwsDebug` or `npm run assemble:cws` |
| Lint (cws debug) | `./gradlew :app:lintCwsDebug` |
| Unit tests | `./gradlew :app:testCwsDebugUnitTest` (or `testCwspDebugUnitTest`) |
| Full build (compile+test+lint) | `./gradlew build` |

All Gradle commands require `JAVA_HOME` and `ANDROID_HOME` to be set. **`npm run dev`** / **`npm run assemble`** / **`npm run build`** default to the **cwsp** hybrid flavor (`space.u2re.cwsp`). Use **`npm run dev:cws`** / **`assemble:cws`** for the Kotlin-only **`space.u2re.cws`** package.

### Product flavors (`applicationId`)

| Flavor | `applicationId` | Use |
|--------|------------------|-----|
| **cwsp** | `space.u2re.cwsp` | **Default hybrid**: same `applicationId` as `runtime/cwsp/capacitor.config.ts` — embedded CWSP WebView + Kotlin (see Settings → Open web shell). |
| **cws** | `space.u2re.cws` | Kotlin/Compose-first standalone; use `-PcwsAdbFlavor=cws` with `attachDebug` or `npm run dev:cws`. |

`attachDebug` uses **cwsp** by default; pass `-PcwsAdbFlavor=cws` to install/launch the Kotlin-only package.

### CWSP Capacitor (embedded web UI)

The app includes **Capacitor** (`@capacitor/android` from the monorepo `runtime/cwsp` npm install) as a second shell alongside Compose:

- **Gradle** includes `:capacitor-android` from the nearest ancestor directory that contains `runtime/cwsp/node_modules/@capacitor/android/capacitor` (typical monorepo checkout), or override with `CWS_CAPACITOR_ANDROID_DIR`.
- **Settings repositories**: `dependencyResolutionManagement` uses `PREFER_SETTINGS` so the Capacitor library’s own `repositories {}` block does not fail the build (would conflict with `FAIL_ON_PROJECT_REPOS`).
- **Root `extra` properties** in `build.gradle.kts` mirror `runtime/cwsp/android/variables.gradle` so the Capacitor library sees the same SDK and AndroidX versions as the standalone Capacitor Android project.
- **Web assets**: `preBuild` runs `syncCwspCapacitorWeb`, copying `runtime/cwsp/dist/capacitor` → `app/src/main/assets/public` when that directory exists. Produce it with `npm run build:capacitor:web` or `build:capacitor` inside `runtime/cwsp`, or run `node scripts/build-cws-android.mjs --with-capacitor-web` from cwsp before Gradle.
- **UI entry**: Settings → General → **Open web shell** launches `CapacitorWebActivity` (`BridgeActivity`).

### Gotchas

- **No physical device or emulator** is available in the Cloud VM. You can build and lint but cannot deploy or run the app on a device.
- The `endpoint` and `airpad` symlinks in the repo root are **broken** (they point to a sibling repo `../U2RE.space/` that doesn't exist in this workspace). They are not build dependencies.
- Unit test task reports `NO-SOURCE` because the project currently has no unit test files — this is expected, not an error.
- Gradle auto-downloads additional SDK components (e.g., `build-tools;35`) during the first build if they're missing — this is normal.
- The `audioswitch-stub` module is a stub replacement for a Twilio dependency and has no Kotlin sources.

---

## Some specifications

Search and read in:
- `/home/u2re-dev/U2RE.space/modules/projects/uniform.ts/src/newer/` (internal)
- `/home/u2re-dev/U2RE.space/runtime/cwsp/endpoint/` (network)

---

## Выбор модели (Model Selection)

- **Анализ архитектуры**: Claude 4.6 (Sonnet / Opus) / GPT-5.4 + max thinking
- **Баги, тесты, доработки**: Claude 4.5 Haiku / Gemini 3 Flash / GPT-5.3-codex-spark (для экономии токенов и времени на простых задачах)
- **Архитектура, план**: Claude 4.6 (Sonnet / Opus) / Gemini 3.1 Pro / GPT-5.4
- **Документации или спецификации**: Claude 4.6 (Sonnet / Opus) / Gemini 3.1 Pro / GPT-5.4 (low или medium thinking)
