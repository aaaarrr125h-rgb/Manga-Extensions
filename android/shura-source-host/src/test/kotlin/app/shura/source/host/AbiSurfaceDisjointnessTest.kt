package app.shura.source.host

import org.junit.jupiter.api.Test
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two ABI jars must stay genuinely incompatible with each other.
 *
 * This is the assumption the whole classloader design is built on, so it is checked rather than
 * assumed. Both surfaces declare `eu.kanade.tachiyomi.source.Source` and about two dozen other
 * identical names, and every one of them differs: 1.4 browses with RxJava, 1.6 browses with
 * `suspend`, and 1.6 adds a method 1.4 does not have. If that ever stops being true the two jars
 * could be merged into one, and until then the per extension classloader is not just tidiness —
 * it is the only thing that lets a device hold both at once.
 *
 * It also fails if someone "fixes" the duplication by deleting one level's copy of a class,
 * which is the mistake this test exists to catch.
 */
class AbiSurfaceDisjointnessTest {

    private fun classBytes(jar: String): Map<String, ByteArray> =
        ZipFile(TestArtifacts.abiJar(jar)).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } }
        }

    @Test
    fun `the two levels share class names but never share an implementation`() {
        val fourteen = classBytes("1.4")
        val sixteen = classBytes("1.6")

        val shared = fourteen.keys intersect sixteen.keys
        val identical = shared.filter { fourteen.getValue(it).contentEquals(sixteen.getValue(it)) }

        assertEquals(0, identical.size, "these classes are byte identical in both jars: $identical")
        assertTrue(
            shared.any { it == "eu/kanade/tachiyomi/source/Source.class" },
            "the surfaces are expected to collide on names; that is the case being defended against",
        )
        assertTrue(shared.size >= 20, "only ${shared.size} names collide, the surfaces have changed shape")
    }

    @Test
    fun `neither jar carries the other's bridge or the host API`() {
        val fourteen = classBytes("1.4").keys
        val sixteen = classBytes("1.6").keys

        assertTrue(fourteen.none { it.startsWith("app/shura/abi/v16/") }, "the 1.4 jar must not carry the 1.6 bridge")
        assertTrue(sixteen.none { it.startsWith("app/shura/abi/v14/") }, "the 1.6 jar must not carry the 1.4 bridge")
        assertTrue(
            fourteen.none { it.startsWith("app/shura/source/api/") },
            "app.shura.source.api is owned by the host and must never be packaged into an ABI jar",
        )
        assertTrue(sixteen.none { it.startsWith("app/shura/source/api/") })
    }

    @Test
    fun `each jar carries only the bridge of its own level`() {
        val fourteen = classBytes("1.4").keys
        val sixteen = classBytes("1.6").keys

        assertTrue(fourteen.any { it.startsWith("app/shura/abi/v14/") })
        assertTrue(sixteen.any { it.startsWith("app/shura/abi/v16/") })
    }
}
