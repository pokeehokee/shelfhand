package app.shelfhand.core.catalog

/**
 * The background pass that fills in details. One request at a time with [pause] between, so a full
 * catalog takes hours and runs a slice per night. Three failures in a row end the pass: the network is
 * gone or the site is turning us away, and pressing on would only mark good books as failed.
 */
object Lookups {
    data class Result(val tried: Int, val matched: Int, val stoppedEarly: Boolean)

    fun run(db: CatalogDb, fetch: (String) -> String, pause: () -> Unit, max: Int, stop: () -> Boolean = { false }): Result {
        var tried = 0; var matched = 0; var failures = 0
        for (p in db.pendingLookups(max)) {
            if (stop()) return Result(tried, matched, true)
            if (tried > 0) pause()
            tried++
            val found = try {
                OpenLibrary.match(p.title, p.authors, OpenLibrary.parse(fetch(OpenLibrary.searchUrl(p.title, p.authors.firstOrNull()))))
                    .also { failures = 0 }
            } catch (e: Exception) {
                db.saveLookup(p.itemId, "error")
                if (++failures >= 3) return Result(tried, matched, true)
                continue
            }
            if (found != null) matched++
            db.saveLookup(p.itemId, if (found != null) "found" else "nomatch", found)
        }
        return Result(tried, matched, false)
    }
}
