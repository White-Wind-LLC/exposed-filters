@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package ua.wwind.exposed.filters.benchmark

import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import ua.wwind.exposed.filters.jdbc.applyFilters
import ua.wwind.exposed.filters.jdbc.applyFiltersOn
import ua.wwind.exposed.filters.jdbc.propertyToColumnMap
import ua.wwind.exposed.filters.rest.parseFilterRequestOrNull
import java.util.UUID

/** SQL rendering needs a transaction; each invocation opens one and repeats the operation this many times. */
const val BUILD_OPS = 500
const val EXEC_OPS = 20

/** In-memory H2 database seeded with 10 warehouses and 1000 products. */
@State(Scope.Benchmark)
open class DbState {
    @Param
    lateinit var scenario: Scenario

    lateinit var db: Database
    lateinit var cachedMap: Map<String, ExpressionWithColumnType<*>>

    @Setup(Level.Trial)
    fun setUp() {
        db = Database.connect("jdbc:h2:mem:bench_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        seed()
        cachedMap = scenario.cachedMap()
        verifySameSql()
    }

    /** The comparison is meaningless unless both variants emit exactly the same SQL. */
    private fun verifySameSql() = transaction(db) {
        val manual = scenario.base().where(scenario.manual()).sql()
        val library = scenario.base().applyFiltersOn(scenario.source, scenario.request).sql()
        val cached = scenario.base().applyFilters(cachedMap, scenario.request).sql()
        check(manual == library && manual == cached) {
            "SQL mismatch for $scenario:\n manual:  $manual\n library: $library\n cached:  $cached"
        }
        val manualRows = scenario.base().where(scenario.manual()).count()
        val libraryRows = scenario.base().applyFiltersOn(scenario.source, scenario.request).count()
        check(manualRows == libraryRows)
        println("\n[$scenario] rows=$libraryRows  $library")
    }

    private fun seed() = transaction(db) {
        SchemaUtils.create(Warehouses, Products)
        val warehouseIds = Warehouses.batchInsert((1..10).toList()) { i ->
            this[Warehouses.name] = "Warehouse $i"
            this[Warehouses.city] = "City ${i % 3}"
        }.map { it[Warehouses.id] }
        Products.batchInsert((1..1000).toList()) { i ->
            this[Products.sku] = "SKU-$i"
            this[Products.name] = "Product $i"
            this[Products.description] = "Description of product $i"
            this[Products.category] = "cat${i % 10}"
            this[Products.brand] = "Brand${i % 7}"
            this[Products.price] = (i % 200).toDouble()
            this[Products.discount] = (i % 30).toDouble()
            this[Products.weight] = (i % 50) / 10.0
            this[Products.quantity] = i % 300
            this[Products.rating] = i % 5
            this[Products.active] = i % 4 != 0
            this[Products.status] = Status.entries[i % 3]
            this[Products.code] = kotlin.uuid.Uuid.random()
            this[Products.warehouseId] = warehouseIds[i % warehouseIds.size]
        }
    }
}

private fun Query.sql(): String = prepareSQL(QueryBuilder(false))

private inline fun DbState.repeatInTx(times: Int, bh: Blackhole, crossinline op: () -> Any) =
    transaction(db) { repeat(times) { bh.consume(op()) } }

/**
 * Level (a): building the predicate and rendering SQL — the part the library replaces.
 * Level (b): same, starting from the raw JSON body (what a REST endpoint actually receives).
 * Level (c): full execution on H2, to put the overhead in proportion to a real query.
 * `a3_library_cachedMap` is the library with field resolution done once up front — what caching would buy.
 * `a0_txBaseline` is the transaction cost spread over [BUILD_OPS]; it is included in every (a)/(b) number.
 */
open class FilterBenchmarks {
    @Benchmark
    @OperationsPerInvocation(BUILD_OPS)
    fun a0_txBaseline(s: DbState, bh: Blackhole) = s.repeatInTx(BUILD_OPS, bh) { s.scenario }

    @Benchmark
    @OperationsPerInvocation(BUILD_OPS)
    fun a1_manual_buildSql(s: DbState, bh: Blackhole) = s.repeatInTx(BUILD_OPS, bh) {
        s.scenario.base().where(s.scenario.manual()).sql()
    }

    @Benchmark
    @OperationsPerInvocation(BUILD_OPS)
    fun a2_library_buildSql(s: DbState, bh: Blackhole) = s.repeatInTx(BUILD_OPS, bh) {
        s.scenario.base().applyFiltersOn(s.scenario.source, s.scenario.request).sql()
    }

    @Benchmark
    @OperationsPerInvocation(BUILD_OPS)
    fun a3_library_cachedMap_buildSql(s: DbState, bh: Blackhole) = s.repeatInTx(BUILD_OPS, bh) {
        s.scenario.base().applyFilters(s.cachedMap, s.scenario.request).sql()
    }

    @Benchmark
    @OperationsPerInvocation(BUILD_OPS)
    fun b_library_parseBuildSql(s: DbState, bh: Blackhole) = s.repeatInTx(BUILD_OPS, bh) {
        s.scenario.base().applyFiltersOn(s.scenario.source, parseFilterRequestOrNull(s.scenario.json)).sql()
    }

    @Benchmark
    @OperationsPerInvocation(EXEC_OPS)
    fun c1_manual_execute(s: DbState, bh: Blackhole) = s.repeatInTx(EXEC_OPS, bh) {
        s.scenario.base().where(s.scenario.manual()).toList()
    }

    @Benchmark
    @OperationsPerInvocation(EXEC_OPS)
    fun c2_library_execute(s: DbState, bh: Blackhole) = s.repeatInTx(EXEC_OPS, bh) {
        s.scenario.base().applyFiltersOn(s.scenario.source, parseFilterRequestOrNull(s.scenario.json)).toList()
    }
}

/** Isolates the per-request reflection: resolving property names to columns. */
open class ReflectionBenchmarks {
    @Benchmark
    fun propertyToColumnMap_products15cols(): Any = Products.propertyToColumnMap()

    @Benchmark
    fun propertyToColumnMap_warehouses3cols(): Any = Warehouses.propertyToColumnMap()

    @Benchmark
    fun parseJsonOnly_andScenario(): Any? = parseFilterRequestOrNull(Scenario.AND_4_FIELDS.json)
}
