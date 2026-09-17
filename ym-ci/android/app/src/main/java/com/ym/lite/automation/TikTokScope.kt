package com.ym.lite.automation

object TikTokScope {
    val allowedPackages: List<String> = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.tiktok.lite.go",
        "com.zhiliaoapp.musically.go",
    )

    fun isAllowed(packageName: CharSequence?): Boolean {
        return packageName?.toString() in allowedPackages
    }
}
