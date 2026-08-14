package ua.wwind.exposed.filters.rest

/**
 * Thrown when a non-empty request body cannot be parsed as a filter request.
 *
 * A body that does not match the expected shape is a client error, not an empty filter: parsing it
 * leniently would silently widen the result set instead of failing. An empty or absent body still
 * means "no filters" and does not raise this exception.
 *
 * Extends [IllegalArgumentException] so applications that already map illegal arguments to
 * `400 Bad Request` keep working without a dedicated handler.
 */
public class FilterRequestParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
