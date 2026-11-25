package app.shelfhand.catalog

import app.shelfhand.core.catalog.Feeds
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain GETs for catalogs: web addresses only, redirects followed by hand, a size cap, three tries. */
object Http {
    const val UA = "Shelfhand/0.1 (personal audiobook app)"
    private const val MAX_BYTES = 20 * 1024 * 1024

    class Status(val code: Int) : IOException("The site answered $code")

    /** [allowErrorBody] returns the body of a 404, which LibriVox uses to say "no more pages". */
    fun get(url: String, allowErrorBody: Boolean = false, accept: String = "*/*"): String {
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                return once(url, allowErrorBody, accept)
            } catch (e: Status) {
                if (e.code in 400..499 && e.code != 429) throw e // the site said no; asking again will not change that
                last = e
            } catch (e: IOException) {
                last = e
            }
            Thread.sleep(2_000L * (attempt + 1))
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
                if (!ok && !(allowErrorBody && code == 404)) throw Status(code)
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
