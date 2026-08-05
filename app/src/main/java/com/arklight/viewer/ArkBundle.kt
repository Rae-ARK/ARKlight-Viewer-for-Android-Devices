package com.arklight.viewer

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Mirrors `arklight/packer/bundle.py`'s split point:
 *
 *     [ inlined entry page ][ sealed OR plain ZIP of the build dir ]
 *
 * The archive half starts immediately after the entry page's closing
 * `</html>\n` — exactly what ARKlight's HTML backend always emits, so
 * this works identically for sealed and plain bundles without needing
 * to search for the seal magic (plain bundles never have one).
 */
object ArkBundle {

    private val HTML_END_MARKER = "</html>\n".toByteArray(Charsets.UTF_8)

    class FormatError(message: String) : Exception(message)

    data class Split(val entryHtml: String, val archiveBytes: ByteArray)

    fun split(data: ByteArray): Split {
        val idx = indexOf(data, HTML_END_MARKER)
        if (idx == -1) {
            throw FormatError("couldn't find the closing </html> boundary marker")
        }
        val end = idx + HTML_END_MARKER.size
        val entryHtml = String(data, 0, end, Charsets.UTF_8)
        val archiveBytes = data.copyOfRange(end, data.size)
        return Split(entryHtml, archiveBytes)
    }

    /**
     * Writes [entryHtml] to `entryDir/index.html`, clearing whatever
     * was there before. Same origin-stability rationale as
     * [unsealAndExtract]: [entryDir] is a fixed path so the "quick
     * view" is served from the same stable origin as the full site,
     * via `WebViewAssetLoader`, instead of `loadDataWithBaseURL(null,
     * ...)`'s opaque origin.
     */
    fun writeEntryPage(entryHtml: String, entryDir: File) {
        entryDir.deleteRecursively()
        entryDir.mkdirs()
        File(entryDir, "index.html").writeText(entryHtml, Charsets.UTF_8)
    }

    sealed class ExtractResult {
        data class Success(val dir: File) : ExtractResult()
        object NeedsPassphrase : ExtractResult()
        data class Failed(val reason: String) : ExtractResult()
    }

    /**
     * Unseals (if needed) and unzips the archive half into [outDir],
     * which the caller points at a **fixed path** (e.g.
     * `cacheDir/ark_current/site`) rather than a per-bundle hash
     * directory. That's deliberate: `WebViewAssetLoader` binds a path
     * handler to a directory *path* once, at `Builder` time — keeping
     * that path constant across every opened bundle means the loader
     * (and therefore the served origin, `https://appassets.
     * androidplatform.net/site/`) never changes, which is what makes
     * origin-scoped storage (`localStorage`, IndexedDB, cookies) behave
     * consistently across different bundles instead of being silently
     * partitioned per bundle. See ARCHITECTURE.md, "Origin stability."
     * [outDir] is cleared before each extraction.
     */
    fun unsealAndExtract(archiveBytes: ByteArray, outDir: File, passphrase: String?): ExtractResult {
        if (archiveBytes.isEmpty()) {
            return ExtractResult.Failed("no archive half present (entry-page-only bundle)")
        }

        val zipBytes: ByteArray = if (ArkSeal.isSealed(archiveBytes)) {
            try {
                ArkSeal.unseal(archiveBytes, passphrase)
            } catch (e: ArkSeal.NeedsPassphrase) {
                return ExtractResult.NeedsPassphrase
            } catch (e: ArkSeal.SealError) {
                return ExtractResult.Failed(e.message ?: "seal error")
            }
        } else {
            archiveBytes
        }

        outDir.deleteRecursively()
        outDir.mkdirs()

        return try {
            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outFile = File(outDir, entry.name)
                    // Guard against a malicious "../" entry name (zip-slip).
                    if (!outFile.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) {
                        throw SecurityException("Unsafe zip entry path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ExtractResult.Success(outDir)
        } catch (e: Exception) {
            outDir.deleteRecursively()
            ExtractResult.Failed("bad zip once unsealed: ${e.message}")
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
