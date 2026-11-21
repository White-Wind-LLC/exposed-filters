package ua.wwind.exposed.filters.jdbc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The syntax of a filter path: its segments and the root a field is looked up by. */
class FilterPathSyntaxTest {

    @Test
    fun `a segment names a target in brackets`() {
        assertEquals(
            PathSegment("documentId[RECEIPT]", "documentId", "RECEIPT"),
            parsePathSegment("f", 1, "documentId[RECEIPT]"),
        )
    }

    @Test
    fun `a plain segment has no target`() {
        assertNull(parsePathSegment("f", 1, "name").target)
    }

    @Test
    fun `a malformed segment fails naming itself`() {
        for (raw in listOf("", "a[", "a[]", "a[b]c", "[b]", "a[b.c]", "a[b][c]")) {
            val failure = assertThrows(FilterPathException::class.java) { parsePathSegment("f", 1, raw) }
            assertEquals(FilterPathException.Reason.INVALID_SEGMENT, failure.reason, raw)
            assertEquals(1, failure.segmentIndex, raw)
            assertEquals(raw, failure.segment, raw)
        }
    }

    @Test
    fun `a root with a target matches the key without it`() {
        assertEquals(
            FieldPath("documentId", "documentId[RECEIPT]", "RECEIPT", "number"),
            splitField("documentId[RECEIPT].number") { it == "documentId" },
        )
    }

    @Test
    fun `the longest dotted key is the root`() {
        val split = splitField("product.category.name") { it == "product.category" }
        assertEquals("product.category", split.baseName)
        assertEquals("name", split.path)
    }

    @Test
    fun `a key written with brackets wins over the key without them`() {
        val split = splitField("a[x].b") { it == "a[x]" || it == "a" }
        assertEquals("a[x]", split.baseName)
        assertEquals("a[x]", split.rootSegment)
        assertNull(split.rootTarget)
    }

    @Test
    fun `with no matching key the root is the first segment`() {
        assertEquals(FieldPath("missing", "missing", null, "x"), splitField("missing.x") { false })
        assertEquals(FieldPath("missing", "missing[X]", "X", "y"), splitField("missing[X].y") { false })
    }
}
