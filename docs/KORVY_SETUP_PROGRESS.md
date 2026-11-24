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
- [ ] Searches done
- [ ] Classes and basis decided
- [ ] Filed

## Phase 6: Store and release
- [ ] Play listing / F-Droid metadata
- [ ] Release checklist
