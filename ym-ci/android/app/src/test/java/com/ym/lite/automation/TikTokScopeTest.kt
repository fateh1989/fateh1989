package com.ym.lite.automation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TikTokScopeTest {
    @Test
    fun acceptsMainTikTokPackagesOnly() {
        assertTrue(TikTokScope.isAllowed("com.zhiliaoapp.musically"))
        assertTrue(TikTokScope.isAllowed("com.ss.android.ugc.trill"))

        assertFalse(TikTokScope.isAllowed("com.tiktok.lite.go"))
        assertFalse(TikTokScope.isAllowed("com.zhiliaoapp.musically.go"))
        assertFalse(TikTokScope.isAllowed("com.ym.lite.stable"))
        assertFalse(TikTokScope.isAllowed("com.openai.chatgpt"))
        assertFalse(TikTokScope.isAllowed("com.android.settings"))
        assertFalse(TikTokScope.isAllowed("com.google.android.youtube"))
        assertFalse(TikTokScope.isAllowed(null))
    }
}
