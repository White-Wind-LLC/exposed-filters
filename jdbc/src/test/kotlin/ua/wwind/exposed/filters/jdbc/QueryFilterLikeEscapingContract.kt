package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.jsonb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

/** Values are stored lowercase, so `name` may also be declared a normalized field. */
internal object LikeItems : Table("like_items") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

/** Only created where JSON paths can be evaluated. */
internal object LikeDocs : Table("like_docs") {
    val id: Column<Int> = integer("id")
    val payload: Column<String> = jsonb("payload", { it }, { it })
    override val primaryKey = PrimaryKey(id)
}

/**
 * `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` match the value literally: `%`, `_` and the escape character
 * in it are no wildcards ([#30](https://github.com/White-Wind-LLC/exposed-filters/issues/30)).
 */
abstract class QueryFilterLikeEscapingContract {
    protected abstract fun connect()

    /** H2 cannot evaluate JSON paths, so the JSON cases only run where it can. */
    protected open val supportsJsonPath: Boolean = true

    private val names = listOf("100% cotton", "cotton", "a_b", "axb", "c:\\temp", "c:temp")

    @BeforeEach
    fun setUp() {
        connect()
        transaction {
            SchemaUtils.drop(LikeItems, LikeDocs)
            SchemaUtils.create(LikeItems)
            names.forEachIndexed { index, value ->
                LikeItems.insert {
                    it[id] = index
                    it[name] = value
                }
            }
            if (supportsJsonPath) {
                SchemaUtils.create(LikeDocs)
                names.forEachIndexed { index, value ->
                    LikeDocs.insert {
                        it[id] = index
                        it[payload] = """{"note": "${value.replace("\\", "\\\\")}"}"""
                    }
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(LikeItems, LikeDocs) }
    }

    enum class CaseMode(val options: FilterOptions) {
        CASE_SENSITIVE(FilterOptions(caseSensitiveStrings = true)),
        NORMALIZED(FilterOptions(normalizedStringFields = setOf("name", "payload.note"))),
        LOWERED(FilterOptions()),
    }

    private class Case(val operator: FilterOperator, val value: String, val expected: Set<String>)

    private val cases = listOf(
        Case(FilterOperator.CONTAINS, "%", setOf("100% cotton")),
        Case(FilterOperator.CONTAINS, "_", setOf("a_b")),
        Case(FilterOperator.STARTS_WITH, "a_", setOf("a_b")),
        Case(FilterOperator.ENDS_WITH, "_b", setOf("a_b")),
        Case(FilterOperator.CONTAINS, "\\", setOf("c:\\temp")),
        Case(FilterOperator.STARTS_WITH, "c:\\", setOf("c:\\temp")),
        Case(FilterOperator.CONTAINS, "cotton", setOf("100% cotton", "cotton")),
        Case(FilterOperator.STARTS_WITH, "a", setOf("a_b", "axb")),
        Case(FilterOperator.ENDS_WITH, "temp", setOf("c:\\temp", "c:temp")),
    )

    private fun filter(field: String, operator: FilterOperator, value: String) =
        FilterRequest(FilterLeaf(listOf(FieldFilter(field, operator, listOf(value)))))

    private fun matchedNames(operator: FilterOperator, value: String, options: FilterOptions): Set<String> =
        transaction {
            LikeItems.selectAll()
                .applyFiltersOn(LikeItems, filter("name", operator, value), options)
                .map { it[LikeItems.name] }
                .toSet()
        }

    private fun matchedNotes(operator: FilterOperator, value: String, options: FilterOptions): Set<String> =
        transaction {
            LikeDocs.selectAll()
                .applyFiltersOn(LikeDocs, filter("payload.note", operator, value), options)
                .map { names[it[LikeDocs.id]] }
                .toSet()
        }

    @ParameterizedTest
    @EnumSource(CaseMode::class)
    fun `special characters in the value match literally on a column`(mode: CaseMode) {
        cases.forEach { case ->
            assertEquals(
                case.expected,
                matchedNames(case.operator, case.value, mode.options),
                "${case.operator} '${case.value}'",
            )
        }
    }

    @ParameterizedTest
    @EnumSource(CaseMode::class)
    fun `special characters in the value match literally on a JSON path`(mode: CaseMode) {
        assumeTrue(supportsJsonPath)
        cases.forEach { case ->
            assertEquals(
                case.expected,
                matchedNotes(case.operator, case.value, mode.options),
                "${case.operator} '${case.value}'",
            )
        }
    }

    @Test
    fun `an upper-case value with special characters still matches case-insensitively`() {
        assertEquals(setOf("100% cotton"), matchedNames(FilterOperator.CONTAINS, "0% COTTON", FilterOptions()))
        assertEquals(setOf("a_b"), matchedNames(FilterOperator.STARTS_WITH, "A_", FilterOptions()))
    }

    @Test
    fun `a filter built outside a transaction runs inside one`() {
        val query = LikeItems.selectAll()
            .applyFiltersOn(LikeItems, filter("name", FilterOperator.CONTAINS, "%"))
        val rows = transaction { query.map { it[LikeItems.name] } }
        assertEquals(listOf("100% cotton"), rows)
    }
}
