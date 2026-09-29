package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry has to hold both levels at once, and has to fail loudly when it cannot.
 */
class AbiRegistryTest {

    @Test
    fun `builds a registry from the staged ABI jars`() {
        val registry = AbiRegistry.fromDirectory(TestArtifacts.extensionArtifacts)

        assertEquals(setOf(ExtensionAbi.V1_4, ExtensionAbi.V1_6), registry.supported)
        assertTrue(registry[ExtensionAbi.V1_4]!!.apiJar.isFile)
        assertTrue(registry[ExtensionAbi.V1_6]!!.apiJar.isFile)
    }

    @Test
    fun `maps each level onto its own bridge class`() {
        assertEquals(AbiRegistry.BRIDGE_1_4, AbiRegistry.bridgeClassFor(ExtensionAbi.V1_4))
        assertEquals(AbiRegistry.BRIDGE_1_6, AbiRegistry.bridgeClassFor(ExtensionAbi.V1_6))
    }

    @Test
    fun `a missing level is a startup failure, not a runtime surprise`() {
        val onlySixteen = Files.createTempDirectory("shura-abi").toFile().apply {
            TestArtifacts.abiJar("1.6").copyTo(resolve("shura-abi-1.6.jar"))
        }

        val registry = AbiRegistry.fromDirectory(onlySixteen)

        assertEquals(setOf(ExtensionAbi.V1_6), registry.supported)
        assertNull(registry[ExtensionAbi.V1_4])
        val failure = assertThrows<UnsupportedAbiException> { registry.require(ExtensionAbi.V1_4) }
        assertTrue(failure.message!!.contains("1.4"), "was: ${failure.message}")
    }

    @Test
    fun `refuses a directory with no ABI jars at all`() {
        val empty = Files.createTempDirectory("shura-abi-empty").toFile()

        assertFailsWith<IllegalArgumentException> { AbiRegistry.fromDirectory(empty) }
    }

    @Test
    fun `refuses a duplicate level`() {
        val jar = TestArtifacts.abiJar("1.6")
        val duplicate = listOf(
            AbiArtifact(ExtensionAbi.V1_6, jar, AbiRegistry.BRIDGE_1_6),
            AbiArtifact(ExtensionAbi.V1_6, jar, AbiRegistry.BRIDGE_1_6),
        )

        assertFailsWith<IllegalArgumentException> { AbiRegistry(duplicate) }
    }

    @Test
    fun `refuses an artifact whose jar is not there`() {
        val missing = File(TestArtifacts.extensionArtifacts, "shura-abi-9.9.jar")

        val failure = assertThrows<IllegalArgumentException> {
            AbiArtifact(ExtensionAbi.V1_6, missing, AbiRegistry.BRIDGE_1_6)
        }
        assertTrue(failure.message!!.contains("9.9"), "was: ${failure.message}")
    }

    @Test
    fun `the staged ABI jars are named after the level they implement`() {
        assertNotNull(TestArtifacts.abiJar("1.4"))
        assertNotNull(TestArtifacts.abiJar("1.6"))
    }
}
