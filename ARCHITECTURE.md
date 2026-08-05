# ARKlight Viewer — Architecture

## 0. What this document is

An implementation architecture for **ARKlight Viewer**, the Android
app that opens `.ark` bundles as a native file association. It also
documents where and how this project adopts design principles from
ARKlight's own (currently unimplemented) Android-backend plan,
`docs/DESIGN-NOTES.md` → **"v0.0438: Android backend —
androidx.webkit.WebViewAssetLoader packaging (PLANNING)"**, and
`docs/ARCHITECTURE.md`'s corresponding entry. That plan describes a
different feature — `arklight android`, a CLI backend that bakes *one
site* into *its own dedicated APK* — but the platform-level lessons in
it apply directly to this project too, and one of them exposed a real
bug that's fixed as part of this document (§3).

---

## 1. Problem framing (unchanged from the original design)

`.ark` is ARKlight's HTML/ZIP polyglot (`arklight/packer/bundle.py`,
`arklight/packer/seal.py`):

```
[ inlined, self-contained entry page ][ sealed OR plain ZIP of the build dir ]
```

Two independent gaps stop a `.ark` file from "just working" on
Android:

- **Server-side:** static hosts don't know `.ark`'s MIME type, so they
  serve `application/octet-stream`, and the browser downloads instead
  of rendering.
- **OS-side:** once downloaded, `.ark` collides with several unrelated
  registered formats (ARK: Survival Evolved saves, PowerDesk archives,
  etc.), so Android either does nothing or opens the wrong app.

This app is a **file-association viewer**, not a browser feature: it
registers `ACTION_VIEW` intent filters on the `.ark` extension
(`AndroidManifest.xml`), so it receives the raw bytes as a
`content://` URI directly — no HTTP layer, no MIME table, no
conflicting registration to fall into.

---

## 2. Component map

```
┌─────────────────────────────────────────────────────────────────┐
│ MainActivity                                                    │
│                                                                  │
│  onCreate / onNewIntent                                         │
│       │                                                          │
│       ▼                                                          │
│  ContentResolver.openInputStream(uri) ──► raw bytes              │
│       │                                                          │
│       ▼                                                          │
│  ArkBundle.split(bytes)                                          │
│       │                        │                                 │
│       ▼                        ▼                                 │
│  entryHtml (String)      archiveBytes (ByteArray)                 │
│       │                        │                                 │
│       ▼                        ▼                                 │
│  ArkBundle.writeEntryPage   ArkBundle.unsealAndExtract            │
│  (→ entryDir/index.html)    (→ ArkSeal.unseal → ZipInputStream    │
│       │                        → siteDir/*)                       │
│       │                        │                                 │
│       └──────────┬─────────────┘                                 │
│                   ▼                                               │
│         WebViewAssetLoader (single instance, two path handlers)   │
│         /entry/ → entryDir      /site/ → siteDir                  │
│                   │                                               │
│                   ▼                                               │
│         WebView, served at https://appassets.androidplatform.net  │
└─────────────────────────────────────────────────────────────────┘
```

**Files:**

| File | Responsibility |
|---|---|
| `AndroidManifest.xml` | `.ark` file-association intent filters (§1) |
| `ArkBundle.kt` | Splits raw bytes at the `</html>\n` marker; writes the entry page and extracted site to fixed-path directories |
| `ArkSeal.kt` | Kotlin port of `arklight/packer/seal.py`'s `unseal()` — HMAC-SHA256 counter-mode stream cipher + HMAC tag, PBKDF2 passphrase mode |
| `MainActivity.kt` | Intent handling, coroutine orchestration, the single `WebViewAssetLoader`, menu, passphrase dialog |

`arklight.packer` never touches the compiler internals (parser/ir/
backend) — it only reads already-built output. `ArkBundle`/`ArkSeal`
follow the same shape on the Android side: they only ever consume
bytes a `.ark` file already contains, and never depend on anything
from the Python package at runtime (the Kotlin port is a translation,
not a binding).

---

## 3. Origin stability — the principle adopted from `v0.0438`, and the bug it caught

### What ARKlight's own design doc says

`docs/DESIGN-NOTES.md`, "v0.0438: Android backend," section "Why this
needs to exist at all: the `file://` problem," makes a specific
platform claim: `file://`-served WebView pages get a **null or opaque
origin**, under which `localStorage`, `fetch()`, and other
origin-scoped APIs behave unreliably or are blocked outright. Its
fix — and the entire reason it proposes `androidx.webkit.
WebViewAssetLoader` over the naive "point a WebView at
`file:///android_asset/index.html`" approach — is that
`WebViewAssetLoader` serves local content under a real, stable `https`
origin instead. That matters concretely for ARKlight sites using
`State(persist=True)`, which relies on `localStorage` surviving a
reload.

