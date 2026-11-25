package app.shelfhand.core.catalog

/**
 * The few things the catalog needs from SQLite, so the same SQL runs on the phone (bundled SQLite)
 * and in the JVM tests (sqlite-jdbc). Arguments are bound, never spliced into the text.
 */
interface Sql {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), row: (Row) -> T): List<T>
    fun <T> transaction(block: () -> T): T

    interface Row {
        fun string(i: Int): String?
        fun long(i: Int): Long?
        fun double(i: Int): Double?
    }
}
