# ARKlight Viewer

Open `.ark` bundles on Android.

ARKlight Viewer is the official Android viewer for **ARKlight** bundles, allowing complete ARKlight websites to open directly on your device without a web server or installation process.

Simply tap an `.ark` file and the viewer takes care of the rest.

---

## Features

- 📦 Open `.ark` bundles directly from Files, Downloads, or any Android file manager.
- ⚡ Instant rendering of the bundle's entry page.
- 🌐 Browse complete multi-page ARKlight websites.
- 🔒 Supports both sealed and passphrase-protected bundles.
- 📱 Built entirely with AndroidX WebView.
- 📶 Works completely offline.

---

## Installation

Build the project with Android Studio or install a released APK.

Minimum Android version: **Android 7.0 (API 24)**

---

## Usage

Build an ARKlight site:

```bash
arklight build site.py -o out
arklight pack out -o website.ark
```

Transfer the bundle to your Android device.

Open `website.ark` from your preferred file manager.

ARKlight Viewer automatically opens the bundle and renders the website.

---

## Building

Requirements:

- Android Studio Koala or newer
- Android SDK
- Minimum SDK 24

Clone the repository:

```bash
git clone https://github.com/Rae-ARK/ARKlight-Viewer-for-Android-Devices.git
```

Open the project in Android Studio and run it on a device or emulator.

---

## Project Structure

```
app/
 ├── MainActivity.kt
 ├── ArkBundle.kt
 ├── ArkSeal.kt
 └── AndroidManifest.xml
```

---

## Documentation

Additional documentation is available in:

- `ARCHITECTURE.md` — internal architecture and implementation
- `LICENSE` — licensing information

---

## About ARKlight

ARKlight is a Python-first compiler that generates dependency-free static websites from Python code.

Learn more at:

https://github.com/Rae-ARK/ARKlight

---

## License

Licensed under the Apache License 2.0.
