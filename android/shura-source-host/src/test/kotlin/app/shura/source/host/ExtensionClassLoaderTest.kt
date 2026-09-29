package app.shura.source.host

import app.shura.source.api.SourceProvider
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The isolation the whole design rests on.
 *
 * `eu.kanade.tachiyomi.source.Source` exists at both levels with incompatible members, so a host
 * that resolved it from a single parent could only ever hold one level. These tests assert the
 * two things that makes holding both possible: the extension facing classes come from the
 * extension's own classloader, and the host facing interface does not.
 */
class ExtensionClassLoaderTest {

    private fun loaderFor(vararg jars: File) = ExtensionClassLoader(
        urls = jars.map { it.toURI().toURL() }.toTypedArray(),
        parent = ExtensionClassLoaderTest::class.java.classLoader,
    )

    @Test
    fun `the host classpath does not carry the extension facing surface`() {
        // If this ever starts passing, the isolation below is being satisfied by accident.
        assertFailsWith<ClassNotFoundException> {
            Class.forName("eu.kanade.tachiyomi.source.Source", false, this::class.java.classLoader)
        }
    }

    @Test
    fun `each level supplies its own Source interface`() {
        val fourteen = loaderFor(TestArtifacts.abiJar("1.4"))
        val sixteen = loaderFor(TestArtifacts.abiJar("1.6"))
        try {
            val fromFourteen = fourteen.loadClass("eu.kanade.tachiyomi.source.Source")
            val fromSixteen = sixteen.loadClass("eu.kanade.tachiyomi.source.Source")

            assertNotSame(fromFourteen, fromSixteen, "the two ABI levels must not share one Source class")
            assertTrue(fromFourteen.isInterface && fromSixteen.isInterface)
        } finally {
            fourteen.close()
            sixteen.close()
        }
    }

    @Test
    fun `the two levels really do disagree about the browse surface`() {
        val fourteen = loaderFor(TestArtifacts.abiJar("1.4"))
        val sixteen = loaderFor(TestArtifacts.abiJar("1.6"))
        try {
            // The browse entry points live on CatalogueSource at both levels, not on Source.
            val catalogue14 = fourteen.loadClass("eu.kanade.tachiyomi.source.CatalogueSource")
            val catalogue16 = sixteen.loadClass("eu.kanade.tachiyomi.source.CatalogueSource")

            // 1.4: browsing is RxJava only.
            val fetchPopular = catalogue14.methods.first { it.name == "fetchPopularManga" }
            assertEquals("rx.Observable", fetchPopular.returnType.name)
            assertEquals(1, fetchPopular.parameterCount)
            assertTrue(
                catalogue14.methods.none { it.name == "getPopularManga" },
                "ABI 1.4 has no suspend browse entry point",
            )

            // 1.6: browsing is suspend. On the JVM a suspend function returns Object and takes a
            // trailing Continuation, which is how the difference is visible from reflection.
            val getPopular = catalogue16.methods.first { it.name == "getPopularManga" }
            assertEquals(Object::class.java, getPopular.returnType)
            assertEquals(2, getPopular.parameterCount)
            assertEquals("kotlin.coroutines.Continuation", getPopular.parameterTypes.last().name)
            assertTrue(catalogue16.methods.any { it.name == "getMangaUpdate" })

            // 1.6 keeps the RxJava browse methods as deprecated shims, because published 1.6
            // extensions still override them. They are the shim, not the contract: the 1.6 bridge
            // never calls them, and a 1.4 extension has to supply them itself.
            val shim = catalogue16.methods.first { it.name == "fetchPopularManga" }
            assertEquals("rx.Observable", shim.returnType.name)
            assertTrue(shim.isAnnotationPresent(Deprecated::class.java))
            assertTrue(
                catalogue14.methods.first { it.name == "fetchPopularManga" }
                    .isAnnotationPresent(Deprecated::class.java).not(),
                "the same method is the contract at 1.4 and a shim at 1.6",
            )

            // 1.6 sources carry a structured memo, 1.4 ones do not.
            val smanga14 = fourteen.loadClass("eu.kanade.tachiyomi.source.model.SManga")
            val smanga16 = sixteen.loadClass("eu.kanade.tachiyomi.source.model.SManga")
            assertTrue(smanga14.methods.none { it.name == "getMemo" })
            assertTrue(smanga16.methods.any { it.name == "getMemo" })

            // 1.6 has a type only it can name, which a 1.4-only host could not have stubbed.
            sixteen.loadClass("eu.kanade.tachiyomi.source.model.SMangaUpdate")
            assertFailsWith<ClassNotFoundException> {
                fourteen.loadClass("eu.kanade.tachiyomi.source.model.SMangaUpdate")
            }
        } finally {
            fourteen.close()
            sixteen.close()
        }
    }

    @Test
    fun `the host facing interface stays one single class across classloaders`() {
        val fourteen = loaderFor(TestArtifacts.abiJar("1.4"))
        val sixteen = loaderFor(TestArtifacts.abiJar("1.6"))
        try {
            // This is the whole reason the bridge can be cast across the classloader boundary.
            assertSame(
                SourceProvider::class.java,
                fourteen.loadClass("app.shura.source.api.SourceProvider"),
            )
            assertSame(
                SourceProvider::class.java,
                sixteen.loadClass("app.shura.source.api.SourceProvider"),
            )
        } finally {
            fourteen.close()
            sixteen.close()
        }
    }

    @Test
    fun `the bridge is loaded from the extension side, not the host`() {
        val loader = loaderFor(TestArtifacts.abiJar("1.6"), TestArtifacts.extension("shura-ext-tachiyomix-abi16"))
        try {
            val bridge = loader.loadClass(AbiRegistry.BRIDGE_1_6)
            val provider = loader.loadClass("app.shura.source.api.SourceProvider")
            assertTrue(provider.isAssignableFrom(bridge), "the bridge has to implement the host interface")
            // The bridge's own parameter type has to be this classloader's CatalogueSource, which
            // is only true if the child first delegation supplied it.
            val catalogueInThisLoader = loader.loadClass("eu.kanade.tachiyomi.source.CatalogueSource")
            val constructor = bridge.declaredConstructors.single()
            assertEquals(catalogueInThisLoader, constructor.parameterTypes.first())
            // 1.6 is suspend throughout, so its bridge takes no dispatcher to hand over.
            assertEquals(2, constructor.parameterCount)
        } finally {
            loader.close()
        }
    }

    @Test
    fun `a class the extension does not ship still falls through to the parent`() {
        val loader = loaderFor(TestArtifacts.abiJar("1.4"))
        try {
            // rx.Observable is supplied by the host, never packaged into an extension or an ABI jar.
            assertSame(rx.Observable::class.java, loader.loadClass("rx.Observable"))
        } finally {
            loader.close()
        }
    }

    @Test
    fun `a loader without the matching ABI jar cannot resolve the surface at all`() {
        val loader = loaderFor(TestArtifacts.extension("shura-ext-tachiyomix-abi16"))
        try {
            assertFailsWith<ClassNotFoundException> {
                loader.loadClass("eu.kanade.tachiyomi.source.Source")
            }
        } finally {
            loader.close()
        }
    }
}
