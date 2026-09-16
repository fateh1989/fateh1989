package com.ym.lite.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object SigningInfo {
    data class Fingerprints(val md5: String, val sha256: String)

    fun fingerprints(context: Context): Fingerprints {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: error("Signing info missing")
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signatures.firstOrNull()?.toByteArray() ?: error("Signing certificate missing")
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: error("Signing certificate missing")
        }

        return Fingerprints(md5 = digest("MD5", bytes), sha256 = digest("SHA-256", bytes))
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm)
            .digest(bytes)
            .joinToString(":") { "%02X".format(it) }
}
