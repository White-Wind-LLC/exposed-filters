package ua.wwind.exposed.filters.benchmark

import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import ua.wwind.exposed.filters.core.FilterRequest
import ua.wwind.exposed.filters.jdbc.propertyToColumnMap
import ua.wwind.exposed.filters.rest.parseFilterRequestOrNull

object Warehouses : IntIdTable("warehouses") {
    val name = varchar("name", 100)
    val city = varchar("city", 100)
}

enum class Status { NEW, ACTIVE, ARCHIVED }

object Products : IntIdTable("products") {
    val sku = varchar("sku", 32)
    val name = varchar("name", 200)
    val description = text("description")
    val category = varchar("category", 50)
    val brand = varchar("brand", 50)
    val price = double("price")
    val discount = double("discount")
    val weight = double("weight")
    val quantity = integer("quantity")
    val rating = integer("rating")
    val active = bool("active")
    val status = enumerationByName<Status>("status", 16)
    val code = uuid("code")
    val warehouseId = reference("warehouse_id", Warehouses)
}

/** Plain two-table join: fields resolve by SQL name through Exposed's internal `joinParts`. */
val ProductsWithWarehouse = Products innerJoin Warehouses

/** A grouped subquery joined to a table: `total` is a computed field addressed by its alias label. */
object Stock {
    val total = Products.quantity.sum().alias("total")
    val sub = Products.select(Products.warehouseId, total).groupBy(Products.warehouseId).alias("stock")
    val join = sub.innerJoin(Warehouses, { sub[Products.warehouseId] }, { Warehouses.id })

    @Suppress("UNCHECKED_CAST")
    val totalExpr = sub[total] as ExpressionWithColumnType<Int?>
}

/**
 * One filter shape expressed every way the benchmark needs: the raw JSON body, the source the library
 * resolves fields on, the query a caller would start from, the hand-written equivalent predicate, and
 * a precomputed field map (what a cache inside the library would hold).
 */
enum class Scenario(
    val json: String,
    val source: ColumnSet,
    val base: () -> Query,
    val manual: () -> Op<Boolean>,
    val cachedMap: () -> Map<String, ExpressionWithColumnType<*>>,
) {
    SIMPLE_EQ(
        """{"filters":{"category":[{"op":"EQ","value":"cat3"}]}}""",
        Products, { Products.selectAll() },
        { Products.category.lowerCase() eq "cat3" },
        { Products.propertyToColumnMap() },
    ),
    AND_4_FIELDS(
        """{"filters":{"status":[{"op":"EQ","value":"ACTIVE"}],"price":[{"op":"GTE","value":10}],""" +
            """"quantity":[{"op":"LT","value":100}],"brand":[{"op":"EQ","value":"Brand2"}]}}""",
        Products, { Products.selectAll() },
        {
            (Products.status eq Status.ACTIVE) and
                (Products.price greaterEq 10.0) and
                (Products.quantity less 100) and
                (Products.brand.lowerCase() eq "brand2")
        },
        { Products.propertyToColumnMap() },
    ),
    OR_NOT_IN_BETWEEN(
        """{"combinator":"AND","children":[""" +
            """{"combinator":"OR","children":[""" +
            """{"filters":{"brand":[{"op":"IN","values":["Brand1","Brand2","Brand3"]}]}},""" +
            """{"filters":{"price":[{"op":"BETWEEN","values":[5,50]}]}}]},""" +
            """{"combinator":"NOT","children":[{"filters":{"active":[{"op":"EQ","value":false}]}}]}]}""",
        Products, { Products.selectAll() },
        {
            ((Products.brand.lowerCase() inList listOf("brand1", "brand2", "brand3")) or
                Products.price.between(5.0, 50.0)) and
                not(Products.active eq false)
        },
        { Products.propertyToColumnMap() },
    ),
    NESTED_REF(
        """{"filters":{"warehouseId.name":[{"op":"EQ","value":"Warehouse 3"}]}}""",
        Products, { Products.selectAll() },
        {
            exists(
                Warehouses.selectAll().where {
                    (Warehouses.id eq Products.warehouseId) and (Warehouses.name.lowerCase() eq "warehouse 3")
                }
            )
        },
        { Products.propertyToColumnMap() },
    ),
    JOIN_COLUMNS(
        """{"filters":{"category":[{"op":"EQ","value":"cat3"}],"city":[{"op":"EQ","value":"City 1"}]}}""",
        ProductsWithWarehouse, { ProductsWithWarehouse.selectAll() },
        { (Products.category.lowerCase() eq "cat3") and (Warehouses.city.lowerCase() eq "city 1") },
        { mapOf("category" to Products.category, "city" to Warehouses.city) },
    ),
    STOCK_COMPUTED(
        """{"filters":{"total":[{"op":"GT","value":14000}],"city":[{"op":"EQ","value":"City 1"}]}}""",
        Stock.join, { Stock.join.select(Warehouses.name, Stock.totalExpr) },
        { (Stock.totalExpr greater 14000) and (Warehouses.city.lowerCase() eq "city 1") },
        { mapOf("total" to Stock.totalExpr, "city" to Warehouses.city) },
    );

    val request: FilterRequest = requireNotNull(parseFilterRequestOrNull(json))
}
