# Intercom Satelite

Unofficial Android companion for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite). Install a signed APK on Android 12 or later, enter the same Intercom key as your kiosks, and enable the app's phone account to receive calls in the system Phone app. The package ID is unchanged from Intercom Pocket; updates must be signed with the original signing certificate.

Calls and announcements stay on the local network. Audio is never recorded. No Kiosk Satellite code, logo, or assets are bundled.

## Features

- Two-way calls from phone to kiosk and kiosk to phone, with system-managed incoming Android calls
- Push-to-talk, hands-free mode, speaker control, and certificate pin confirmation
- One-to-many announcements to available kiosks; a single failed kiosk does not end the other audio sockets
- Announce to all available kiosks, a one-time selection, or saved local room groups; each recipient's connection status is shown
- Independently disable incoming announcements while keeping individual calls enabled
- Home-screen kiosk widget with a confirmed per-widget destination
- Optional start after boot or app update, if reception was enabled before restart
- Local metadata-only call history with verified callback; no audio or shared secret in history
- Weekly quiet hours, overnight schedules, and selected kiosk exceptions; manual Do Not Disturb still blocks everyone
- Temporary Do Not Disturb for 30 minutes, two hours, or until 07:00 tomorrow; expiration preserves scheduled quiet hours
- Room aliases, icons, favorites, and system/light/dark themes with teal, blue, plum, or Android dynamic accent colors
- A dedicated settings page, a card-based call screen, and a full call-history page with callbacks

Long-press a kiosk row to edit its name, symbol, or favorite status. Add a widget from the Android home screen and select its kiosk. The widget opens the app, rechecks the saved kiosk identity, and then calls. Boot reception and quiet hours are configured in settings.

Use **Raumgruppen verwalten** on the home screen to create, edit, or delete groups of 1–32 previously connected kiosks. Membership follows kiosk IDs, not IP addresses or aliases. **Durchsage starten …** offers all available kiosks, a one-time selection, and saved groups. Only currently discovered or manually added, ready members are contacted; offline members are shown and an empty/unavailable selection never falls back to all kiosks. Groups remain local to this phone.

In settings, switch **Durchsagen empfangen** off and save to refuse incoming announcements; the existing default is on. Calls still ring unless Do Not Disturb or quiet hours block them. **Nicht stören für …** on the home screen stores an expiration time that survives an app/device restart. The ordinary switch still enables indefinite Do Not Disturb, or cancels the current timer when turned off. At expiration, call handling uses the current quiet-hour rules immediately; the background notification refreshes within five seconds while the service is running.

German guides: [Installation and setup](docs/Einrichtung.md) · [Version 1.3.0 validation](docs/Pruefbericht-1.3.0.md).

## Build and signing

Requires JDK 17, Android SDK platform/build-tools 35, and Gradle 8.13.

```
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The Gradle release output is unsigned. Sign it with your own key. An update to the previously distributed APK must use its original signing certificate, which is deliberately absent from this public repository.

## Protocol and security

Implements the public Kiosk Satellite intercom protocol independently: NSD `_kiosk-satellite._tcp.`, local identity and call routes, HMAC-SHA256 bearer tokens, replay protection, authenticated WebSockets, and PCM16LE 16 kHz mono audio. TLS is optional and requires explicit certificate pin confirmation per endpoint; there is no fallback to plaintext after enabling TLS. The shared key is encrypted with Android Keystore AES-GCM.

The Android `connectedDevice` foreground service maintains LAN reception. Microphone and `phoneCall` foreground types are added only when needed. The phone account uses `ConnectionService` without taking the default dialer role or reading the call log. Android and device-specific power management can still delay or block background reception.

## Validation

JVM tests cover protocol and call handling, real HTTP/WebSocket transitions, Android phone-session state, quiet-hour boundaries, announcement failure isolation and recipient selection, room-group persistence, incoming-announcement policy, and temporary Do Not Disturb expiration. Android Lint and a release build are run before distributing the signed APK. Hardware microphone behavior, Samsung Phone UI, widget placement, boot delivery, and visual layout on physical screen sizes still require device testing.

Dependencies and licenses: [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt). Protocol reference: [Kiosk Satellite intercom documentation](https://kiosksatellite.com/docs/intercom/).
