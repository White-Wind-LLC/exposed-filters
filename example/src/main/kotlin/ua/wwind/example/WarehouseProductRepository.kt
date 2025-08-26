package ua.wwind.example

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.util.*

object Warehouses : Table("warehouses") {
    val id: Column<UUID> = uuid("id")
    val name: Column<String> = varchar("name", 100)

    override val primaryKey = PrimaryKey(id)
}

object Products : Table("products") {
    val id: Column<Int> = integer("id").autoIncrement()

    // Property name is camelCase, DB column is snake_case
    val warehouseId: Column<UUID> = reference("warehouse_id", Warehouses.id)
    val title: Column<String> = varchar("title", 120)

    override val primaryKey = PrimaryKey(id)
}

data class Warehouse(val id: UUID, val name: String)

data class Product(val id: Int?, val warehouseId: UUID, val title: String)
