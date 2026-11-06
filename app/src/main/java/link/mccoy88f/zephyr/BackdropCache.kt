package link.mccoy88f.zephyr

import java.io.File
import java.io.InputStream

/** The Spotlight backdrops' folder in cacheDir. */
internal const val BACKDROP_DIR = "spotlight"

/** What each cached backdrop shows (file, title, year and genre), for the TMDB screensaver; in cacheDir. */
internal const val BACKDROP_TITLES = "spotlight-titles.tsv"

/** Films' and series' art (posters, backdrops, logos) in cacheDir: the home screen's and the player's, one folder. */
internal const val ART_DIR = "art_images"
internal const val ART_FILES = 500 // the most used kept, the rest let go

/** Channel logos' folder, in filesDir (see Images): the player reads them there too. */
internal const val LOGO_DIR = "channel_logo_images"

/** The screensaver choice that shows the Spotlight backdrops. */
internal const val SS_TMDB = "tmdb:trending"

/**
 * Spotlight backdrops kept on disk, so cycling through the trending titles downloads each one once instead of
 * every few seconds (and fitWallpaper's three passes over an image read the file, not the network three times).
 * The newest [maxFiles] stay; the rest go, oldest use first. Files are written under a temporary name and renamed,
 * so a download cut short never leaves a broken image behind. Safe to use from several threads.
 */
internal class BackdropCache(private val dir: File, private val maxFiles: Int = 48, private val name: (String) -> String = ::nameFor) {
    init {
        dir.mkdirs()
        // Leftovers of downloads cut short by a crash or a killed process.
        dir.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
    }

    /** The image file for [url], downloaded with [download] first if it isn't here yet. Network: off the UI thread. */
    fun get(url: String, download: (String) -> InputStream): File {
        val file = fileFor(url)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis()) // recently used: kept longest
            return file
        }
        val part = File(dir, "${file.name}.${Thread.currentThread().id}$PART")
        // The folder made again when it's gone: Android's Clear cache empties it while the app runs on (the home
        // screen is never closed), and every image after would fail to save until the app started again.
        dir.mkdirs()
        // Where it's saved, opened first: a file that can't be written is an IOException, never the
        // FileNotFoundException a 404 is (see art: that one isn't asked for again this session).
        val out = try {
            part.outputStream()
        } catch (e: java.io.FileNotFoundException) {
            throw java.io.IOException("couldn't write ${part.path}", e)
        }
        try {
            out.use { o -> download(url).use { input -> input.copyCapped(o, Http.MAX_IMAGE) } }
            if (!part.renameTo(file) && file.length() == 0L) throw java.io.IOException("couldn't save $url")
        } finally {
            part.delete()
        }
        trim()
        return file
    }

    /** True when [url] is already on disk. */
    fun has(url: String) = fileFor(url).length() > 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(url: String) = File(dir, name(url))

    @Synchronized
    private fun trim() {
        val files = dir.listFiles { f -> !f.name.endsWith(PART) } ?: return
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { it.delete() }
    }

    companion object {
        private const val PART = ".part"

        /** A channel logo's file name: by its whole address, many are called just "logo.png". */
        fun logoName(url: String) = "l${url.hashCode().toUInt()}"

        /** An art image's file name: by a hash of its whole address ("…/poster.jpg", "…/img" are many titles'). */
        fun artName(url: String) = "a" + java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

        /** The file name an image is kept under. TMDB names images by a unique hash ("/abc123.jpg"). */
        fun nameFor(url: String): String {
            val name = url.substringAfterLast('/').substringBefore('?').filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            return name.takeIf { it.length > 4 } ?: "u${url.hashCode().toUInt()}"
        }
    }
}
