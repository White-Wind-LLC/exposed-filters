package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * The member properties of each [Table] class that can hold a [Column], already made accessible.
 *
 * The `memberProperties` scan and `isAccessible` setup cost several microseconds per table, far more
 * than reading the properties afterwards, so they run once per class. The cache is keyed by class and
 * holds only property references, never a table instance: tables created at runtime (an `Alias` per
 * request, a table built from metadata) stay collectable. Weak keys per instance would not work -
 * every column references its table, so a cached column map would keep its own key reachable.
 */
private val columnProperties = object : ClassValue<List<KProperty1<Any, *>>>() {
    override fun computeValue(type: Class<*>): List<KProperty1<Any, *>> =
        type.kotlin.memberProperties.mapNotNull { prop ->
            @Suppress("UNCHECKED_CAST")
            val typed = prop as? KProperty1<Any, *> ?: return@mapNotNull null
            if (!typed.canHoldColumn()) return@mapNotNull null
            // Some properties can be non-public on generated tables; make accessible defensively.
            typed.isAccessible = true
            typed
        }
}

/**
 * Whether the declared type admits a [Column] value: a column type itself, or a supertype such as
 * `Expression<*>` or `Any`. Excludes the table's own bookkeeping (`tableName`, `columns`, ...) without
 * deciding by declared type alone - a column stored in an `Any` property is still found at read time.
 */
private fun KProperty1<*, *>.canHoldColumn(): Boolean {
    val classifier = returnType.classifier as? KClass<*> ?: return true
    return classifier.isSubclassOf(Column::class) || Column::class.isSubclassOf(classifier)
}

/** The properties of this table's class that can hold a column; see [columnProperties]. */
internal fun Table.columnProperties(): List<KProperty1<Any, *>> = columnProperties.get(this::class.java)
