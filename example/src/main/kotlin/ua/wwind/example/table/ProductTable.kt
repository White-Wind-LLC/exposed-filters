package ua.wwind.example.table

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import ua.wwind.example.type.ProductId
import ua.wwind.example.type.WarehouseId
import ua.wwind.example.type.productId
import ua.wwind.example.type.warehouseId
import java.util.UUID

object ProductTable : Table("products") {
    val id: Column<ProductId> = productId("id").clientDefault { ProductId(UUID.randomUUID()) }
    // Property name is camelCase, DB column is snake_case
    val warehouseId: Column<WarehouseId> = warehouseId("warehouse_id").references(WarehouseTable.id)
    val title: Column<String> = varchar("title", 120)

    override val primaryKey = PrimaryKey(id)
}

data class Product(val id: ProductId?, val warehouseId: WarehouseId, val title: String)
