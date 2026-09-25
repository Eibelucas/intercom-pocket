# Intercom Pocket

Version 1.1.0 adds a system-managed Android ConnectionService for incoming
calls. Enable the Intercom Pocket phone account once in the system settings
from the app's setup card. The existing dialer provides answer, reject,
hangup, mute and audio routing. Capture starts after answer and Telecom
focus, with continuous transmission until muted. Outgoing calls remain in
Intercom Pocket. The app does not replace the default dialer or make SIM
calls; its account only supports SIP-style local intercom addresses.

Call diagnostics distinguish local cancellation from remote endings and
show callback arrival and HTTP response stages. Both fixed-length and
chunked HTTP request bodies are accepted with a 64 KiB limit. A regression
test reproduces the old chunked-callback rejection. This does not establish
whether that was the cause of the user's physical-device failure.

Independent Android intercom companion for Kiosk Satellite. Native Java UI,
Android audio, NSD discovery, and local HTTP/WebSocket communication.
No code, logos or assets from Kiosk Satellite are included in this project.
The warm paper / teal palette follows the requested visual direction.

## Build

Requires JDK 17, Android SDK platform 35, build-tools 35.0.0 and Gradle 8.13.
Set `ANDROID_HOME` and run:

```
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The release APK is unsigned until signed with your own Android signing key.
The delivered APK was separately signed; its signing key is not embedded in
this source archive. Keep using the same signing key for app updates.

## Protocol

Reference: https://kiosksatellite.com/docs/intercom/
and the public jxlarrea/kiosk-satellite intercom routes and authentication format,
inspected September 24, 2026 (main branch at download time).

- `_kiosk-satellite._tcp.` service, plain identity endpoint at port 2324
- optional separate TLS listener at port 2325
- supports legacy identity without the `endpoint` field
- padded URL-safe Base64 payload/signature, HMAC-SHA256 with `intercom:` key
  prefix, expiry in milliseconds, replay protection
- calls, signals, PCM16 little-endian mono 16 kHz, 80 ms binary frames
- authenticated audio sockets, talk/end controls, ring/connect timeouts
- certificate pin confirmation per endpoint, no plaintext TLS fallback
- shared secret encrypted using Android Keystore AES-GCM

## Android behavior

Minimum API 31, target API 35. `connectedDevice` foreground service provides
LAN reachability. App calls add the microphone foreground type from the
visible activity. Native incoming calls add phoneCall and microphone types
after answer through the system-bound ConnectionService. A denied microphone
foreground upgrade ends cleanly and records the failure. No microphone opens
on an incoming ring. Telecom owns audio focus, mode and routing for native
calls; the app does not reset the system audio route when such a call ends.
If the account is disabled or microphone permission is missing, incoming
calls use the existing app notification/activity flow. API 35+ checks only
the app's registered accounts without phone-number permission. API 31–34
requires READ_PHONE_NUMBERS to query the enabled account, requested only
when enabling this feature. No READ_CALL_LOG or default-dialer role needed.
No automatic boot start. Ongoing reachability holds CPU/Wi-Fi locks and can
use additional battery. Device power management can still affect LAN calls.

## Validation

JVM protocol tests exercise authentication failures, expiry/replay, public
identity, signaling, body limits, TLS downgrade refusal and real WebSocket
binary PCM round trips. They are not a physical Android microphone or
Samsung power-management test. See the accompanying German test report.

PhoneCallSession tests exercise the real Engine with a local mock kiosk,
mock hardware audio and a fake dialer display: focus gating, duplicate
answer handling, continuous transmission, mute before/after connection,
rejection, stale callbacks, focus loss and microphone permission failure.
Android's actual binding, Samsung Phone UI, locked-screen audio and headset
routing require a device test. No emulator or phone was available here.

Android references:
- https://developer.android.com/reference/android/telecom/ConnectionService
- https://developer.android.com/reference/android/telecom/TelecomManager
- https://developer.android.com/develop/background-work/services/fgs/service-types

Dependencies: OkHttp / Okio / Kotlin standard library (Apache 2.0), NanoHTTPD
and NanoWSD (BSD 3-Clause), JetBrains annotations (Apache 2.0). JUnit and
org.json are test-only dependencies. See THIRD-PARTY-NOTICES.txt.
