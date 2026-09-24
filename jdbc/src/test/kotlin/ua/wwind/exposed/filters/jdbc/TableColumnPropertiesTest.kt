package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.filterRequest
import java.lang.ref.WeakReference

object TestOddPropertiesTable : Table("test_odd_properties") {
    val id: Column<Int> = integer("id")
    val asExpression: Expression<Int> = integer("as_expression")
    val asAny: Any = integer("as_any")
    val note: String = "not a column"
    val broken: Column<Int> get() = error("getter fails")
}

/** A table class instantiated per call, standing in for tables built at runtime. */
class TestRuntimeInstanceTable(name: String) : Table(name) {
    val id: Column<Int> = integer("id")
    val label: Column<String> = varchar("label", 50)
}

class TableColumnPropertiesTest {
    @Test
    fun `columns held by properties of any column-compatible type are mapped by property name`() {
        val columnMap = TestOddPropertiesTable.propertyToColumnMap()

        assertSame(TestOddPropertiesTable.id, columnMap["id"])
        assertSame(TestOddPropertiesTable.asExpression, columnMap["asExpression"])
        assertSame(TestOddPropertiesTable.asAny, columnMap["asAny"])
    }

    @Test
    fun `non-column properties and failing getters are skipped`() {
        val columnMap = TestOddPropertiesTable.propertyToColumnMap()

        assertNull(columnMap["note"])
        assertNull(columnMap["broken"])
        assertEquals(setOf("id", "asExpression", "asAny"), columnMap.keys)
    }

    @Test
    fun `each instance of a table class resolves to its own columns`() {
        val first = TestRuntimeInstanceTable("first")
        val second = TestRuntimeInstanceTable("second")

        assertSame(first.label, first.propertyToColumnMap()["label"])
        assertSame(second.label, second.propertyToColumnMap()["label"])
    }

    @Test
    fun `filtering fresh table and alias instances retains none of them`() {
        Database.connect(
            url = "jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val references = (1..50).flatMap { filterFreshInstances(it) }

        repeat(20) {
            if (references.all { it.get() == null }) return
            System.gc()
            Thread.sleep(50)
        }
        val retained = references.count { it.get() != null }
        assertEquals(0, retained, "$retained of ${references.size} table instances were retained")
    }

    private fun filterFreshInstances(index: Int): List<WeakReference<Table>> {
        val table = TestRuntimeInstanceTable("runtime_$index")
        val alias = table.alias("a_$index")
        val filter = filterRequest { eq("label", "x") }
        transaction {
            table.selectAll().applyFiltersOn(table, filter).prepareSQL(this)
            alias.selectAll().applyFiltersOn(alias, filter).prepareSQL(this)
        }
        return listOf(WeakReference(table), WeakReference(alias))
    }
}
