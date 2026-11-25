package app.shelfhand.catalog

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.shelfhand.core.catalog.Sql

/**
 * The catalog's SQL on the phone, on SQLite bundled with the app rather than the system's, because the
 * system build is not guaranteed to include FTS5. One connection, used by one thread at a time.
 */
class AndroidSql(path: String) : Sql {
    private val c: SQLiteConnection = BundledSQLiteDriver().open(path)
    private val lock = Any()
    private var depth = 0

    init {
        c.prepare("PRAGMA journal_mode=WAL").use { it.step() }
        c.execSQL("PRAGMA synchronous=NORMAL")
    }

    private fun SQLiteStatement.bind(args: List<Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            null -> bindNull(i + 1)
            is Int -> bindLong(i + 1, a.toLong())
            is Long -> bindLong(i + 1, a)
            is Double -> bindDouble(i + 1, a)
            is Float -> bindDouble(i + 1, a.toDouble())
            else -> bindText(i + 1, a.toString())
        }
    }

    override fun exec(sql: String, args: List<Any?>) = synchronized(lock) {
        c.prepare(sql).use { st -> st.bind(args); while (st.step()) Unit }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (Sql.Row) -> T): List<T> = synchronized(lock) {
        c.prepare(sql).use { st ->
            st.bind(args)
            val r = object : Sql.Row {
                override fun string(i: Int) = if (st.isNull(i)) null else st.getText(i)
                override fun long(i: Int) = if (st.isNull(i)) null else st.getLong(i)
                override fun double(i: Int) = if (st.isNull(i)) null else st.getDouble(i)
            }
            buildList { while (st.step()) add(row(r)) }
        }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (depth > 0) return block()
        c.execSQL("BEGIN IMMEDIATE")
        depth++
        try {
            block().also { c.execSQL("COMMIT") }
        } catch (e: Throwable) {
            runCatching { c.execSQL("ROLLBACK") }
            throw e
        } finally { depth-- }
    }
}
