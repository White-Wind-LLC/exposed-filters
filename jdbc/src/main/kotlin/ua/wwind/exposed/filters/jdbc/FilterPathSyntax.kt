package ua.wwind.exposed.filters.jdbc

/** A path segment, e.g. `documentId[RECEIPT]`: [raw] as written, its field [name] and optional [target]. */
internal data class PathSegment(val raw: String, val name: String, val target: String?)

/** A name has no bracket; a target is non-empty, without a dot or bracket. */
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

/** A field split into its key [baseName], the [rootSegment] naming it as written, its [rootTarget] and the rest. */
internal data class FieldPath(val baseName: String, val rootSegment: String, val rootTarget: String?, val path: String?)

/**
 * Splits [field] at the longest dot-delimited prefix that is a key, literally or without a trailing
 * `[target]` (the literal key wins); with no such prefix the root is the first segment.
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
        if (isKey(candidate)) return FieldPath(candidate, candidate, null, field.pathAfter(end))
        val (name, target) = candidate.splitTarget() ?: continue
        if (isKey(name)) return FieldPath(name, candidate, target, field.pathAfter(end))
    }
    val first = field.substring(0, ends.first())
    val (name, target) = first.splitTarget() ?: (first to null)
    return FieldPath(name, first, target, field.pathAfter(ends.first()))
}

private fun String.pathAfter(end: Int): String? = if (end == length) null else substring(end + 1)

private fun String.splitTarget(): Pair<String, String>? {
    if (!endsWith(']')) return null
    val open = lastIndexOf('[')
    if (open <= 0) return null
    val target = substring(open + 1, length - 1)
    return if (target.isValidTarget()) substring(0, open) to target else null
}

/** JSON segments are taken verbatim, brackets included; only an empty one fails. */
internal fun parseJsonPath(field: String, firstIndex: Int, segments: List<String>): List<String> {
    segments.forEachIndexed { offset, segment ->
        if (segment.isEmpty()) throw invalidSegment(field, firstIndex + offset, segment)
    }
    return segments
}