### Why that applied here too, and what was wrong

`loadDataWithBaseURL(null, html, "text/html", "utf-8", null)` — which
this project's first draft used for the instant "entry page" quick
view — has the **same opaque-origin problem** as `file://`. It isn't
literally `file://`, but the platform consequence is the same one
ARKlight's own doc names: any `localStorage`/`fetch()` behavior a
packed site relies on would be unreliable specifically in quick-view
mode, while working fine once "browse full site" switched over to
`WebViewAssetLoader`. That inconsistency — same bundle, two different
origin behaviors depending on which button was tapped — is exactly the
class of bug the cited design doc exists to prevent.

### The fix applied in this codebase

Both rendering paths now go through **one** `WebViewAssetLoader`,
built once in `onCreate`, covering two fixed-path directories:

```kotlin
entryDir = File(cacheDir, "ark_current/entry")
siteDir  = File(cacheDir, "ark_current/site")

assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/entry/", WebViewAssetLoader.InternalStoragePathHandler(this, entryDir))
    .addPathHandler("/site/",  WebViewAssetLoader.InternalStoragePathHandler(this, siteDir))
    .build()
```

`ArkBundle.writeEntryPage()` writes the inlined entry page to
`entryDir/index.html` (a plain text write — still effectively
instant, no unzip/unseal needed) instead of calling
`loadDataWithBaseURL`. `ArkBundle.unsealAndExtract()` now takes an
explicit `outDir` and always extracts into the same fixed `siteDir`,
rather than a per-bundle SHA-256-hash subdirectory as an earlier draft
did.

**Why fixed paths, specifically:** `WebViewAssetLoader` binds a path
handler to a directory *path* once, at `Builder` construction time.
Keeping `entryDir`/`siteDir` constant across every opened bundle means
the loader — and therefore the origin served,
`https://appassets.androidplatform.net/{entry,site}/` — never changes.
Both views for the *same* bundle now share one origin, and (as a
direct consequence) so do quick-view and full-site across *different*
bundles, matching how a real deployed site would behave under one
fixed domain rather than getting silently partitioned per file opened.
Contents are overwritten per bundle; only the container path is
stable.

**Trade-off knowingly accepted:** the earlier hash-keyed cache meant
re-opening the same bundle skipped re-extraction. Fixed paths drop
that (every open re-extracts). This was chosen deliberately —
extraction is cheap (a few hundred KB to a few MB, in-memory ZIP,
milliseconds) and origin correctness is not something to trade away
for it. If re-extraction cost ever becomes real (very large bundles),
the right fix is a content hash used only as a *skip-if-unchanged*
check inside the fixed directory, not a second directory.

---

## 4. Data flow, step by step

1. **Intent arrives** (`onNewIntent` or cold-start `onCreate`) with a
   `content://` URI pointing at a `.ark` file.
2. **Read bytes** off the IO dispatcher via
   `contentResolver.openInputStream(uri)`.
3. **Split** (`ArkBundle.split`) at the first `</html>\n` — the exact
   boundary `arklight/packer/bundle.py`'s `_find_archive_start` uses,
   ported byte-for-byte (see §6 for how this was verified).
4. **Render immediately:** `writeEntryPage` + `webView.loadUrl(ENTRY_URL)`.
   This step never depends on the archive half at all — it works even
   for a hypothetically archive-less bundle.
5. **In the background** (`Dispatchers.IO`): `ArkBundle.
   unsealAndExtract` — detect sealed vs. plain via the `ARKSEAL2`/
   `ARKSEAL1` magic (`ArkSeal.isSealed`), unseal if needed
   (embedded-key mode needs no user input; passphrase mode returns
   `NeedsPassphrase` and the activity shows a dialog), then unzip into
   `siteDir` with a zip-slip guard on every entry path.
6. **Enable "Browse full site"** once extraction succeeds; the menu
   item routes to `SITE_URL` on the same asset loader.

---

## 5. Toolchain reality — also adopted from `v0.0438`

ARKlight's design doc is explicit that there is **no zero-build-step
version** of a `WebViewAssetLoader`-based app: it's compiled Jetpack
bytecode, not a script a WebView can load like a `<script src>` tag.
Building this project unavoidably needs:

