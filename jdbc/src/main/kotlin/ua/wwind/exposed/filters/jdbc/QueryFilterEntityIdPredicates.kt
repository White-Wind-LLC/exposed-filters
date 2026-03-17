package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Equality predicate for EntityID columns.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?)
internal fun eqEntityIdValue(
    column: Column<EntityID<*>>,
    raw: String,
    fieldName: String
): Op<Boolean> {
    val rawColumnType = rawColumnTypeOf(column, fieldName)
    if (mappersModule != null) {
        @Suppress("UNCHECKED_CAST")
        val customMapped = mappersModule.tryMap(rawColumnType as IColumnType<Any>, raw)
        if (customMapped != null) {
            @Suppress("UNCHECKED_CAST")
            return (column as Column<EntityID<Any>>).eq(customMapped)
        }
    }
    return when (rawColumnType) {
        is IntegerColumnType -> (column as Column<EntityID<Int>>).eq(raw.toInt())
        is LongColumnType -> (column as Column<EntityID<Long>>).eq(raw.toLong())
        is ShortColumnType -> (column as Column<EntityID<Short>>).eq(raw.toShort())
        is VarCharColumnType -> (column as Column<EntityID<String>>).eq(raw)
        is UUIDColumnType -> (column as Column<EntityID<java.util.UUID>>).eq(java.util.UUID.fromString(raw))
        is UuidColumnType -> (column as Column<EntityID<Uuid>>).eq(Uuid.parse(raw))
        else -> error("Unsupported equality for field '$fieldName'")
    }
}

/**
 * IN predicate for EntityID columns.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?)
internal fun inListEntityIdValue(
    column: Column<*>,
    raws: List<String>,
    fieldName: String
): Op<Boolean> {
    val rawColumnType = rawColumnTypeOf(column, fieldName)
    if (mappersModule != null) {
        @Suppress("UNCHECKED_CAST")
        val customMapped = raws.mapNotNull { raw ->
            mappersModule.tryMap(rawColumnType as IColumnType<Any>, raw)
        }
        if (customMapped.size == raws.size) {
            @Suppress("UNCHECKED_CAST")
            return (column as Column<EntityID<Any>>).inList(customMapped)
        }
    }
    return when (rawColumnType) {
        is IntegerColumnType -> (column as Column<EntityID<Int>>).inList(raws.map(String::toInt))
        is LongColumnType -> (column as Column<EntityID<Long>>).inList(raws.map(String::toLong))
        is ShortColumnType -> (column as Column<EntityID<Short>>).inList(raws.map(String::toShort))
        is VarCharColumnType -> (column as Column<EntityID<String>>).inList(raws)
        is UUIDColumnType -> (column as Column<EntityID<java.util.UUID>>).inList(raws.map(java.util.UUID::fromString))
        is UuidColumnType -> (column as Column<EntityID<Uuid>>).inList(raws.map(Uuid::parse))
        else -> error("Unsupported IN for field '$fieldName'")
    }
}

/**
 * Extracts the underlying ID column type from an EntityID column type.
 */
internal fun rawColumnTypeOf(column: Column<*>, fieldName: String): IColumnType<*> {
    val ct = column.columnType
    val isEntityId = ct is EntityIDColumnType<*>
    if (!isEntityId) return ct
    val idColumn = ct.javaClass.methods
        .firstOrNull { it.name == "getIdColumn" && it.parameterCount == 0 }
        ?.invoke(ct) as? Column<*>
    if (idColumn == null) {
        error("Cannot access idColumn for EntityID field '$fieldName'")
    }
    return idColumn.columnType
}