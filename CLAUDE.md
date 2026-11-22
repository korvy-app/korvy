# Notes for Claude sessions

A privacy-first, fast Android launcher. Working name "Launcher"; the final name is still to be chosen and
trademarked (candidates and checks in REQUIREMENTS.md).

## Read first
- **`REQUIREMENTS.md` is the source of truth**: the owner's decisions, priorities, engineering choices, test
  procedure and a dated status log. Update its status log with every meaningful change.
- If present, **`CLAUDE.local.md`** (git-ignored) has the owner's devices and home-network details. Never copy
  anything from it into committed files: this repo is meant to become public.

## Non-negotiables (from the owner)
- **Privacy:** no analytics, no crash reporting, no accounts, no ads, no network calls from the app. The app has
  no INTERNET permission; adding one needs the owner's explicit approval. No Google Play services or Firebase.
  Google *code* may appear only in the `play` flavour (e.g. Play Billing for the Pro unlock), never in `foss`.
- **Performance is a top value alongside privacy.** Nothing heavy at startup; models and voice load on demand
  in a separate process. Judge speed only on release builds (debug Compose is several times slower).
- **Less LLM:** use plain code wherever it works (suggestions, search); the optional model only where language
  is messy. The model and voices are user-chosen and off by default.
- Hidden apps must never leak: not into search (except the exact-name reveal setting), suggestions, history,
  the assistant's view, logs or backups.
- Every visual effect must respect the animations toggle (`LocalAnimations` / `Motion`), and low-tier phones
  default to light effects (`core/DeviceTier.kt`).

## Build and test
```
export JAVA_HOME=$(/usr/libexec/java_home -v 20)
./gradlew testPlayDebugUnitTest      # unit tests (must pass before a change counts as done)
./gradlew installPlayDebug           # debug build on a connected phone
./scripts/publish-test.sh            # optimised APK to the owner's file share (configured in local.properties)
```
- Same toolchain as the owner's other app (Piano Notes): Kotlin, Compose, Material 3, minSdk 26, target 37,
  AGP 9.5. AGP 9 turns on R8 full mode: anything created by reflection needs keep rules; check a release
  build launches before any store upload.
- Compose test tags exist on the main controls (`search_bar`, `search_field`, `drawer`, `menu_hide`, …) for
  future UI tests.

## Conventions
- Commits: author `haysoos <4546210+haysoos@users.noreply.github.com>` (never a personal email).
- Plain, friendly UI text in US English; explain privacy trade-offs honestly in the app.
- New logic goes in `core/` as plain Kotlin with unit tests.
