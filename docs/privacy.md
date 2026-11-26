# Korvy Privacy Policy

_Last updated: November 23, 2026_

Korvy Launcher ("Korvy") is an open-source Android home screen with an optional assistant that runs on your phone.
This policy describes what happens to your information when you use it. Short version: **we don't collect
anything.** There are no accounts, no ads, no analytics, no crash reporting, and no Korvy server for your data to
go to.

## What stays on your phone
Everything you do in Korvy is stored only on your device: your home screen, hidden and locked apps, app-usage
patterns used for suggestions, the assistant's notebook and people shortcuts, chat history, settings, downloaded
models and voices. Hidden apps, the notebook and people shortcuts are encrypted with a key kept in your phone's
secure hardware. Korvy excludes its data from Android backups. Uninstalling Korvy deletes all of it.

- **Microphone:** used only while you are speaking to the assistant (after you tap the mic or hold the power
  button). Speech is turned into text on your phone, by your phone's on-device recognizer or by Whisper running
  inside Korvy. Audio is never stored or sent anywhere.
- **Contacts (optional):** if you allow it, Korvy reads your contacts on the phone to show them in search and to
  text or call people when you ask. Contacts are never sent anywhere.
- **Location (optional):** if you allow it, Korvy reads your approximate location only to get the weather
  "where you are" (see Online features).
- **Screen text (optional):** when you hold the power button over an app (with Korvy as your digital assistant),
  Korvy can read the visible text of that screen so you can ask about it ("summarise this", "text this to Jen").
  It is read only when you call the assistant, processed on your phone, kept only for that conversation and
  never stored or sent anywhere. Korvy never reads hidden or locked apps, or password and card fields. Turn it off
  in Settings ("Read the screen when I call Korvy") or in Android (Digital assistant → Use text from screen).
- **The assistant's AI model** runs entirely on your phone. Your questions are never sent to us or to an AI company.

## Online features (off by default)
Korvy works without the internet. Online features are off until you turn them on, each source has its own switch
(Settings → Online features), and Korvy can only connect to the sites listed for each source:

| Feature | Service | What is sent |
|---|---|---|
| Model downloads | Hugging Face (huggingface.co) | The file you chose to download |
| Voice downloads | GitHub (github.com) | The voice or model file you chose |
| Weather | Open-Meteo (open-meteo.com) | The place you asked about, or your approximate location rounded to about 1 km |
| News headlines | NPR, BBC public feeds | Nothing about you (Korvy just fetches the feed) |
| Stock prices | Yahoo Finance (query1.finance.yahoo.com) | The company or ticker you asked about |
| Quick answers | DuckDuckGo (api.duckduckgo.com) | Your question |
| Search suggestions | DuckDuckGo (duckduckgo.com) | What you type in the search bar |

Like any internet request, these services see your IP address. Their own privacy policies apply. Korvy sends no
identifiers, account details or tracking information with these requests.

**Web searches, links, rides, messages and calls** open in your own browser or the app you chose (for example your
messaging or maps app); Korvy always asks before texting, calling or booking.

## Purchases
On Google Play, the optional Pro unlock is sold through Google Play. Google handles the payment; Korvy only learns
whether Pro is unlocked. We never see your payment details. See Google's privacy policy.

## Children
Korvy is not directed at children under 13.

## Changes
If this policy changes, the new version will be published at https://korvy.app/privacy with a new date.

## Contact
Questions: open an issue at https://github.com/korvy-app/korvy/issues.
The source code is public, so anyone can check these statements.
