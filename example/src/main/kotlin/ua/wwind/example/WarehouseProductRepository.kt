package ua.wwind.example

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.util.UUID

object Warehouses : IdTable<UUID>("warehouses") {
    override val id: Column<EntityID<UUID>> = uuid("id").entityId()
    val name: Column<String> = varchar("name", 100)

    override val primaryKey = PrimaryKey(id)
}

object Products : LongIdTable("products") {
    // Property name is camelCase, DB column is snake_case
    val warehouseId: Column<EntityID<UUID>> = reference("warehouse_id", Warehouses.id)
    val title: Column<String> = varchar("title", 120)
}

data class Warehouse(val id: UUID, val name: String)

data class Product(val id: Int?, val warehouseId: UUID, val title: String)
