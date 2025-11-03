package ua.wwind.example.type

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UUIDColumnType
import java.util.UUID

@JvmInline
value class WarehouseId(val value: UUID)

/**
 * Custom Exposed column type for [WarehouseId].
 * Stores values as SQL UUID and converts to/from [WarehouseId].
 */
object WarehouseIdColumnType : ColumnType<WarehouseId>() {
    private val delegate = UUIDColumnType()

    override fun sqlType(): String = delegate.sqlType()

    override fun nonNullValueToString(value: WarehouseId): String = delegate.nonNullValueToString(value.value)

    override fun notNullValueToDB(value: WarehouseId): Any = delegate.notNullValueToDB(value.value)

    override fun valueFromDB(value: Any): WarehouseId = when (value) {
        is WarehouseId -> value
        is UUID -> WarehouseId(value)
        is String -> WarehouseId(UUID.fromString(value))
        else -> error("Cannot convert ${value::class.simpleName} to WarehouseId")
    }
}

/**
 * Registers a [WarehouseId] column backed by SQL UUID.
 */
fun Table.warehouseId(name: String): Column<WarehouseId> = registerColumn(name, WarehouseIdColumnType)
