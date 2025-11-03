package ua.wwind.example.type

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UUIDColumnType
import java.util.UUID

@JvmInline
value class ProductId(val value: UUID)

/**
 * Custom Exposed column type for [ProductId].
 * Stores values as SQL UUID and converts to/from [ProductId].
 */
object ProductIdColumnType : ColumnType<ProductId>() {
    private val delegate = UUIDColumnType()

    override fun sqlType(): String = delegate.sqlType()

    override fun nonNullValueToString(value: ProductId): String = delegate.nonNullValueToString(value.value)

    override fun notNullValueToDB(value: ProductId): Any = delegate.notNullValueToDB(value.value)

    override fun valueFromDB(value: Any): ProductId = when (value) {
        is ProductId -> value
        is UUID -> ProductId(value)
        is String -> ProductId(UUID.fromString(value))
        else -> error("Cannot convert ${value::class.simpleName} to ProductId")
    }
}

/**
 * Registers a [ProductId] column backed by SQL UUID.
 */
fun Table.productId(name: String): Column<ProductId> = registerColumn(name, ProductIdColumnType)
