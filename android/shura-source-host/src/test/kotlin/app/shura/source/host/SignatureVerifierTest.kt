package app.shura.source.host

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningFingerprintTest {

    @Test
    fun `a 64 character lower case digest is accepted`() {
        val digest = "a".repeat(64)
        assertEquals(digest, SigningFingerprint.normaliseOrNull(digest))
    }

    @Test
    fun `upper case is normalised rather than refused`() {
        // A value that differs only in case names the same certificate. Refusing it would fail a
        // legitimate repository for a cosmetic reason.
        assertEquals("ab".repeat(32), SigningFingerprint.normaliseOrNull("AB".repeat(32)))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("ab".repeat(32), SigningFingerprint.normaliseOrNull("  ${"ab".repeat(32)}\n"))
    }

    @Test
    fun `anything that is not a sha-256 digest is refused`() {
        // Each of these would match no certificate at all, so accepting one would be a trust hole
        // rather than a lenient parse.
        listOf(
            null,
            "",
            "not-a-digest",
            "0".repeat(63),
            "0".repeat(65),
            "g".repeat(64),
            "0".repeat(32),
            // The published key with a trailing space is deliberately NOT in this list: surrounding
            // whitespace is trimmed, so it is accepted. That case has its own test above.
        ).forEach { candidate ->
            assertNull(SigningFingerprint.normaliseOrNull(candidate), "should have refused '$candidate'")
        }
    }

    @Test
    fun `the real published signing key is a well formed digest`() {
        val published = SigningFingerprint.normaliseOrNull(TestArtifacts.repoIndex.readText().let { text ->
            RepositoryIndexParser.parse(text).signingKey
        })
        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            published,
            "the key this repository actually publishes must satisfy the client side rule",
        )
    }

    @Test
    fun `a certificate digests to its own sha-256`() {
        val der = "a certificate would be bytes".toByteArray()
        val digest = SigningFingerprint.ofCertificate(der)
        val expected = MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        assertEquals(expected, digest)
        assertEquals(digest, SigningFingerprint.normaliseOrNull(digest))
    }

    @Test
    fun `two different certificates digest differently`() {
        assertNotEquals(
            SigningFingerprint.ofCertificate("first".toByteArray()),
            SigningFingerprint.ofCertificate("second".toByteArray()),
        )
    }

    @Test
    fun `require names the source that published the bad value`() {
        val failure = assertFailsWith<RepositoryIntegrityException> {
            SigningFingerprint.require("nope", "https://shura.example/index.json")
        }
        assertContains(failure.message.orEmpty(), "https://shura.example/index.json")
    }
}

class ExtensionSignatureVerifierTest {

    private val certificate = "the real signing certificate would be bytes".toByteArray()
    private val fingerprint = SigningFingerprint.ofCertificate(certificate)
    private val apk = File("tachiyomix.apk").also { it.writeBytes(ByteArray(16)) }

    private fun verifierFor(der: ByteArray) =
        ExtensionSignatureVerifier(FixedApkInspector(der))

    @Test
    fun `a matching certificate passes and reports the fingerprint it found`() {
        val verified = verifierFor(certificate).verify(apk, fingerprint, "eu.kanade.tachiyomi.extension.all.tachiyomix")
        assertEquals(fingerprint, verified)
    }

    @Test
    fun `a certificate that is not the published one is refused`() {
        val failure = assertFailsWith<ExtensionVerificationException> {
            verifierFor("a different certificate".toByteArray())
                .verify(apk, fingerprint, "eu.kanade.tachiyomi.extension.all.tachiyomix")
        }
        assertContains(failure.message.orEmpty(), "repository publishes $fingerprint")
    }

    @Test
    fun `a refusal names both digests so the mismatch is diagnosable`() {
        val other = SigningFingerprint.ofCertificate("another certificate".toByteArray())
        val failure = assertFailsWith<ExtensionVerificationException> {
            verifierFor(certificate).verify(apk, other, "pkg")
        }
        assertContains(failure.message.orEmpty(), fingerprint)
        assertContains(failure.message.orEmpty(), other)
    }

    @Test
    fun `a repository key that is not a digest is refused before any certificate is read`() {
        // Nothing is installed when the repository cannot describe its own verification: the check
        // is on the metadata, not on the bytes.
        val verifier = verifierFor(certificate)
        val failure = assertFailsWith<ExtensionVerificationException> { verifier.verify(apk, "nope", "pkg") }
        assertContains(failure.message.orEmpty(), "repository signingKey is not a SHA-256 digest")
    }

    @Test
    fun `an unreadable certificate becomes a verification refusal`() {
        val throwing = ExtensionSignatureVerifier(
            FixedApkInspector(ByteArray(0), failure = { IllegalStateException("no signing block") }),
        )
        val failure = assertFailsWith<ExtensionVerificationException> { throwing.verify(apk, fingerprint, "pkg") }
        assertContains(failure.message.orEmpty(), "no signing block")
    }

    @Test
    fun `a missing artifact is refused rather than treated as empty`() {
        val failure = assertFailsWith<ExtensionVerificationException> {
            verifierFor(certificate).verify(File("no-such-file.apk"), fingerprint, "pkg")
        }
        assertContains(failure.message.orEmpty(), "missing")
    }

    @Test
    fun `an unsigned apk is refused`() {
        val unsigned = File("unsigned.apk").also { it.writeBytes(ByteArray(4)) }
        val failure = assertFailsWith<ExtensionVerificationException> {
            verifierFor(ByteArray(0)).verify(unsigned, fingerprint, "pkg")
        }
        assertTrue(failure.message.orEmpty().isNotEmpty())
    }
}