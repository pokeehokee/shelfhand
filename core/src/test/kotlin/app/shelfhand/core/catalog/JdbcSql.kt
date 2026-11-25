package app.shelfhand.core.catalog

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/** The catalog's SQL over sqlite-jdbc, which is built with FTS5, for tests on the JVM. */
class JdbcSql(private val c: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")) : Sql {
    private fun bind(sql: String, args: List<Any?>) = c.prepareStatement(sql).also { st ->
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, Types.NULL)
                is Int -> st.setInt(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Double -> st.setDouble(i + 1, a)
                else -> st.setString(i + 1, a.toString())
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) { bind(sql, args).use { it.execute() } }

    override fun <T> query(sql: String, args: List<Any?>, row: (Sql.Row) -> T): List<T> = bind(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val r = object : Sql.Row {
                override fun string(i: Int) = rs.getString(i + 1)
                override fun long(i: Int) = rs.getLong(i + 1).takeUnless { rs.wasNull() }
                override fun double(i: Int) = rs.getDouble(i + 1).takeUnless { rs.wasNull() }
            }
            buildList { while (rs.next()) add(row(r)) }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (!c.autoCommit) return block()
        c.autoCommit = false
        try { return block().also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true }
    }
}
