package ua.wwind.example.type

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Table

@JvmInline
value class UserId(val value: Int)

/**
 * Custom Exposed column type for [UserId].
 * Stores values as SQL INT under the hood and converts to/from [UserId].
 */
object UserIdColumnType : ColumnType<UserId>() {
    private val delegate = IntegerColumnType()

    override fun sqlType(): String = delegate.sqlType()

    override fun nonNullValueToString(value: UserId): String = value.value.toString()

    override fun notNullValueToDB(value: UserId): Any = value.value

    override fun valueFromDB(value: Any): UserId = when (value) {
        is UserId -> value
        is Int -> UserId(value)
        is Number -> UserId(value.toInt())
        is String -> UserId(value.toInt())
        else -> error("Cannot convert ${value::class.simpleName} to UserId")
    }
}

/**
 * Registers a [UserId] column backed by SQL INT, supports `autoIncrement()`.
 */
fun Table.userId(name: String): Column<UserId> = registerColumn(name, UserIdColumnType)
