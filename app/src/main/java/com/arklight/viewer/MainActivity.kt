package com.arklight.viewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import android.webkit.MimeTypeMap

/**
 * Origin strategy (see ARCHITECTURE.md, "Origin stability"):
 *
 * ARKlight's own v0.0438 Android-backend design doc
 * (arklight/docs/DESIGN-NOTES.md) identifies that `file://` — and,
 * equally, `loadDataWithBaseURL(null, ...)` — gives a WebView page a
 * null/opaque origin, under which `localStorage`/`fetch()` behave
 * unreliably. Its fix is `androidx.webkit.WebViewAssetLoader`, serving
 * local content under a real, stable `https://appassets.
 * androidplatform.net` origin instead.
 *
 * This activity applies that to *both* rendering paths it has — the
 * instant "entry page" quick view, and the background-extracted "full
 * site" — rather than only the full site, by writing the entry page
 * to a fixed-path file and serving it through the same loader. Both
 * therefore load under the identical stable origin, which is what
 * makes ARKlight's `State(persist=True)` → `localStorage` reliable
 * regardless of which of the two views is showing, and consistent
 * between the two views for the same bundle.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var entryDir: File
    private lateinit var siteDir: File

    private var siteReady = false
    private var showingFullSite = false

    // Which backing the currently-open bundle's full site is using --
    // RAM when there was headroom for it, disk otherwise. Whatever it
    // is, it gets flushed (RAM reference dropped / disk dir deleted)
    // as soon as we're done with it: right before extracting the next
    // bundle, and when the activity is destroyed. See ArkBundle.flush.
    private var currentBacking: ArkBundle.SiteBacking? = null

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { openArkFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Fixed paths, not per-bundle hash directories — see the class
        // doc above. Built once; contents get overwritten per bundle.
        entryDir = File(cacheDir, "ark_current/entry").apply { mkdirs() }
        siteDir = File(cacheDir, "ark_current/site").apply { mkdirs() }

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/entry/", WebViewAssetLoader.InternalStoragePathHandler(this, entryDir))
            .addPathHandler("/site/", SitePathHandler())
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        siteReady = false
        showingFullSite = false
        invalidateOptionsMenu()
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // App's actually done with the current site now -- release
        // whichever backing it was using (RAM reference dropped for
        // GC, or the disk fallback directory deleted outright).
        flushCurrentSite()
    }

    private fun flushCurrentSite() {
        ArkBundle.flush(currentBacking)
        currentBacking = null
        siteReady = false
    }

    /**
     * Serves the currently-open site's files from whichever backing
     * [currentBacking] holds. RAM-backed sites are served straight out
     * of the in-memory map; disk-backed ones delegate to a plain
     * [WebViewAssetLoader.InternalStoragePathHandler] pointed at the
     * fallback directory. Either way callers just see `/site/...`
     * resolve under the same stable origin -- see the class doc above.
     */
    private inner class SitePathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            return when (val backing = currentBacking) {
                is ArkBundle.SiteBacking.Ram -> {
                    val key = if (path.isEmpty()) "index.html" else path
                    val bytes = backing.files[key] ?: return null
                    WebResourceResponse(mimeTypeFor(key), null, ByteArrayInputStream(bytes))
                }
                is ArkBundle.SiteBacking.Disk ->
                    WebViewAssetLoader.InternalStoragePathHandler(this@MainActivity, backing.dir)
                        .handle(path)
                null -> null
            }
        }
    }

    private fun mimeTypeFor(path: String): String {
        val ext = path.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null) openArkFile(uri) else showWelcomeScreen()
    }

    private fun showWelcomeScreen() {
        ArkBundle.writeEntryPage(
            """
            <html><body style="font-family:sans-serif;padding:32px;color:#1F2933">
            <h2>ARKlight Viewer</h2>
            <p>Tap a <code>.ark</code> file anywhere on your device — Downloads,
            a file manager, a share sheet — and choose this app to open it.</p>
            <p>Or use the menu (&#8942;) to browse for one manually.</p>
            </body></html>
            """.trimIndent(),
            entryDir
        )
        webView.loadUrl(ENTRY_URL)
        title = getString(R.string.app_name)
    }

    private fun openArkFile(uri: Uri) {
        lifecycleScope.launch {
            progress.visibility = ProgressBar.VISIBLE

            // Done with whatever was open before -- release its RAM
            // or disk backing before we start pulling in the next one.
            flushCurrentSite()

            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            }
            if (bytes == null) {
                progress.visibility = ProgressBar.GONE
                toast("Couldn't read that file.")
                return@launch
            }

            val split = try {
                ArkBundle.split(bytes)
            } catch (e: ArkBundle.FormatError) {
                progress.visibility = ProgressBar.GONE
                toast("Not a valid .ark bundle: ${e.message}")
                return@launch
            }

            // Instant render, same stable origin as the full site.
            withContext(Dispatchers.IO) { ArkBundle.writeEntryPage(split.entryHtml, entryDir) }
            showingFullSite = false
            webView.loadUrl(ENTRY_URL)
            title = uri.lastPathSegment ?: getString(R.string.app_name)
            progress.visibility = ProgressBar.GONE

            tryExtractFullSite(split.archiveBytes, passphrase = null)
        }
    }

    private suspend fun tryExtractFullSite(archiveBytes: ByteArray, passphrase: String?) {
        val result = withContext(Dispatchers.IO) {
            ArkBundle.unsealAndExtract(archiveBytes, siteDir, passphrase, applicationContext)
        }
        when (result) {
            is ArkBundle.ExtractResult.Success -> {
                currentBacking = result.backing
                siteReady = true
                invalidateOptionsMenu()
                val where = when (result.backing) {
                    is ArkBundle.SiteBacking.Ram -> "in memory"
                    is ArkBundle.SiteBacking.Disk -> "on disk"
                }
                toast("Full site ready ($where) — see \u22EE menu \u2192 Browse full site.")
            }
            is ArkBundle.ExtractResult.NeedsPassphrase -> {
                if (passphrase == null) {
                    askForPassphrase { pass ->
                        lifecycleScope.launch { tryExtractFullSite(archiveBytes, pass) }
                    }
                } else {
                    toast("Wrong passphrase.")
                }
            }
            is ArkBundle.ExtractResult.Failed -> {
                // Not fatal: the entry page is already on screen.
            }
        }
    }

    private fun askForPassphrase(onSubmit: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Passphrase required")
            .setMessage("This bundle's archive half was sealed with a passphrase.")
            .setView(input)
            .setPositiveButton("Unseal") { _, _ -> onSubmit(input.text.toString()) }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun browseFullSite() {
        if (!siteReady) return
        showingFullSite = true
        invalidateOptionsMenu()
        webView.loadUrl(SITE_URL)
    }

    private fun backToEntryPage() {
        showingFullSite = false
        invalidateOptionsMenu()
        webView.loadUrl(ENTRY_URL)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_browse_full)?.isVisible = siteReady && !showingFullSite
        menu.findItem(R.id.action_entry_page)?.isVisible = showingFullSite
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            openDocument.launch(arrayOf("*/*"))
            true
        }
        R.id.action_browse_full -> {
            browseFullSite()
            true
        }
        R.id.action_entry_page -> {
            backToEntryPage()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val ENTRY_URL = "https://appassets.androidplatform.net/entry/index.html"
        private const val SITE_URL = "https://appassets.androidplatform.net/site/index.html"
    }
}
