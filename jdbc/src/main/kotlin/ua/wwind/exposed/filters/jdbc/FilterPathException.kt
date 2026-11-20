package ua.wwind.exposed.filters.jdbc

/**
 * A filter field whose path cannot be resolved against the filtered source. It is the caller's input
 * that is wrong, so the type is an [IllegalArgumentException]; a misconfigured resolver is reported
 * as a plain [IllegalArgumentException] instead, so the two can be answered differently.
 *
 * @property field The filter field as written.
 * @property segmentIndex Position of the failed segment in the path; `0` is the root, the key the
 * field is looked up by, even when that key contains dots.
 * @property segment The failed segment as written, including a `[target]` it names.
 * @property reason Why the path cannot be resolved.
 */
public class FilterPathException(
    public val field: String,
    public val segmentIndex: Int,
    public val segment: String,
    public val reason: Reason,
    message: String,
) : IllegalArgumentException(message) {

    /** Why a filter path cannot be resolved. */
    public enum class Reason {
        /** The root is not a field of the source, or a segment is not a field of the table it reaches. */
        UNKNOWN_FIELD,

        /** A path continues below a computed expression, which has no reference to follow. */
        NOT_A_COLUMN,

        /** A path continues below a column that neither references a table nor holds JSON. */
        NOT_A_REFERENCE,

        /** A path continues through a reference with several targets without naming one. */
        TARGET_REQUIRED,

        /** A segment names a target where the column it reaches has no choice of targets. */
        TARGET_NOT_SUPPORTED,

        /** A segment names a target the reference does not know. */
        UNKNOWN_TARGET,

        /** A path ends at a segment that names a target, with no field of that target to read. */
        TARGET_WITHOUT_FIELD,

        /** A segment is not well formed: empty, or with misplaced brackets. */
        INVALID_SEGMENT,

        /** A path follows more references than the configured limit allows. */
        DEPTH_EXCEEDED,
    }
}
