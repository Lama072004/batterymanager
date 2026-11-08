package de.mlampert.batterymanager.testutil

import de.mlampert.batterymanager.monitor.db.SqlDb
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/** [SqlDb] auf xerial sqlite-jdbc für JVM-Unit-Tests (kein Gerät nötig). */
class JdbcSqlDb private constructor(val conn: Connection) : SqlDb, AutoCloseable {

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    override fun update(sql: String, args: List<Any?>): Int =
        conn.prepareStatement(sql).use { st -> bind(st, args); st.executeUpdate() }

    override fun queryLong(sql: String, args: List<Any?>): Long? =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1)?.let { (it as Number).toLong() } else null }
        }

    override fun pragma(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun <T> transaction(block: () -> T): T {
        val auto = conn.autoCommit
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = auto
        }
    }

    /** Alle Zeilen als Liste von Spalten-Maps. */
    fun rows(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val out = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    out += (1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) }
                }
                out
            }
        }

    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    }

    companion object {
        fun inMemory(): JdbcSqlDb = JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
        fun file(f: File): JdbcSqlDb = JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}"))
    }
}
