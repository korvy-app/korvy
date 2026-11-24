# Korvy setup progress

Tracks the rename and launch steps from the Korvy naming guide. Tick items as they're done.

## Settled
- Name: Korvy ("KOR-vee"), wake word "Hey Korvy". Domain korvy.app (Namecheap, expires 2027-11-23).
- GitHub org: a variant such as `korvy-app` (the `korvy` user is taken).
- GPLv3; free on F-Droid/GitHub, $4.99 Pro unlock on Play.

## Found in the repo (2026-11-23)
- Old name: the placeholder "Launcher" (`app_name`, wake word "Hey Launcher", docs). No About screen,
  no `fastlane/` store metadata, no translations yet (UI text is in Kotlin, US English).
- Package/applicationId: `com.hiddenoutlier.launcher` (`.foss` and `.debug` suffixes).
- Wake word engine: sherpa-onnx open-vocabulary keyword spotter (3.3M gigaspeech model). A new phrase is a
  config change (word pieces + threshold), no training. "Hey Korvy" → `▁HE Y ▁K OR V Y`.
- Published? Not to Play, F-Droid or GitHub releases. Test APKs only, on the owner's 9 Pro and the test 4a.
- GitHub: no remote yet (`gh` not signed in). Checked 2026-11-23: `korvy` taken; `korvy-app`, `korvyapp`,
  `korvylauncher` available → use **`korvy-app`**.
- Owner is US-based (USPTO only; no attorney required for US-domiciled filers).

## Phase 1: Rename in the codebase
- [x] Owner confirms: never published, OK to change applicationId to `app.korvy.launcher`
- [x] List of hits reviewed by owner
- [x] User-facing name → Korvy (app label), "Korvy Launcher" for store/About
- [x] Package / namespace / source folders → `app.korvy.launcher`
- [x] Build, tests, launch on the test phone

Phase 1 notes: About screen added (Settings → About Korvy); welcome tour rewritten (it wrongly said "no internet
permission"); README rewritten; network User-Agent now "Korvy/1". Installed copies under the old ID must be
reinstalled (settings and downloads don't carry over).

## Phase 2: Wake word
- [x] Default phrase "Hey Korvy"
- [ ] Near-miss list and threshold tuning (recordings)
  - 2026-11-23 findings (bench: `scripts/kws-bench`, 6 macOS voices × "Hey Korvy" + 7 near-misses):
    the 3.3M keyword model hears "Korvy" as korvi/corvie, never as spelled (`K OR V Y`: 0/6 voices).
    Sounds-alike spellings (korvy/corvy/korvie/corvie/korvi/corvi) → 3/6 voices, 0 false triggers at
    every threshold; spellings that catch the other 3 voices fire on "hey curvy"/"hey coffee".
    Over the air (Mac speaker → 4a) "Hey Korvy" didn't wake; control "Hey Siri" did.
    Fixed: wake service crashed on a keyword label with a space ("@Hey Korvy").
  - Built: two-step check. Step 1 (keyword model) listens for 12 permissive spellings; step 2 sends the last
    ~3 s to Whisper (base.en, else tiny.en, downloaded by the user) and wakes only if the transcript has the name
    (`core/WakeCheck`: sound key, exact match). Whisper tiny on the bench voices writes "Hey Corvie" for Hey Korvy
    (5/6 voices) and never for a near-miss; all 15 near-miss transcripts are unit tests.
  - Phone tests with synthetic speech are inconclusive: Mac speaker across the desk reaches the mic at ~1% level;
    the 4a's own speaker into its mic: step 1 fired on 2/3 "Hey Korvy" and 0/2 near-misses, but Whisper heard
    distorted audio ("K-core V.", "Hey Tori V") and rejected. **Needs a real voice test by the owner.**
- [ ] Spoken prompts/TTS with the old name updated

## Phase 3: GitHub
- [ ] Org created
- [ ] Repo created in the org and pushed
- [ ] korvy.app verified (TXT record)
- [ ] Links updated

## Phase 4: korvy.app website
- [ ] Static site on GitHub Pages with CNAME
- [ ] DNS records added and checked
- [ ] Privacy policy matching the code

## Phase 5: Trademark (owner files; tracked here)
- Decision 2026-11-23: register the word **KORVY** alone (standard characters), not "Korvy Launcher": it covers
  future Korvy products, and "launcher" is descriptive (it would have to be disclaimed anyway). Store/About
  keep "Korvy Launcher" as the product name. Class 9 first; Class 42 only if a hosted service ever exists.
- [ ] Searches done
- [ ] Classes and basis decided
- [ ] Filed

## Phase 6: Store and release
- 2026-11-23: Play app created ("Korvy: Private Launcher", free, automatic protection off), service account
  given testing-track access, upload key created (`~/.config/play/korvy-upload.jks`, password in
  `~/.config/play/korvy-upload.txt` and local.properties; back it up), Play build drops the wake-word mic
  service. **0.12.0 (code 29) uploaded to internal testing as a draft.** `app.korvy.launcher` is now permanent.
  Build: `./scripts/build-upload.sh`; upload: `./scripts/play-upload.sh upload … --track internal --notes … --draft`.
- [ ] Play listing / F-Droid metadata
- [ ] Release checklist
