package com.ym.lite.core

object AutomationGate {
    private val tikTokPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    fun isTikTokPackage(packageName: String?): Boolean =
        packageName != null && packageName in tikTokPackages

    fun mayAutomate(enabled: Boolean, foregroundPackage: String?): Boolean =
        enabled && isTikTokPackage(foregroundPackage)
}
