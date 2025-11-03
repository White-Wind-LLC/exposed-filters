package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.IColumnType

/**
 * Container for multiple column value mappers.
 * Mappers are tried in reverse order of addition (last added first).
 */
public class ColumnMappersModule {
    private val mappers = mutableListOf<ColumnValueMapper>()

    /**
     * Add a mapper to this module.
     */
    public fun addMapper(mapper: ColumnValueMapper) {
        mappers.add(mapper)
    }

    /**
     * Add a mapper using a lambda.
     */
    public fun mapper(block: (IColumnType<*>, String) -> Any?) {
        addMapper(object : ColumnValueMapper {
            override fun <T : Any> map(columnType: IColumnType<T>, raw: String): T? {
                @Suppress("UNCHECKED_CAST")
                return block(columnType, raw) as? T
            }
        })
    }

    /**
     * Try to map a value using all registered mappers.
     * Returns null if no mapper handles the given column type.
     */
    internal fun <T : Any> tryMap(columnType: IColumnType<T>, raw: String): T? {
        // Try mappers in reverse order (last added first)
        for (mapper in mappers.asReversed()) {
            val result = mapper.map(columnType, raw)
            if (result != null) return result
        }
        return null
    }
}

/**
 * Plug-in module to map raw string values from filters into column-specific typed values.
 *
 * Return null if this mapper does not handle the given [columnType]. The library will fall back
 * to built-in mappers and, if still unsupported, will throw an error.
 */
public interface ColumnValueMapper {
    public fun <T : Any> map(columnType: IColumnType<T>, raw: String): T?
}

/**
 * Creates a new [ColumnMappersModule] and configures it using the provided block.
 */
public fun columnMappers(block: ColumnMappersModule.() -> Unit): ColumnMappersModule {
    return ColumnMappersModule().apply(block)
}
