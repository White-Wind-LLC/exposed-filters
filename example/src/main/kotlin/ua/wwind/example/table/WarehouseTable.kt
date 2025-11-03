package ua.wwind.example.table

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import ua.wwind.example.type.WarehouseId
import ua.wwind.example.type.warehouseId

object WarehouseTable : Table("warehouses") {
    val id: Column<WarehouseId> = warehouseId("id")
    val name: Column<String> = varchar("name", 100)

    override val primaryKey = PrimaryKey(id)
}

data class Warehouse(val id: WarehouseId, val name: String)
