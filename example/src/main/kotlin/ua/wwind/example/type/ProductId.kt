package ua.wwind.example.type

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UuidColumnType
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

@ExperimentalUuidApi
@JvmInline
value class ProductId(val value: Uuid)

/**
 * Custom Exposed column type for [ProductId].
 * Stores values as SQL UUID and converts to/from [ProductId].
 */
@OptIn(ExperimentalUuidApi::class)
object ProductIdColumnType : ColumnType<ProductId>() {
    private val delegate = UuidColumnType()

    override fun sqlType(): String = delegate.sqlType()

    override fun nonNullValueToString(value: ProductId): String = delegate.nonNullValueToString(value.value)

    override fun notNullValueToDB(value: ProductId): Any = delegate.notNullValueToDB(value.value)

    override fun valueFromDB(value: Any): ProductId = when (value) {
        is ProductId -> value
        is Uuid -> ProductId(value)
        is UUID -> ProductId(value.toKotlinUuid())
        is String -> ProductId(Uuid.parse(value))
        else -> error("Cannot convert ${value::class.simpleName} to ProductId")
    }
}

/**
 * Registers a [ProductId] column backed by SQL UUID.
 */
@OptIn(ExperimentalUuidApi::class)
fun Table.productId(name: String): Column<ProductId> = registerColumn(name, ProductIdColumnType)
