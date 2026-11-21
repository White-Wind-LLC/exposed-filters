package ua.wwind.exposed.filters.jdbc

/**
 * A filter path that cannot be resolved: the caller's input is wrong. A misconfigured resolver is a plain
 * [IllegalArgumentException] instead, so the two can be answered differently.
 *
 * @property segmentIndex Position of the failed segment; `0` is the root key, even when it contains dots.
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
        UNKNOWN_FIELD,

        /** The path continues below a computed expression. */
        NOT_A_COLUMN,

        /** The path continues below a column that neither references a table nor holds JSON. */
        NOT_A_REFERENCE,

        /** A reference with several targets is followed without naming one. */
        TARGET_REQUIRED,

        /** A target is named on a column without a choice of targets. */
        TARGET_NOT_SUPPORTED,

        UNKNOWN_TARGET,

        /** The path ends at a target, with no field of it. */
        TARGET_WITHOUT_FIELD,

        /** An empty segment, or misplaced brackets. */
        INVALID_SEGMENT,

        /** More references than [FilterOptions.maxReferenceHops] allows. */
        DEPTH_EXCEEDED,
    }
}
