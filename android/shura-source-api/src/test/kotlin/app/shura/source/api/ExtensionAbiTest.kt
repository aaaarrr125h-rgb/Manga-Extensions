package app.shura.source.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExtensionAbiTest {

    @Test
    fun `parses the two levels the host implements`() {
        assertSame(ExtensionAbi.V1_4, ExtensionAbi.parse("1.4"))
        assertSame(ExtensionAbi.V1_6, ExtensionAbi.parse("1.6"))
    }

    @Test
    fun `trims whitespace because manifest values are xml attributes`() {
        assertSame(ExtensionAbi.V1_6, ExtensionAbi.parse(" 1.6 "))
    }

    @Test
    fun `an unknown level is never silently upgraded to the newest one`() {
        assertNull(ExtensionAbi.parseOrNull("1.5"))
        assertNull(ExtensionAbi.parseOrNull("2.0"))
        assertFailsWith<UnsupportedExtensionAbiException> { ExtensionAbi.parse("1.5") }
    }

    @Test
    fun `rejects anything that is not major dot minor`() {
        assertNull(ExtensionAbi.parseOrNull("1"))
        assertNull(ExtensionAbi.parseOrNull("1.6.0"))
        assertNull(ExtensionAbi.parseOrNull("v1.6"))
        assertNull(ExtensionAbi.parseOrNull(""))
    }

    @Test
    fun `rejects a null level`() {
        assertNull(ExtensionAbi.parseOrNull(null))
    }

    @Test
    fun `the error names what the host does support`() {
        val error = assertFailsWith<UnsupportedExtensionAbiException> { ExtensionAbi.parse("1.5") }
        assertEquals("1.5", error.version)
        assertEquals(listOf("1.4", "1.6"), error.supported)
    }

    @Test
    fun `1_6 is suspend only and 1_4 is not`() {
        assertTrue(ExtensionAbi.V1_6.isSuspendOnly)
        assertFalse(ExtensionAbi.V1_4.isSuspendOnly)
    }

    @Test
    fun `ordering follows the encoded level not the enum declaration`() {
        assertTrue(ExtensionAbi.V1_4 < ExtensionAbi.V1_6)
        assertSame(ExtensionAbi.V1_6, maxOf(ExtensionAbi.V1_4, ExtensionAbi.V1_6))
    }

    @Test
    fun `supports mirrors parseOrNull`() {
        assertTrue(ExtensionAbi.supports("1.4"))
        assertFalse(ExtensionAbi.supports("1.7"))
    }
}
