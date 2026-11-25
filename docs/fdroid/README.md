# Submitting Korvy to F-Droid

The F-Droid build is the `foss` flavour (`app.korvy.launcher.foss`): no Google code, no prebuilt libraries,
Pro always unlocked, no natural voices or wake word (their engine, sherpa-onnx, is a prebuilt AAR).

Checked locally (2026-11-24):
- A fresh `git clone --recurse-submodules` builds with `./gradlew --offline assembleFossRelease`, without
  `app/libs/sherpa-onnx-*.aar`, after the recipe's `scandelete` paths are removed.
- `fdroid scanner` on that tree: 0 problems (1 harmless warning: a Keynote file in llama.cpp/docs).
- No downloads during the build: KleidiAI is a submodule (`-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI`).
- Store text and changelogs: `fastlane/metadata/android/<locale>/` (en-US, es, pt-BR, fr, de).

## Owner steps
1. Make a GitLab account (gitlab.com).
2. Fork https://gitlab.com/fdroid/fdroiddata and add `metadata/app.korvy.launcher.foss.yml` (copy of the file in
   this folder).
3. Open a merge request titled "New app: Korvy". In the description: open source launcher with an optional
   on-device assistant; the foss flavour is the F-Droid build; native code (llama.cpp, whisper.cpp, KleidiAI)
   comes from git submodules; online features are opt-in (expect the NonFreeNet anti-feature for Yahoo Finance /
   DuckDuckGo); AI models and voices are downloaded only when the user picks one.
4. The F-Droid CI builds it; reviewers may ask questions. Typical wait: weeks to a couple of months.

## Each new version
Bump `versionCode`/`versionName`, add `fastlane/metadata/android/*/changelogs/<versionCode>.txt`, commit, and push
a tag `vX.Y.Z`. F-Droid picks up new tags automatically (`UpdateCheckMode: Tags`).
