# Phone WiFi Keyboard + Touchpad for Windows

Use your phone as a wireless keyboard, touchpad and air mouse for a Windows PC, over WiFi.

```
windows/   PC server (Python, zero dependencies)  ← always needed
android/   Native Android app (Kotlin)            ← optional, the browser works too
```

## 1. PC side (required)
1. Install Python 3.8+ (tick "Add to PATH").
2. Double-click `windows/start.bat`.
3. Allow it on **Private** networks when the Windows firewall asks.

Options: `--admin` (type into admin windows), `--port 9000`, `--new-token` (kick out all paired phones).
`windows/build_exe.bat` turns it into a standalone `PhoneKeyboard.exe`.

## 2a. Any phone: browser
Open the link shown in the PC window. Done.

## 2b. Android app
**Get the APK, either way:**
- **No setup (GitHub):** push this folder to a GitHub repo → *Actions* tab → "Build Android APK" runs automatically → download `WiFiKeyboard-apk` from the run.
- **Android Studio:** File → Open → pick the `android` folder → Run ▶ (or Build → Build APK).

Install the APK on the phone (allow "install unknown apps"), open it:
1. Your PC shows up automatically, tap it.
2. On the PC window type `y` + Enter to approve the phone (one time only).
3. Next time it's just one tap: "Reconnect".

### What the app adds over the browser
- Auto-finds PCs on the WiFi, one-time pairing, one-tap reconnect
- **Air mouse**: move the cursor by turning/tilting the phone (gyroscope)
- **Phone volume buttons** → PC volume or scroll wheel (switch in Media tab)
- Screen stays on, haptic feedback, remembers speed/tab settings
- Hardware keyboard plugged into the phone gets forwarded too

## Features (both)
Live typing in any language/emoji/swipe/autocorrect · Compose-then-send mode · Ctrl/Shift/Alt/Win ·
arrows with hold-to-repeat · F1–F12 · shortcuts (copy, paste, Alt+Tab, Win+D, snip, lock, Task Manager) ·
media keys · touchpad (tap, 2-finger scroll & right-click, double-tap-drag, drag lock) · game-friendly scan codes ·
secret token so nobody else on the WiFi can control your PC.

## Troubleshooting
- **PC not found / can't connect:** same WiFi; network set to *Private*; firewall allows Python (TCP 8765 + UDP 8766). Guest WiFi often blocks device-to-device traffic, use a phone hotspot instead.
- **Doesn't type in some window:** it's an admin window, restart with `--admin`.
- **"Rejected" in the app:** you used `--new-token`; go back and pair again.
- Windows blocks simulated input on the lock screen and Ctrl+Alt+Del, by design.
