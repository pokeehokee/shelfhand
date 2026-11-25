package app.shelfhand.catalog

import app.shelfhand.core.catalog.Feeds
import app.shelfhand.core.catalog.HttpError
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain GETs for catalogs: web addresses only, redirects followed by hand, a size cap, four tries. A site that
 * says "too many requests" or "busy" gets the wait it asks for (up to a minute) before the next try.
 */
object Http {
    const val UA = "Shelfhand/0.1 (personal audiobook app)"
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val TRIES = 4

    /** [allowErrorBody] returns the body of a 404, which LibriVox uses to say "no more pages". */
    fun get(url: String, allowErrorBody: Boolean = false, accept: String = "*/*"): String {
        var last: IOException? = null
        repeat(TRIES) { attempt ->
            var wait = 2_000L * (attempt + 1)
            try {
                return once(url, allowErrorBody, accept)
            } catch (e: HttpError) {
                if (e.code in 400..499 && e.code != 429 && e.code != 408) throw e // the site said no; asking again will not change that
                if (e.code == 429 || e.code == 503) wait = (e.retryAfterSeconds?.times(1000) ?: (10_000L shl attempt)).coerceIn(2_000, 60_000)
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < TRIES - 1) Thread.sleep(wait)
        }
        throw last ?: IOException("No answer")
    }

    private fun once(url: String, allowErrorBody: Boolean, accept: String): String {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", accept)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("Redirect with no address")
                    current = Feeds.resolve(current, next) ?: throw IOException("Redirected somewhere that isn't a web address")
                    return@repeat
                }
                val ok = code in 200..299
                if (!ok && !(allowErrorBody && code == 404)) throw HttpError(code, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
                val stream = (if (ok) c.inputStream else c.errorStream) ?: return ""
                val out = ByteArrayOutputStream()
                stream.use { s ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = s.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("The page is larger than 20 MB")
                    }
                }
                return out.toString(Charsets.UTF_8.name())
            } finally { c.disconnect() }
        }
        throw IOException("Too many redirects")
    }
}
