package com.ym.lite.automation

object TikTokScope {
    private val allowedPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    fun isAllowed(packageName: CharSequence?): Boolean {
        return packageName?.toString() in allowedPackages
    }
}
