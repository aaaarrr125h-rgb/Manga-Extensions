package app.shura.source.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCapabilitiesTest {

    @Test
    fun `adding a capability makes contains true`() {
        val capabilities = SourceCapabilities.NONE + SourceCapability.SEARCH
        assertTrue(SourceCapability.SEARCH in capabilities)
        assertFalse(SourceCapability.BROWSE_LATEST in capabilities)
    }

    @Test
    fun `adding the same capability twice changes nothing`() {
        val once = SourceCapabilities.of(SourceCapability.SEARCH)
        val twice = once + SourceCapability.SEARCH
        assertEquals(once, twice)
    }

    @Test
    fun `removing a capability that is not present changes nothing`() {
        val capabilities = SourceCapabilities.of(SourceCapability.SEARCH)
        assertEquals(capabilities, capabilities - SourceCapability.CONFIGURABLE)
    }

    @Test
    fun `hasAll is a subset test`() {
        val all = SourceCapabilities.of(SourceCapability.SEARCH, SourceCapability.BROWSE_POPULAR)
        val subset = SourceCapabilities.of(SourceCapability.SEARCH)
        assertTrue(all.hasAll(subset))
        assertFalse(subset.hasAll(all))
    }

    @Test
    fun `asList is in enum order so it is stable`() {
        val capabilities = SourceCapabilities.of(
            SourceCapability.FETCH_PAGES,
            SourceCapability.BROWSE_POPULAR,
            SourceCapability.SEARCH,
        )
        assertContentEquals(
            listOf("BROWSE_POPULAR", "SEARCH", "FETCH_PAGES"),
            capabilities.asNames(),
        )
    }

    @Test
    fun `parses a comma separated list`() {
        val parsed = SourceCapabilities.parseOrNull("SEARCH, FETCH_PAGES")
        assertEquals(SourceCapabilities.of(SourceCapability.SEARCH, SourceCapability.FETCH_PAGES), parsed)
    }

    @Test
    fun `refuses an unknown capability instead of dropping it`() {
        assertNull(SourceCapabilities.parseOrNull("SEARCH,TELEPORT"))
        assertFailsWith<IllegalArgumentException> { SourceCapabilities.parse(listOf("TELEPORT")) }
    }

    @Test
    fun `NONE prints as an empty list`() {
        assertEquals("[]", SourceCapabilities.NONE.toString())
    }

    @Test
    fun `DEFAULT advertises exactly the five mandatory operations`() {
        assertEquals(
            listOf("BROWSE_POPULAR", "SEARCH", "FETCH_DETAILS", "FETCH_CHAPTERS", "FETCH_PAGES"),
            SourceCapabilities.DEFAULT.asNames(),
        )
    }
}
