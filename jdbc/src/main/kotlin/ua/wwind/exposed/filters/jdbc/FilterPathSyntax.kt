package ua.wwind.exposed.filters.jdbc

/**
 * One segment of a filter path below its root: a field [name], optionally followed by the [target] a
 * reference with several targets is followed into, as in `documentId[RECEIPT]`.
 *
 * @property raw The segment as written.
 */
internal data class PathSegment(val raw: String, val name: String, val target: String?)

/**
 * Parses the segment [raw] at position [index] of [field]. The name is non-empty and carries no
 * bracket; a target, when given, is non-empty and carries no dot or bracket. Anything else fails
 * with [FilterPathException.Reason.INVALID_SEGMENT].
 */
internal fun parsePathSegment(field: String, index: Int, raw: String): PathSegment {
    val open = raw.indexOf('[')
    if (open < 0) {
        if (raw.isEmpty() || ']' in raw) throw invalidSegment(field, index, raw)
        return PathSegment(raw, raw, null)
    }
    val name = raw.substring(0, open)
    if (name.isEmpty() || ']' in name || !raw.endsWith(']')) throw invalidSegment(field, index, raw)
    val target = raw.substring(open + 1, raw.length - 1)
    if (!target.isValidTarget()) throw invalidSegment(field, index, raw)
    return PathSegment(raw, name, target)
}

private fun invalidSegment(field: String, index: Int, raw: String): FilterPathException = FilterPathException(
    field = field,
    segmentIndex = index,
    segment = raw,
    reason = FilterPathException.Reason.INVALID_SEGMENT,
    message = "Invalid segment '$raw' at position $index of filter field '$field'",
)

private fun String.isValidTarget(): Boolean = isNotEmpty() && none { it == '.' || it == '[' || it == ']' }

/**
 * A filter field split into the [baseName] it is looked up by, the [rootSegment] that names it as
 * written (with a `[target]` when one follows the key) and the dot [path] below it; `null` when
 * nothing follows the root.
 */
internal data class FieldPath(val baseName: String, val rootSegment: String, val path: String?)

/**
 * Splits [field] at the longest dot-delimited prefix that names a key [isKey] accepts, so a map can
 * expose namespaced keys such as `product.category` while `product.attributes.color` still reads a
 * JSON path below the key `product.attributes`. A prefix names a key either literally or, when it ends
 * in a well-formed `[target]`, without that target; the literal key wins. When no prefix names a key,
 * the root is the first segment: that is what an unknown-field error names, and what every map
 * without dotted keys resolved to before.
 */
internal fun splitField(field: String, isKey: (String) -> Boolean): FieldPath {
    val ends = buildList {
        var dot = field.indexOf('.')
        while (dot >= 0) {
            add(dot)
            dot = field.indexOf('.', dot + 1)
        }
        add(field.length)
    }
    for (end in ends.asReversed()) {
        val candidate = field.substring(0, end)
        val baseName = (if (isKey(candidate)) candidate else candidate.withoutTarget()?.takeIf(isKey)) ?: continue
        return FieldPath(baseName, candidate, field.pathAfter(end))
    }
    val first = field.substring(0, ends.first())
    return FieldPath(first.withoutTarget() ?: first, first, field.pathAfter(ends.first()))
}

private fun String.pathAfter(end: Int): String? = if (end == length) null else substring(end + 1)

/** This prefix without the well-formed `[target]` it ends in; `null` when it ends in none. */
private fun String.withoutTarget(): String? {
    if (!endsWith(']')) return null
    val open = lastIndexOf('[')
    if (open <= 0 || !substring(open + 1, length - 1).isValidTarget()) return null
    return substring(0, open)
}

/**
 * The JSON path [segments] of [field], the first of them at position [firstIndex]. They are taken
 * verbatim, brackets included; only an empty one fails, with
 * [FilterPathException.Reason.INVALID_SEGMENT].
 */
internal fun parseJsonPath(field: String, firstIndex: Int, segments: List<String>): List<String> {
    segments.forEachIndexed { offset, segment ->
        if (segment.isEmpty()) throw invalidSegment(field, firstIndex + offset, segment)
    }
    return segments
}
