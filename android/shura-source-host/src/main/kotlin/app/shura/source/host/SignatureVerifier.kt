package app.shura.source.host

import java.io.File
import java.security.MessageDigest

/**
 * The published contract, restated here so the code that depends on it can be read next to it.
 *
 * `repo/index.json` carries a single top level `"signingKey"` field. That value is the SHA-256
 * digest of the *signing certificate* of the APKs this repository publishes -- 64 characters of
 * lower case hexadecimal, and nothing else matches. It is not a hash of any file in the index, it
 * is not a jar hash, and it is not the index's own checksum: there is no per extension digest in
 * the index at all, so the certificate is the whole trust anchor.
 *
 * The consequence drives the shape of this file. A host cannot confirm an extension by comparing
 * bytes it was handed, so it must read the certificate out of the APK it downloaded and compare
 * that. [ExtensionSignatureVerifier] does exactly this, and the platform specific part -- getting
 * bytes out of an APK's signing block -- is isolated behind [ApkInspector] so that it
 * can be tested without a device.
 */
object SigningFingerprint {

    private const val LENGTH = 64
    private val HEX = Regex("^[0-9a-f]{64}$")

    /**
     * The SHA-256 digest of a DER encoded certificate, as lower case hexadecimal.
     */
    fun ofCertificate(der: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(der)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * Normalises a published fingerprint, or returns null if it is not one.
     *
     * Surrounding whitespace and upper case are tolerated because a value that differs only in case
     * or spacing is the same certificate, and rejecting it would fail a legitimate repository for a
     * cosmetic reason. Everything else is refused: a value of the wrong shape matches no
     * certificate at all, so treating it as a wildcard would be a silent trust hole.
     */
    fun normaliseOrNull(raw: String?): String? = raw
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.length == LENGTH && HEX.matches(it) }

    /**
     * Normalises a published fingerprint or fails.
     *
     * @throws RepositoryIntegrityException if [raw] is not a SHA-256 digest.
     */
    fun require(raw: String?, source: String): String = normaliseOrNull(raw)
        ?: throw RepositoryIntegrityException(
            source,
            "signingKey is not a 64 character lower case SHA-256 digest",
        )
}

/**
 * Decides whether a downloaded APK is the one this repository says it is.
 *
 * The check is deliberately the narrow one the repository can support: read the APK's signing
 * certificate, hash it, and require that hash to equal the repository's `signingKey`. An APK that
 * fails is not installed, not registered, and does not disturb the version that was already
 * installed.
 *
 * Note what this does *not* claim. It does not prove the APK came from the repository -- anyone who
 * can serve a response can serve an APK, and only the certificate identity is checked -- and it
 * does not prove the contents are benign. It proves the key material matches the key the repository
 * publishes, which is the whole trust anchor the index format offers.
 */
class ExtensionSignatureVerifier(
    private val inspector: ApkInspector,
) {

    /**
     * Verifies [apk] against [expectedFingerprint] and returns the fingerprint that was found.
     *
     * @throws ExtensionVerificationException if the certificate cannot be read, if it is not a
     * SHA-256 shaped digest, or if it does not equal [expectedFingerprint].
     */
    fun verify(apk: File, expectedFingerprint: String, packageName: String = apk.name): String {
        val expected = SigningFingerprint.normaliseOrNull(expectedFingerprint)
            ?: throw ExtensionVerificationException(
                packageName,
                "repository signingKey is not a SHA-256 digest, refusing to install",
            )
        if (!apk.isFile) {
            throw ExtensionVerificationException(packageName, "downloaded file is missing: ${apk.name}")
        }
        val der = try {
            inspector.certificateOf(apk)
        } catch (e: ExtensionVerificationException) {
            throw e
        } catch (e: RuntimeException) {
            throw ExtensionVerificationException(
                packageName,
                "cannot read signing certificate: ${e.message}",
            )
        }
        if (der.isEmpty()) {
            throw ExtensionVerificationException(packageName, "signing certificate is empty")
        }
        val actual = SigningFingerprint.ofCertificate(der)
        if (actual != expected) {
            throw ExtensionVerificationException(
                packageName,
                "signed by $actual, repository publishes $expected",
            )
        }
        return actual
    }
}