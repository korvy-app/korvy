# Launcher – requirements (source of truth)

Working name: **Launcher**. Name candidates (invented words, 3 syllables, good as wake words; not yet checked
against USPTO, Play and domains): **Okuvi** (recommended), Vantiko, Orlune. Kivora is taken (an iOS app). Owner decisions are recorded here; read this first.

## Values, in priority order
1. **Privacy**: nothing leaves the phone unless the user asks (a web search). No accounts.
2. **Performance**: the home screen must feel instant on mid-range and older phones (test device: Pixel 4a).
3. **Clean and modern**: nice animations, but never at the cost of performance.
4. **Less LLM**: plain code wherever it works; the model only where language is messy.

## Background images
- Custom background from the user's photos (Android photo picker, no storage permission). Images are copied
  and scaled to the screen size once, so drawing them is cheap.
- **Slideshow (toggle):** rotate through chosen images with a smooth crossfade; interval setting; shuffle.
  Implemented as the app's own live wallpaper so the transition is smooth and also covers the lock screen
  where the phone allows. Pauses when the screen is off or in battery saver. Off by default on low-tier phones.

## Home-screen grid
- A grid of app icons between the clock and the suggestion row (4 × 5 for now; size setting later). Stored as
  `core/HomeGrid` in DataStore; hidden apps are removed from it (the layout is not encrypted, so no hidden app's
  key may stay there) and uninstalled apps drop out.
- One gesture per touch on any app icon (`ui/Drag.kt` → `appGestures`): **tap** opens; **hold** shows the menu;
  **hold and move** drags (the menu closes). Icons claim their touch, so holding an icon never also opens
  Settings; holding empty home space does. Swiping up opens the app list even when the swipe starts on an icon.
- Drag from the app list, search results or suggestions onto the grid (the list fades but keeps the gesture);
  drag within the grid to move (an occupied target swaps; from the list, the occupant moves to the nearest
  free cell); drag to "Remove from home" at the top. Menu: Add to home / Remove from home.

