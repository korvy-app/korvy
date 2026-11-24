# Play Console → App content: suggested answers

## Data safety
- Does your app collect or share any of the required user data types? **Yes** (only through optional online features).
- Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS only).
- Do you provide a way for users to request that their data be deleted? **No** — nothing is stored by the developer
  (data is processed ephemerally by the services; on-device data is deleted by uninstalling).

Data types (all: **Collected: yes · Shared: no · Processed ephemerally: yes · Optional: yes · Purpose: App functionality**):
- **Location → Approximate location** (weather "where you are", only if allowed).
- **App activity → In-app search history** (search suggestions / quick answers from DuckDuckGo, only if turned on).
- **App activity → Other user-generated content** (place names for weather, company names for stock prices).

Not collected (stays on the device): contacts, audio, messages, app usage, purchase history (handled by Google Play),
device identifiers. No data is shared with third parties for any other purpose; no advertising or analytics.

## Other declarations
- **Ads:** No ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating:** Utility/productivity app; no violence, sexual content, gambling, drugs or user-to-user chat.
  The app can open the web browser (answer "yes" to "unrestricted internet access" only if asked about a browser
  feature: Korvy opens links in the user's own browser).
- **Target audience:** 18 and over (avoids the Families policy; the app is for everyone but not designed for kids).
- **News app:** No. **Government app:** No. **Financial features:** None. **Health:** No.

## Sensitive permissions (declaration forms)
- **QUERY_ALL_PACKAGES:** core purpose **Device launcher** — a home screen must list every app to show and search them.
- **Foreground service, dataSync:** user-initiated downloads of the AI model and voices the user picked (hundreds of
  MB), shown with a progress notification and a Cancel button, so they finish when the screen turns off.
  _Play asks for a short video of this; I can record one on the test phone._
- **Foreground service, specialUse (stats overlay):** a performance overlay the user turns on in Device stats,
  showing CPU speed, memory, battery drain and temperature over other apps, with a notification to hide it.
  _Video as above._
- **RECORD_AUDIO, READ_CONTACTS, ACCESS_COARSE_LOCATION:** runtime permissions asked only when the feature is used,
  with an explanation in the app.
