# Launcher (working name)

A clean, fast Android home screen that keeps what you do on your phone.

- No accounts, ads, analytics or crash reporting, and no internet permission.
- Search apps by name, initials or with typos; search the web with the engine you choose.
- Hide apps, or hide and lock them behind your fingerprint or PIN.
- Suggests apps for the time of day, worked out on the phone.
- Your own backgrounds and an optional slideshow; animations that switch off on slower phones.
- A live device stats screen (CPU, memory, frame rate, battery, heat).
- Planned: an optional on-device assistant with your choice of model, voice input and spoken replies.

Status: early prototype (0.1.0). See [REQUIREMENTS.md](REQUIREMENTS.md) for the plan and decisions.

## Build
Android Studio or the command line (JDK 17+):
```
./gradlew assemblePlayDebug   # or assembleFossDebug for the build with no Google code
./gradlew testPlayDebugUnitTest
```

## License
GPL-3.0-or-later. See [LICENSE](LICENSE). The name and logo will be trademarks of the project.
