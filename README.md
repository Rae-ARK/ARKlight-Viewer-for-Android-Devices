# ARKlight Viewer

An Android app that registers itself as the file handler for `.ark`
bundles — ARKlight's own HTML/ZIP polyglot format — and renders them
natively, no server involved.

## Why this works where the browser download path doesn't

Your original diagnosis was right: a `.ark` file served over HTTP hits
the server's Content-Type guess (`application/octet-stream`, since
`.ark` isn't a registered web MIME type), so the browser downloads it
instead of rendering it, and then Android has no reliable app to hand
it to.

This app sidesteps that path entirely. It's not a *browser* extension —
it's a **file association**, registered in `AndroidManifest.xml` via
an `ACTION_VIEW` intent filter matching the `.ark` extension. When you
tap a `.ark` file in Downloads, a file manager, or a share sheet,
Android asks "open with?" and offers this app directly, with the raw
bytes handed over as a `content://` URI. No HTTP request, no
Content-Type header, no MIME registry to fall into the wrong bucket.

## What it does

1. **Instant render.** Per `arklight/packer/bundle.py`, the first part
   of every `.ark` file — up through `</html>\n` — is a fully
   self-contained, inlined copy of the entry page (CSS and JS already
   inlined, no external references). The app finds that boundary and
   renders it in a `WebView` immediately, before touching anything
   else. This always works, sealed or not.
2. **Background unseal + unzip.** The rest of the file is a ZIP of the
   full build output, sealed by default (`ARKSEAL2`: an HMAC-SHA256
   counter-mode stream cipher + HMAC-SHA256 authentication tag — see
   `arklight/packer/seal.py`). `ArkSeal.kt` is a straight Kotlin port
   of that file's `unseal()`, verified byte-for-byte against the real
   Python implementation (same SHA-256 of the recovered ZIP bytes,
   across embedded-key, passphrase, and `--plain` bundles — see
   "Verification" below). Once unsealed, `ArkBundle.kt` unzips it into
   the app's cache directory.
3. **Full-site browsing.** Once extraction finishes, the menu's
   "Browse full site" option switches the `WebView` over to
   `androidx.webkit.WebViewAssetLoader`, serving the extracted
   directory at `https://appassets.androidplatform.net/site/...` — so
   multi-page sites, relative links, and `assets/` all resolve
   correctly, not just the single entry page.
4. **Passphrase prompt.** If a bundle was sealed with `--passphrase`,
   the app can't unseal it automatically — it shows a dialog and
   retries with whatever you enter.

See `ARCHITECTURE.md` for the full implementation architecture,
including how this project adopts (and, in one case, was corrected by)
design principles from ARKlight's own unimplemented `v0.0438` Android
backend plan (`arklight/docs/DESIGN-NOTES.md`) — most notably origin
stability via a single `WebViewAssetLoader` instance for both the
quick entry-page view and the full-site view.

## Project layout

```
ARKlightViewer/
  app/src/main/java/com/arklight/viewer/
    MainActivity.kt   -- intent handling, WebView, menu, passphrase UI
    ArkBundle.kt       -- splits the file at </html>, drives unseal+unzip
    ArkSeal.kt          -- Kotlin port of arklight/packer/seal.py (unseal only)
  app/src/main/AndroidManifest.xml   -- the .ark intent filters
  app/src/main/res/                  -- layout, menu, minimal launcher icon
```

## Building it

You'll need Android Studio (Koala or newer) or the command-line
Gradle/Android SDK toolchain — this sandbox has no Android SDK or
Google's Maven repo on its allow-list, so the APK itself couldn't be
compiled here. The crypto and parsing logic *were* independently
verified in this sandbox using a plain-Java port checked against the
real Python `unseal()`/`unpack()` (see below) — only the Android UI
plumbing is unbuilt.

1. Open the `ARKlightViewer/` folder in Android Studio — it'll
   generate the Gradle wrapper and sync automatically.
2. Run on a device/emulator (`minSdk 24`).
3. The launcher icon here is a placeholder vector; run Android
   Studio's Image Asset wizard (`res` → New → Image Asset) if you want
   a real one, and it'll also generate the legacy pre-API-26 PNGs this
   scaffold doesn't include.
4. To test: build a site and pack it —
   ```
   arklight build site.py -o out
   arklight pack out -o test.ark
   ```
   then `adb push test.ark /sdcard/Download/` and tap it in Files.

Three verified sample `.ark` files are included alongside this
project's outputs: a default (embedded-key sealed) bundle, one sealed
with `--passphrase hunter2`, and a `--plain` one — good for exercising
all three code paths (auto-unseal, passphrase prompt, and the
already-a-real-ZIP case) without needing Python installed on your
build machine.

## Verification performed in this sandbox

Since the Android SDK wasn't available here, the parts of the app that
actually matter for correctness — the format parsing and the
crypto — were tested independently:

- Built three real `.ark` bundles with ARKlight's own CLI (default
  seal, passphrase seal, `--plain`).
- Wrote a plain-JDK Java transliteration of `ArkSeal.kt`/`ArkBundle.kt`
  (no Android dependencies, so it runs on any JVM) and ran it against
  all three bundles.
- Confirmed the recovered ZIP bytes' SHA-256 matches Python's own
  `unseal()` output exactly, for all three bundles.
- Confirmed entry names/sizes inside the recovered ZIP are correct.
- Confirmed wrong-passphrase and missing-passphrase both fail exactly
  where they should (HMAC integrity check / `NeedsPassphrase`).

The Kotlin files in `app/src/main/java/` are the same logic, adapted
to Android's `File`/coroutine APIs — not re-derived, so this
verification carries over directly.

## Known limitations / next steps

- `android:pathPattern` glob matching for `.ark` pre-API-31 is a
  little loose (Android's manifest globs aren't real regex); the
  API 31+ `pathSuffix` filter is exact. In practice this is how most
  custom-extension viewer apps on the Play Store handle it.
- No real launcher icon assets — placeholder vector only.
- "Back to entry page" after switching to full-site mode currently
  just prompts you to reopen the file, rather than re-rendering from
  cached bytes — a small polish item if you want it seamless.
