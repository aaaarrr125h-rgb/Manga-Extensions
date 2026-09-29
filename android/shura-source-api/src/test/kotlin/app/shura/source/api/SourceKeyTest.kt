package app.shura.source.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceKeyTest {

    @Test
    fun `splits package name and source id`() {
        val key = SourceKey.of("eu.kanade.tachiyomi.extension.all.comicgrowl", 299423548273637501L)
        assertEquals("eu.kanade.tachiyomi.extension.all.comicgrowl", key.packageName)
        assertEquals(299423548273637501L, key.sourceId)
    }

    @Test
    fun `wire form round trips`() {
        val key = SourceKey.of("app.shura.ext.demo", 42L)
        assertEquals(key, SourceKey.parse(key.value))
        assertEquals("app.shura.ext.demo#42", key.value)
    }

    @Test
    fun `accepts negative source ids`() {
        assertEquals(-1L, SourceKey.parse("app.shura.ext.demo#-1").sourceId)
    }

    @Test
    fun `rejects a value without a source id`() {
        assertFailsWith<IllegalArgumentException> { SourceKey.parse("app.shura.ext.demo") }
        assertNull(SourceKey.parseOrNull("app.shura.ext.demo"))
    }

    @Test
    fun `rejects a non numeric source id`() {
        assertFailsWith<IllegalArgumentException> { SourceKey.parse("app.shura.ext.demo#abc") }
    }

    @Test
    fun `rejects an empty package name`() {
        assertFailsWith<IllegalArgumentException> { SourceKey.parse("#42") }
    }

    @Test
    fun `rejects a package segment that starts with a digit`() {
        assertFailsWith<IllegalArgumentException> { SourceKey.parse("app.shura.1demo#42") }
    }

    @Test
    fun `rejects an empty package segment`() {
        assertFailsWith<IllegalArgumentException> { SourceKey.parse("app..demo#42") }
    }

    @Test
    fun `package name validation accepts identifiers with underscores and digits`() {
        assertTrue(SourceKey.isValidPackageName("a.b_c.d2"))
        assertFalse(SourceKey.isValidPackageName("a.b-c"))
        assertFalse(SourceKey.isValidPackageName(""))
    }
}
