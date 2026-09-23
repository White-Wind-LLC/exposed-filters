@file:OptIn(ExperimentalUuidApi::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private object ProjProductsTable : Table("proj_products") {
    val id: Column<Uuid> = uuid("id")
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(id)
}

private object ProjProductTranslationsTable : Table("proj_product_translations") {
    val productId: Column<Uuid> = reference("product_id", ProjProductsTable.id)
    val language: Column<String> = varchar("language", 8)
    val name: Column<String?> = varchar("name", 100).nullable()
    override val primaryKey = PrimaryKey(productId, language)
}

private object ProjBalancesTable : Table("proj_balances") {
    val id: Column<Int> = integer("id").autoIncrement()
    val sku: Column<String> = varchar("sku", 50)
    val productId: Column<Uuid> = reference("product_id", ProjProductsTable.id)
    override val primaryKey = PrimaryKey(id)
}

private val PRODUCT_TRANSLATED: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
private val PRODUCT_UNTRANSLATED: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000b2")

class QueryFilterNestedProjectionTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:test_proj_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                ProjProductsTable,
                ProjProductTranslationsTable,
                ProjBalancesTable,
                H2JsonUsersTable,
            )
            insertProduct(PRODUCT_TRANSLATED, "Alpha")
            insertProduct(PRODUCT_UNTRANSLATED, "Beta")
            ProjProductTranslationsTable.insert {
                it[productId] = PRODUCT_TRANSLATED
                it[language] = "uk"
                it[name] = "Альфа"
            }
            insertBalance("BAL-A", PRODUCT_TRANSLATED)
            insertBalance("BAL-B", PRODUCT_UNTRANSLATED)
        }
    }

    private fun insertProduct(
        productId: Uuid,
        productName: String,
    ) {
        ProjProductsTable.insert {
            it[id] = productId
            it[name] = productName
        }
    }

    private fun insertBalance(
        code: String,
        product: Uuid,
    ) {
        ProjBalancesTable.insert {
            it[sku] = code
            it[productId] = product
        }
    }

    @AfterEach
    fun cleanUp() {
        transaction {
            SchemaUtils.drop(
                H2JsonUsersTable,
                ProjBalancesTable,
                ProjProductTranslationsTable,
                ProjProductsTable,
            )
        }
    }

    private fun leaf(
        field: String,
        operator: FilterOperator,
        value: String,
    ) = FilterRequest(FilterLeaf(listOf(FieldFilter(field, operator, listOf(value)))))

    private fun localizedIn(language: String) = FilterOptions(
        nestedFieldResolver = { table, nestedField ->
            if (table === ProjProductsTable && nestedField == "name") {
                NestedFieldProjection(
                    source = ProjProductsTable.leftJoin(
                        otherTable = ProjProductTranslationsTable,
                        additionalConstraint = {
                            (ProjProductTranslationsTable.productId eq ProjProductsTable.id) and
                                (ProjProductTranslationsTable.language eq language)
                        },
                    ),
                    expression = coalesce(ProjProductTranslationsTable.name, ProjProductsTable.name),
                )
            } else {
                null
            }
        },
    )

    private fun skusMatching(
        filter: FilterRequest,
        options: FilterOptions = DefaultFilterOptions,
    ): List<String> = ProjBalancesTable
        .selectAll()
        .applyFiltersOn(ProjBalancesTable, filter, options = options)
        .map { it[ProjBalancesTable.sku] }
        .sorted()

    @Test
    fun `without a resolver a nested path matches the base value only`() {
        transaction {
            assertEquals(listOf("BAL-A"), skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha")))
            assertEquals(emptyList<String>(), skusMatching(leaf("productId.name", FilterOperator.EQ, "Альфа")))
        }
    }

    @Test
    fun `a nested field resolver matches the translated value`() {
        transaction {
            assertEquals(
                listOf("BAL-A"),
                skusMatching(leaf("productId.name", FilterOperator.EQ, "Альфа"), localizedIn("uk")),
            )
        }
    }

    @Test
    fun `a nested field resolver falls back to the base value when a translation is missing`() {
        transaction {
            assertEquals(
                listOf("BAL-B"),
                skusMatching(leaf("productId.name", FilterOperator.EQ, "Beta"), localizedIn("uk")),
            )
        }
    }

    @Test
    fun `a nested field resolver stops the base value from matching a translated row`() {
        transaction {
            assertEquals(
                emptyList<String>(),
                skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha"), localizedIn("uk")),
            )
        }
    }

    @Test
    fun `a nested field resolver applies to like operators`() {
        transaction {
            assertEquals(
                listOf("BAL-A"),
                skusMatching(leaf("productId.name", FilterOperator.CONTAINS, "льф"), localizedIn("uk")),
            )
        }
    }

    @Test
    fun `a nested field resolver is consulted for a declared reference`() {
        var consulted = false
        val options = FilterOptions(
            nestedFieldResolver = { _, _ ->
                consulted = true
                null
            },
        )
        transaction {
            skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha"), options)
        }
        assertTrue(consulted, "the nested resolver must be consulted even when Exposed resolves the reference")
    }

    @Test
    fun `a nested field resolver returning null keeps the base column behaviour`() {
        val options = FilterOptions(nestedFieldResolver = { _, _ -> null })
        transaction {
            assertEquals(
                listOf("BAL-A"),
                skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha"), options),
            )
        }
    }

    private fun projectedBy(projection: NestedFieldProjection) = FilterOptions(
        nestedFieldResolver = { table, nestedField ->
            if (table === ProjProductsTable && nestedField == "name") projection else null
        },
    )

    @Test
    fun `an id expression lets an alias of the target table stand in for it`() {
        val products = ProjProductsTable.alias("p")
        val options = projectedBy(
            NestedFieldProjection(
                source = products,
                expression = products[ProjProductsTable.name],
                idExpression = products[ProjProductsTable.id],
            ),
        )
        transaction {
            assertEquals(listOf("BAL-A"), skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha"), options))
        }
    }

    @Test
    fun `an id expression lets a subquery replace the target table entirely`() {
        val displayName = coalesce(ProjProductTranslationsTable.name, ProjProductsTable.name).alias("display_name")
        val names = ProjProductsTable
            .leftJoin(
                otherTable = ProjProductTranslationsTable,
                additionalConstraint = {
                    (ProjProductTranslationsTable.productId eq ProjProductsTable.id) and
                        (ProjProductTranslationsTable.language eq "uk")
                },
            )
            .select(ProjProductsTable.id, displayName)
            .alias("pn")
        val options = projectedBy(
            NestedFieldProjection(
                source = names,
                expression = names[displayName],
                idExpression = names[ProjProductsTable.id],
            ),
        )
        transaction {
            assertEquals(listOf("BAL-A"), skusMatching(leaf("productId.name", FilterOperator.EQ, "Альфа"), options))
            assertEquals(listOf("BAL-B"), skusMatching(leaf("productId.name", FilterOperator.EQ, "Beta"), options))
        }
    }

    @Test
    fun `a source without the referenced id and no id expression fails before reaching the database`() {
        val products = ProjProductsTable.alias("p")
        val options = projectedBy(NestedFieldProjection(products, products[ProjProductsTable.name]))
        transaction {
            val error = assertThrows(IllegalArgumentException::class.java) {
                skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha"), options)
            }
            assertTrue(error.message!!.contains("idExpression"), error.message)
        }
    }

    private fun renderedSql(
        filter: FilterRequest,
        options: FilterOptions = DefaultFilterOptions,
    ): String = ProjBalancesTable
        .selectAll()
        .applyFiltersOn(ProjBalancesTable, filter, options = options)
        .prepareSQL(QueryBuilder(false))

    @Test
    fun `a nested path without a resolver renders neither a join nor a coalesce`() {
        transaction {
            val sql = renderedSql(leaf("productId.name", FilterOperator.EQ, "Alpha"))

            assertTrue(sql.contains("EXISTS", ignoreCase = true), sql)
            assertFalse(sql.contains("JOIN", ignoreCase = true), sql)
            assertFalse(sql.contains("COALESCE", ignoreCase = true), sql)
            assertFalse(sql.contains(ProjProductTranslationsTable.tableName, ignoreCase = true), sql)
        }
    }

    @Test
    fun `a nested field resolver renders the join and the coalesce it supplied`() {
        transaction {
            val sql = renderedSql(leaf("productId.name", FilterOperator.EQ, "Альфа"), localizedIn("uk"))

            assertTrue(sql.contains("JOIN", ignoreCase = true), sql)
            assertTrue(sql.contains("COALESCE", ignoreCase = true), sql)
            assertTrue(sql.contains(ProjProductTranslationsTable.tableName, ignoreCase = true), sql)
        }
    }

    @Test
    fun `a json path never reaches the nested field resolver`() {
        var consulted = false
        val options = FilterOptions(
            nestedFieldResolver = { _, _ ->
                consulted = true
                null
            },
        )
        transaction {
            // H2 has no JSON_EXTRACT; the path still routes to the JSON branch, which is the point.
            assertThrows(UnsupportedByDialectException::class.java) {
                H2JsonUsersTable
                    .selectAll()
                    .applyFiltersOn(
                        H2JsonUsersTable,
                        FilterRequest(
                            FilterLeaf(listOf(FieldFilter("payload.profile.name", FilterOperator.EQ, listOf("Alice")))),
                        ),
                        options = options,
                    ).toList()
            }
        }
        assertFalse(consulted, "a JSON path is not a reference and must not consult the resolver")
    }

    @Test
    fun `the reference resolver is still skipped for a declared reference`() {
        var consulted = false
        val options = FilterOptions(
            referenceResolver = { _ ->
                consulted = true
                null
            },
        )
        transaction {
            skusMatching(leaf("productId.name", FilterOperator.EQ, "Alpha"), options)
        }
        assertFalse(consulted, "a declared reference must keep resolving without the reference resolver")
    }
}