## Online features (decided 2026-11-22)
- One app (owner's choice). Android can't make INTERNET a runtime permission, so: **online features are off by
  default**; all network access goes through one gate class that refuses while they're off; each source
  (model downloads, weather, news, stocks, web search) has its own switch in Settings showing where it
  connects. GrapheneOS users can also block network for the app at the OS level. Settings and the tour say so.
- The assistant runs on **llama.cpp** (owner's choice), so it works in the F-Droid build too.

## Assistant implementation (0.3.0)
- **Engine:** llama.cpp as a git submodule at `app/src/main/cpp/llama.cpp`, pinned to release **b12634**; our JNI
  bridge is `app/src/main/cpp/assistant_jni.cpp` (load, complete with optional GBNF grammar, stop, unload).
  arm64 only; ggml picks the best CPU variant at runtime (`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`, so
  `jniLibs.useLegacyPackaging = true`). NDK 30.0.16248370, CMake 4.1.2. The APK grew from ~2 MB to ~11 MB.
- **Separate process:** `AssistantService` runs in `:assistant`; `AssistantClient` binds on use and unbinds after
  2 min idle; the service frees the model and kills its process on unbind. The launcher never holds a model.
- **Order of handling a request (less LLM):** `Calc` (maths, always code: small models get arithmetic wrong) →
  `AgentCore.quickParse` (open app, timer, weather, news, stock, dial, search) → the model, which may only output
  `{"action": …, "arg": …}` (grammar-constrained; few-shot examples; `enable_thinking=false`). Tools format results
  in code (`assistant/Tools.kt`); no second model pass. Hidden apps are never offered to it.
- **Models:** `assistant/Models.kt` catalog (Qwen 2.5 0.5B/1.5B/3B, Llama 3.2 1B/3B, Gemma 3 1B/4B, SmolLM2 1.7B,
  Phi 3.5 mini; Q4_K_M GGUF from Hugging Face, sizes and SHA-256 recorded). Private storage, resumable downloads,
  checksum verified, Wi-Fi only unless allowed, RAM warning per model.
- **Personality:** `agent.md` in private storage (`assistant/Personality.kt`), presets, size meter; rules come
  after it in the system prompt so it can't override them.
- **Online sources** (`platform/Net.kt`, https only, host allow-list per source, redirects re-checked):
  Model downloads (huggingface.co, hf.co), Weather (Open-Meteo), News (NPR, BBC RSS), Stocks (Yahoo Finance
  public chart API: the only keyless quote source that worked; Stooq and Nasdaq failed), Quick answers
  (DuckDuckGo instant answers), Web search suggestions (DuckDuckGo autocomplete, **opt-in** even when online is on).
- Android's ICU regex differs from the desktop JVM (e.g. a bare `}` is an error on Android): regexes in `core/`
  must be checked on a device, not only by unit tests.

## Power button and voice (0.4.0)
- **Power button:** `AssistActivity` answers `android.intent.action.ASSIST`, which qualifies the app for Android's
  Digital assistant role (verified on Android 13). The user picks it in Default apps → Digital assistant app
  (Settings → Assistant shows the steps and a button); on Pixels the power button must also be set to
  "Digital assistant" under Gestures. The panel is a see-through activity in its own task (not in Recents) that
  slides up over the current app, shares the conversation (`assistant/AssistantController`, app-wide in
  `LauncherApp`), closes itself when a request opens an app or page, and can expand to the full assistant screen.
- **Voice input:** `assistant/VoiceInput` uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` only (Android 12+);
  no online fallback, ever. Mic permission is asked on first use. Starts automatically when opened by the power
  button (setting). The mic is on only while listening (Android shows its indicator).
- **Spoken replies:** `assistant/Speaker` wraps Android TextToSpeech (any installed engine); Settings → Assistant →
  Voice: speak Always / When I speak to it (default) / Never, engine, voice (offline voices by default), speed,
  pitch, test. Model answers are spoken sentence by sentence as they stream (`core/SentenceSplitter`).
- Later: bundled open-source recognizer (whisper.cpp / Moonshine) and voices (Piper), wake word.

## Search
- Ranking (`core/AppSearch.kt`): exact > starts with > a word starts with > initials ("gm" → Google Maps) >
  contains > **typo match** > letters in order. Accents and case are ignored.
- Typos: Damerau–Levenshtein distance (a swap of neighbouring letters counts as one) against the start of the
  name and of each word. Allowed mistakes: 0 for queries under 4 letters, 1 for 4–6, 2 for 7+.

- **Autocomplete:** the rest of the top app's name shows greyed after the cursor (`core/Completion`, only when the
  name starts with the typed text); the → button accepts it. Maths typed in search shows its result (`core/Calc`).
  With the opt-in "Web search suggestions" source, DuckDuckGo suggestions appear under the apps (debounced 250 ms).

## Device stats (Settings → Device stats)
- This app: CPU share, memory, frames drawn per second and dropped frames (FrameMetrics; a frame is dropped
  past twice the refresh budget, the AndroidX JankStats rule), with a "smoothness test" animation because an
  idle screen draws nothing.
- Phone: RAM used/total, core count and current speed per core, battery level, drain/charging current, power,
  voltage, temperature, thermal state and headroom. Read live every second while open; nothing is stored.
- Later: the same as a small **overlay** on top of other apps (needs the "display over other apps" permission).

## Distribution and money
- Open source, **GPLv3**, on GitHub. Name and logo trademarked so forks can't impersonate it.
- Public promise: never sold to an ad-tech or data company.
- **Play** build: free download, **$4.99 one-time Pro unlock** (Play Billing; local prices per country, launch sale).
- **F-Droid / GitHub** build: everything unlocked, **no Google code at all**, donation link.

## Telemetry
- **None.** No analytics, no crash reporting, and no server run by the developer (nothing for the app to
  contact, and nothing for anyone to attack). Play Console's Android vitals (collected by Android itself, no code in the app) is the only crash
  signal.
- Feedback: GitHub Issues, a "Report a problem" button that opens a pre-filled email, a Play beta channel.
- Maybe later: opt-in, anonymous reporting to a third-party privacy-focused service. Not in v1.

## Home screen and search
- Home screen, app drawer, type-to-search, gestures, widgets.
- Search order: apps, contacts, settings, calculator (all local), then the web.
- Web search engine is the user's choice: DuckDuckGo (opens results), Brave Search, others.

## Suggested apps (time and day patterns)
- A local database of app launches (app, hour, day of week; later optional signals such as headphones or
  charging). Plain scoring code, not the language model: frequency weighted by time-of-day and weekday
  closeness, with older launches fading out. The assistant can use it to answer "what do I usually open now?".
- Shown as a small, fast suggestion row. Hidden apps are never recorded or suggested.
- Stored only on the device, excluded from backups. "Forget history" button and per-app "never suggest".
- Launches from inside the launcher need no permission; counting launches from elsewhere would need Usage Access,
  which stays optional.

## Hidden apps
- **Level 1 – Hide:** removed from home, drawer, search, suggestions, widgets, the assistant's view and any logs.
  Reachable from Settings → Hidden apps (optionally by typing the exact name).
- **Level 2 – Hide and lock:** the hidden list and launching those apps need fingerprint, face or the phone PIN
  (AndroidX BiometricPrompt, not Play services).
- The hidden list is stored on the device, encrypted, and excluded from backups.
- Nothing outside the locked/hidden screens reveals that hidden apps exist: no counts or badges (Settings shows
  only "Hidden apps", never how many).
- The app says plainly that hiding only applies in this launcher (apps still show in Android Settings).
- Later: Android 15+ Private Space support (check the permission requirements first).

## On-device assistant (optional, off by default)
- Default: **None**. The launcher is fully usable without it.
- User chooses a model: Gemma (Google), Qwen, Llama, Phi, SmolLM, others. Each shows maker, size, license and
  memory needed. Warn when the phone has too little memory.
- Engine: llama.cpp (one engine for all models); optionally Google LiteRT-LM for speed on new phones.
- Downloads: Wi-Fi only by default, checksum verified, one-tap delete.
- Runs entirely on the phone; prompts are never sent anywhere.
- **Personality: `agent.md`**, editable in the app.
  - Built-in presets (e.g. Concise, Friendly, Playful); import and export.
  - Length limit: small models are slowed by long prompts, so the editor shows a size meter.
  - Fixed rules the personality can't override: ask before sending messages, calling, or changing settings;
    never see hidden apps.
- Jobs: plain-language commands (text, call, timers, open apps, settings toggles), smart search, notification
  summaries. Not: live facts (use web search), booking or acting on websites.

## Voice
- **Ways to start listening:** mic button; **holding the power button** (the app registers as the phone's digital
  assistant, and the user picks it in Settings → Default apps → Digital assistant; works where the phone maker
  routes the power-button hold to the default assistant, e.g. Pixels); and an optional **wake word**.
- **Wake word (opt-in, off by default):** the app's name or a word the user picks (e.g. "Phone"). Runs on a tiny
  open-source keyword model (e.g. openWakeWord), never the big model. Android requires an ongoing notification
  and shows the green mic indicator while it listens; costs some battery; pause it in battery saver. Words must
  be distinctive (3 syllables), so plain words like "Phone" will trigger falsely more often; the app warns.
- **Speech input** (microphone), user's choice:
  - Bundled open-source on-device recognizer (e.g. whisper.cpp or Moonshine; Vosk for older phones).
  - Or the phone's own on-device recognizer.
  - Nothing is sent to a server; the microphone is only on while the user is holding or tapping the mic button.
- **Speech output** for the assistant's replies, user's choice of engine and **voice**:
  - Bundled open-source neural voices (e.g. Piper, many voices and languages; Kokoro for higher quality on
    fast phones), downloaded on demand.
  - Or the phone's own text-to-speech engine and any voice installed there.
  - Speak sentence by sentence as the reply is generated, so speech starts quickly.
  - Speed and pitch settings; a mute button.

## Performance rules
- **Device tiers** chosen on first run: RAM, CPU core count and speed, Android version, Android's performance
  class. Low tier (e.g. under 4 GB RAM, fewer than 6 cores, or Android older than 10) defaults animations, the
  wallpaper slideshow and the wake word to **off**. Every one is a user toggle in Settings.
- Cold start to a usable home screen: target under 300 ms on the Pixel 4a. Baseline profiles from day one.
- Animations at the display's refresh rate (60/90/120 Hz); spring-based motion; follow Android's
  "Remove animations" setting; lighter effects (no heavy blur) on low-end phones.
- **AI and voice run in a separate process** that is only started when used and stopped after idle, so the
  launcher itself stays small in memory and Android doesn't kill and redraw the home screen.
- Models are never loaded at launch. Benchmarks for startup, scrolling and search on every release.

## Engineering (same choices as Piano Notes)
- Kotlin + Jetpack Compose, Material 3; minSdk 26, targetSdk/compileSdk 37; AGP 9.5, Gradle 9.8, JDK 17 target
  (build with `JAVA_HOME=$(/usr/libexec/java_home -v 20)`).
- Package `com.hiddenoutlier.launcher` (placeholder until the name is chosen; it can't change after the first
  Play upload). Flavours: `play` and `foss` (`.foss` suffix). Debug builds add `.debug`, so all can coexist.
- No Google Play services, Firebase, analytics or network libraries. The app has **no INTERNET permission**.
- Storage: DataStore (settings), plain SQLite (`usage.db`, launch history), Keystore-encrypted file
  (`privacy.bin`, hidden apps). `allowBackup=false` and data-extraction rules exclude everything.
- Code map: `core/` pure logic with unit tests (Suggest, AppSearch, DeviceTier); `data/` stores and the app
  list (LauncherApps); `platform/` Android services (Auth, Device, Stats); `ui/` Compose screens.

## Test on a phone
- Unit tests: `./gradlew testPlayDebugUnitTest`. Each change must build and pass them; new logic gets tests.
- Try it: `./gradlew installPlayDebug` on a test phone (see `CLAUDE.local.md` for the owner's devices).
- **Performance must be judged on release builds** (debug Compose is several times slower):
  `./scripts/publish-test.sh` builds the optimised APK and copies it to a file share on the owner's network
  (set in `local.properties`, never committed). On a phone: open the share with a file manager that speaks SMB
  (e.g. Material Files: Add storage → SMB server), tap `launcher-play-latest.apk`, allow installs from that app.
- Measured 2026-11-22, Pixel 4a, release APK (2.1 MB): cold start 417 ms (target < 300 ms; baseline profiles
  next). Debug build: 1.7 s.

## Status / log
- 2026-11-22 (later): **0.4.1** – Privacy fix: Settings no longer shows how many apps are hidden.
- 2026-11-22 (later): **0.4.0** – Hold the power button for the assistant (panel over any app), voice input
  (on-device recognizer only) and spoken replies with engine/voice choice. The assistant moved into an app-wide
  `AssistantController`. Checked on the Pixel 4a release build: the app was accepted as Digital assistant; the
  assist key opened the panel over Calculator; mic permission prompt then "Listening…" with the system mic
  indicator; silence timed out cleanly; typed weather answered in the panel; Test voice and a spoken weather
  reply played through Google's embedded (offline) voice. Speaking to it wasn't tested (no way to talk to the
  test phone remotely).
- 2026-11-22 (later): **0.3.0** – On-device assistant (see "Assistant implementation"), Online features with
  per-source switches, autocomplete in search, calculator, picture crop step, and fixes:
  - Fixed: scrolling search results could start a drag (a scroll was mistaken for a long press). Now a drag
    needs a still long-press; moving first = scrolling.
  - New: pictures that aren't screen-shaped open a crop screen (drag the screen-shaped frame, pinch to zoom);
    only that part is saved (`core/CropFrame`).
  - Privacy: the app is excluded from autofill services (Google's autofill had offered to save typed text).
  - Fixed: Enter in the assistant box added a new line instead of sending; the search bar label wrapped.
  - Checked on the Pixel 4a release build: scrolling results, hold-and-drag from results, crop of a wide picture
    (exact part shown on home), weather and stock answers, Qwen 0.5B download (checksum OK) and a model answer.
    The 0.5B model answered "18% of 64" wrongly (115.2), which is why maths now always goes to `Calc`.
- 2026-11-22 (later): **0.2.0** – Home-screen grid with drag and drop (see "Home-screen grid"). Fixed: slideshow
  never advanced for people who leave the home screen often (the countdown restarted on every return); the
  picture now follows the clock (`core/slideshowIndex`). Checked on the Pixel 4a: drag from list to grid, move,
  swap, remove, hold = menu only, tap, swipe up from an icon, list scroll; slideshow with 4 pictures at 1 min
  advanced on home and across leaving and returning. 33 unit tests.
- 2026-11-22 (later): **0.1.2** – Fixed: the keyboard flashed for a moment when returning home after opening
  an app from search (now hidden and unfocused before leaving; `stateAlwaysHidden`). Fixed: the clock lagged up
  to a minute after the phone's time or time zone changed (e.g. airplane mode off after travel); it now follows
  Android's TIME_TICK / TIME_CHANGED / TIMEZONE_CHANGED / DATE_CHANGED broadcasts and refreshes on resume.
  Checked on the Pixel 4a: time-zone switch updated the clock within ~1.5 s; keyboard stayed hidden after Home.
- 2026-11-22 (later): **0.1.1** – Fixed: choosing "Make this my home screen" on the tour's last page restarted
  the tour (Android relaunches the new default home app before the tour was marked done). Asking for the home
  role from the tour now counts as finishing it. New: if the tour is skipped and this isn't the default home
  app, a dismissible "Make this your home screen?" card sits above the search bar (Not now remembers the
  choice; Settings keeps the same button). Checked all three paths on the Pixel 4a release build.
- 2026-11-22: **Prototype 0.1.0.** Requirements gathered from the owner, then built:
  - Home screen (clock, time-of-day suggestions, search bar, swipe up for all apps, long-press for Settings),
    app drawer with ranked and typo-tolerant search and web search (DuckDuckGo, Brave, Startpage, Mojeek).
  - Hidden apps: Hide and Hide and lock (BiometricPrompt), encrypted list, reveal by exact name (toggle),
    never recorded in history. Long-press menu: Hide, Hide and lock, Never suggest, App info, Uninstall.
  - Suggestions from local launch history (`Suggest`), "Forget app history".
  - Device tiers set animation defaults (Pixel 4a = mid); animations toggle.
  - Custom backgrounds (photo picker, scaled copies) and crossfading slideshow (1/5/15/60 min).
  - First-run tour (6 pages, re-openable), "Make this my home screen" (RoleManager).
  - Device stats screen.
  - 22 unit tests pass. Checked on the Pixel 4a: tour, home, search ("pn" → Piano Notes, launch), hide and
    reveal-by-name, settings, stats (54 fps / 3% dropped in the smoothness test on debug), release build.
  - Not built yet: assistant/model, voice, wake word, power-button assistant, Private Space, widgets,
    stats overlay, baseline profiles, Pro unlock, UI tests.
