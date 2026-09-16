package com.ym.lite.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationGateTest {
    @Test fun allowsOnlyOfficialTikTokPackages() {
        assertTrue(AutomationGate.isTikTokPackage("com.zhiliaoapp.musically"))
        assertTrue(AutomationGate.isTikTokPackage("com.ss.android.ugc.trill"))
        assertFalse(AutomationGate.isTikTokPackage("com.openai.chatgpt"))
        assertFalse(AutomationGate.isTikTokPackage("com.android.launcher"))
        assertFalse(AutomationGate.isTikTokPackage(null))
    }

    @Test fun automationFailsClosedOutsideTikTok() {
        assertTrue(AutomationGate.mayAutomate(true, "com.zhiliaoapp.musically"))
        assertFalse(AutomationGate.mayAutomate(false, "com.zhiliaoapp.musically"))
        assertFalse(AutomationGate.mayAutomate(true, "com.openai.chatgpt"))
        assertFalse(AutomationGate.mayAutomate(true, null))
    }
}
