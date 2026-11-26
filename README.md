# Korvy Launcher™

A private, fast Android home screen, with an optional assistant that runs entirely on your phone.
Website: [korvy.app](https://korvy.app)

- **Private by design:** no accounts, ads, analytics or crash reporting, and no server of ours. Online features
  (weather, news, stock prices, model and voice downloads) are off by default, each with its own switch.
- **Fast:** an instant home screen even on older phones; animations switch off automatically on slower ones.
- Search apps (initials, typos), contacts and the web with the engine you choose; a calculator in the search bar.
- Hide apps, or hide them and lock them behind your fingerprint or PIN.
- Home-screen grid, widgets, your own backgrounds and slideshow, app suggestions for the time of day.
- **Korvy, the assistant (optional):** pick a local model (Gemma, Qwen, SmolLM and others, via llama.cpp). It opens
  apps, texts and calls (always asking first), sets alarms and timers, gives directions, plays music, and answers.
  Teach it notes, phrases and nicknames in its notebook. Voice input with whisper.cpp or the phone's on-device
  recognizer, natural offline voices (sherpa-onnx), and the power button.
- Device stats screen and overlay.

Free on F-Droid and GitHub (everything included, no Google code). On Google Play, a one-time Pro unlock supports
development. See [REQUIREMENTS.md](REQUIREMENTS.md) for the plan and decisions.

Bugs and ideas: [Issues](https://github.com/korvy-app/korvy/issues), or support@korvy.app.

## Build
JDK 17+ (see CLAUDE.md for the exact setup), then:
```
git submodule update --init      # llama.cpp, whisper.cpp and KleidiAI
./scripts/fetch-libs.sh          # sherpa-onnx (checksummed)
./gradlew assembleFossDebug      # or assemblePlayDebug
./gradlew testPlayDebugUnitTest
```

## License
GPL-3.0-or-later. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). "Korvy" is a trademark of the project: forks are welcome, under
another name.
