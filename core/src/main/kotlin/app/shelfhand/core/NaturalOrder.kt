package app.shelfhand.core

/**
 * Sorts "Chapter 2" before "Chapter 10". Plain string order puts 10 first, which plays a book
 * out of order -- the most common way a multi-file audiobook goes wrong.
 */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val n = p.trimStart('0').ifEmpty { "0" }; val m = q.trimStart('0').ifEmpty { "0" }
                if (n.length != m.length) n.length - m.length else n.compareTo(m)
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
