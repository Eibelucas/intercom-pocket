# Intercom Satellite 2.0.0 – Setup

1. Install a signed APK on your phone. To update an existing installation, sign it with the original key; settings are kept. The build from this repository is unsigned and must be signed before installation.

2. On each kiosk, enable **Remote Administration**, **Find other kiosks** and **Intercom** in Kiosk Satellite, and copy the intercom key. In the app, open **Settings** (top bar) and enter exactly the same key.

3. The phone and the kiosks need a direct connection on the same Wi-Fi. Guest networks with client isolation block calls. If a kiosk is not found automatically, choose **Add kiosk** and enter its address, for example `192.168.1.50` or `kitchen.local`. The default port is 2324; add a different port after a colon. For an encrypted connection, start with `https://`.

4. Choose **Call** on a kiosk card. Hold the talk button to speak (push-to-talk), or turn on **Hands-free**. Kiosk calls to your phone appear in the regular phone app once you have enabled the Intercom call account (**Settings → Phone account**).

5. **Start announcement** offers all available kiosks, a one-time selection, or a saved room group. Create groups of 1–32 previously connected kiosks under **Room groups** in the top bar of the home screen. The announcement shows each room's status: connected, unreachable or declined. Unreachable group members are skipped, and an empty selection never calls everyone. The microphone starts muted. A declined or failed kiosk does not end the other connections.

6. For a home-screen widget, first connect a kiosk in the app. Then add the **Intercom Satellite** widget and pick its target. A tap opens the app, rechecks the kiosk and calls it.

7. Optional settings: reception after restart, quiet hours with weekdays and exceptions, light/dark/system theme. The app follows the wallpaper-based Material You colors of your device. Open the call history from the top bar of the home screen. Edit a kiosk's name, symbol and favorite status through the options button on its card.

8. In **Settings**, switch **Receive announcements** off and save to refuse announcements. Individual calls can still ring; quiet hours and **Do not disturb** apply in addition.

9. **Do not disturb for …** on the home screen offers 30 minutes, two hours, until 07:00 tomorrow, or no end. The end time survives a restart. Turning it off ends the timer immediately. When it expires, your scheduled quiet hours stay active; the status notification updates within five seconds while reception is running.

## Reception while the phone is locked

Open **Settings → Background reception** and allow unrestricted battery use if needed. Some manufacturers still limit background activity. Active reception uses more battery. **Turn off availability** stops it; the app then does not start automatically after a restart.

During an incoming call, the app is shown above the lock screen. At all other times it stays behind the lock screen.

## Security

TLS must be set the same way on the phone and the kiosk. With TLS, confirm each kiosk's certificate by its SHA-256 fingerprint. The app never falls back to an unencrypted connection.

The app is an unofficial, standalone companion for Kiosk Satellite by Xavier Larrea. Calls are not recorded and no cloud is used. Minimum supported version: Android 12.
