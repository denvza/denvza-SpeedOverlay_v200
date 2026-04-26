# ⚡ SpeedOverlay

A lightweight Android app that displays your current driving speed as a **draggable floating overlay**, always visible on top of other apps.

---

## Features
- 🔵 **Circular floating overlay** — always on top of every app
- 📍 **Real GPS speed** via Google's Fused Location Provider (1s updates)
- 🎨 **Color-coded speed indicator:**
  - Dark teal = normal (0–79 km/h)
  - Orange = fast (80–119 km/h)
  - Red = very fast (120+ km/h)
- ✋ **Drag anywhere** on screen
- 🔔 Persistent foreground notification (required by Android)

---

## How to Build

### Requirements
- Android Studio Hedgehog or newer
- Android SDK 26+
- A real Android device (GPS doesn't work reliably in emulator)
- Google Play Services on device

### Steps

1. **Open in Android Studio:**
   - File → Open → select the `SpeedOverlay` folder

2. **Sync Gradle:**
   - Android Studio will prompt you to sync. Click "Sync Now".

3. **Run on device:**
   - Connect your Android phone via USB
   - Enable Developer Mode + USB Debugging on the phone
   - Click ▶ Run

4. **First launch:**
   - Tap "Start Overlay"
   - Grant **"Display over other apps"** permission (opens Settings)
   - Grant **Location** permission
   - The overlay appears — drag it wherever you want!

---

## Permissions Explained

| Permission | Why needed |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the overlay on top of other apps |
| `ACCESS_FINE_LOCATION` | Read GPS speed (most accurate) |
| `FOREGROUND_SERVICE` | Keep the service alive in background |
| `FOREGROUND_SERVICE_LOCATION` | Android 14+ requirement for location in foreground service |

---

## Project Structure

```
SpeedOverlay/
├── app/src/main/
│   ├── AndroidManifest.xml          # Permissions + service declaration
│   ├── java/com/speedoverlay/app/
│   │   ├── MainActivity.java        # Permission flow + start/stop UI
│   │   └── OverlayService.java      # Core: GPS + floating window
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml    # Main screen UI
│       │   └── overlay_speed.xml    # The floating widget layout
│       └── drawable/
│           ├── overlay_bg_normal.xml
│           ├── overlay_bg_fast.xml
│           └── overlay_bg_veryfast.xml
```

---

## Notes
- The overlay persists when you switch apps — that's the whole point!
- Stop it from the notification or by reopening the app.
- GPS speed is accurate when moving; at standstill it will show 0.
- Works with any navigation or music app in the foreground.
