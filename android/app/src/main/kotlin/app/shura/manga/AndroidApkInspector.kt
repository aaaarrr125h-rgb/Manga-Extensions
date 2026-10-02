package app.shura.manga

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import app.shura.source.api.ExtensionAbi
import app.shura.source.host.ApkInspector
import app.shura.source.host.ExtensionLoadException
import app.shura.source.host.ExtensionManifest
import app.shura.source.host.ExtensionManifestParser
import app.shura.source.host.ExtensionVerificationException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * An [ApkInspector] that uses Android's [PackageManager] to read the signing certificate and the
 * extension manifest from a downloaded APK.
 *
 * This is the platform seam: the host's security check requires the certificate that the APK was
 * actually signed with, which is only available via `PackageManager.getPackageArchiveInfo` with the
 * `GET_SIGNING_CERTIFICATES` flag. The manifest is read from `AndroidManifest.xml` meta-data, which
 * matches how real published extensions declare themselves, not from the jar properties the fixtures
 * happen to carry.
 *
 * If the device does not support `getSigningInfo` (API < 28), the legacy `signatures` array is used,
 * and the signature bytes are converted to their DER representation so that the same SHA-256 digest
 * of the certificate is compared against the repository's `signingKey`. The digest is of the DER
 * certificate, never of the raw signature.
 */
class AndroidApkInspector(private val context: Context) : ApkInspector {

    override fun certificateOf(apk: File): ByteArray {
        if (!apk.isFile) {
            throw ExtensionVerificationException(apk.name, "downloaded file is missing: ${apk.name}")
        }
        val packageManager = context.packageManager
        val archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA

        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(archiveFlags.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
        }

        if (packageInfo == null) {
            throw ExtensionVerificationException(apk.name, "APK is not readable")
        }

        val der = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
                ?: throw ExtensionVerificationException(apk.name, "APK has no signing info")
            signingCertificateDer(signingInfo)
        } else {
            @Suppress("DEPRECATION")
            val signatures = packageInfo.signatures
                ?: throw ExtensionVerificationException(apk.name, "APK has no signatures")
            if (signatures.isEmpty()) {
                throw ExtensionVerificationException(apk.name, "APK has no signatures")
            }
            // On legacy API levels each signature is a `Signature` object whose `toByteArray()` is the
            // DER encoded X.509 certificate for that signer.
            signatureDer(signatures.first())
        }

        if (der.isEmpty()) {
            throw ExtensionVerificationException(apk.name, "signing certificate is empty")
        }
        return der
    }

    override fun manifestOf(apk: File): ExtensionManifest {
        if (!apk.isFile) {
            throw ExtensionLoadException("${apk.name} is missing")
        }
        val packageManager = context.packageManager
        val archiveFlags = PackageManager.GET_META_DATA

        @Suppress("DEPRECATION")
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(archiveFlags.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
        }

        if (packageInfo == null) {
            throw ExtensionLoadException("${apk.name} is not readable")
        }
        val metaData = packageInfo.applicationInfo?.metaData
            ?: throw ExtensionLoadException("${apk.name} has no meta-data in its AndroidManifest")

        return ExtensionManifestParser.fromMetaData(metaData)
    }

    /**
     * Returns the DER encoded certificate of the first signer from [SigningInfo].
     *
     * When multiple signers are present, only the first is used: the repository's `signingKey` is
     * the digest of a single signing certificate, which matches the contract the repository publishes.
     */
    private fun signingCertificateDer(signingInfo: SigningInfo): ByteArray {
        val signingCertificateHistory = signingInfo.signingCertificateHistory
        if (signingCertificateHistory.isNotEmpty()) {
            return signingCertificateHistory.first().toByteArray()
        }
        val apkContentsSigners = signingInfo.apkContentsSigners
        if (apkContentsSigners.isNotEmpty()) {
            return apkContentsSigners.first().toByteArray()
        }
        throw ExtensionVerificationException("", "APK has no signing certificates")
    }

    private fun signatureDer(signature: android.content.pm.Signature): ByteArray {
        return signature.toByteArray()
    }
}