1. `implementation "androidx.webkit:webkit:1.11.0"`, resolved from
   Google's Maven repository (declared in `app/build.gradle.kts`).
2. Android Gradle Plugin + Android SDK, to compile Kotlin down to dex.
3. A JDK, for Gradle/AGP/`kotlinc` to run at all.
4. Network access on first build (Gradle/AGP/AndroidX artifacts are
   fetched, not vendored).

Nothing about this project's *source* generation needs any of that —
every file in `app/src/` is plain text, and was written and verified
(§6) without an Android SDK present. Only turning those files into a
running APK needs the toolchain. This is the same "templating is
free, building never is" distinction `v0.0438`'s design draws for
`arklight android scaffold` vs. `arklight android build`.

**Practical implication for anyone building this:** open the project
in Android Studio (it provisions JDK/SDK/Gradle wrapper automatically)
rather than trying to assemble a command-line toolchain from scratch
first.

---

## 6. Verification strategy

Because no Android SDK was available in the environment this project
was built in, correctness for the two components that actually matter
(format parsing, crypto) was established independently of the Android
build:

1. Built three real `.ark` bundles with ARKlight's own CLI: default
   (embedded-key sealed), `--passphrase`, and `--plain`.
2. Wrote a plain-JDK Java transliteration of `ArkSeal.kt`/
   `ArkBundle.kt`'s logic (no Android dependencies — any JVM runs it).
3. Ran it against all three bundles and compared the recovered ZIP's
   SHA-256 against Python's own `unseal()`/`unpack()` output directly.
   **Result: byte-for-byte identical across all three sealing modes.**
4. Confirmed wrong-passphrase and missing-passphrase both fail at the
   right point (`Integrity check failed` from the HMAC comparison,
   `NeedsPassphrase` before that comparison even runs).

This gives confidence in the two hardest-to-eyeball parts of the
port — the counter-mode keystream construction and the PBKDF2/HMAC
field layout — without needing to trust a manual Python→Kotlin
transcription by inspection alone. The `MainActivity.kt` UI plumbing
around it (coroutines, menu state, dialog) is comparatively low-risk
and wasn't independently re-verified beyond code review; that's the
one part of this project that still wants a real device/emulator pass.

**Recommended follow-up test, once building on a real toolchain is
possible:** pack a demo ARKlight site whose `site.py` uses
`State(..., persist=True)`, open it in quick-view mode, set some
state, switch to "Browse full site," and confirm the persisted value
carries over — the concrete, observable behavior §3's fix is for.

---

## 7. Suggested implementation ladder

Mirroring `v0.0438`'s own staged CLI ladder (`scaffold` → `build` →
`--install` → `--release`) — each rung independently useful, each
adding one more toolchain dependency — this project's own remaining
work breaks down the same way:

1. **Open + build in Android Studio, run on an emulator.** No code
   changes needed; this is the first real toolchain-dependent step
   after everything in this repo so far.
2. **On-device pass of the origin-stability fix** (§6's recommended
   test). This is the one thing static verification couldn't cover.
3. **Real launcher icon** via Android Studio's Image Asset wizard —
   also backfills the legacy pre-API-26 PNGs the current adaptive-icon
   scaffold doesn't include.
4. **Polish `backToEntryPage`/menu state** — currently correct but
   minimal; e.g. a "recently opened bundles" list would reuse the
   `entryDir`/`siteDir` machinery with zero architecture change, since
   nothing about it is bundle-identity-specific.
5. **Signing + Play Store metadata**, if distribution is the goal.
   Out of scope for this document, same as `v0.0438` explicitly leaves
   signing/publishing to the user rather than inventing a
   credential-handling story.

---

## 8. Explicitly out of scope (mirroring `v0.0438`'s own scoping discipline)

- **iOS / WKWebView.** Different toolchain, different origin
  mechanism (`WKURLSchemeHandler`), deserves its own design rather
  than being folded in here.
- **A native-bridge / plugin layer.** This app's `WebView` serves
  bundle content and nothing else — no JS-to-native message channel
  beyond what `WebViewAssetLoader` itself provides.
- **Editing or re-packing `.ark` files.** This is a viewer; `arklight
  pack`/`unpack` remain the Python-side tools of record for producing
  bundles.
- **Multi-bundle history/library UI.** The current scope is "open one
  `.ark` file, view it well," not a bundle-management app — noted here
  as a candidate extension (§7.4), not a current requirement.
