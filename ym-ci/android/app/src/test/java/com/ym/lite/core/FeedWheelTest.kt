package com.ym.lite.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedWheelTest {
    @Test fun wrapsForwardAndBackward() {
        assertEquals(0, FeedWheel.nextIndex(2, 3))
        assertEquals(2, FeedWheel.previousIndex(0, 3))
    }

    @Test fun clampsInvalidIndex() {
        assertEquals(0, FeedWheel.clampIndex(-5, 3))
        assertEquals(2, FeedWheel.clampIndex(9, 3))
        assertEquals(0, FeedWheel.clampIndex(9, 0))
    }

    @Test fun intervalIsBounded() {
        assertEquals(3, FeedWheel.normalizedIntervalSec(1))
        assertEquals(8, FeedWheel.normalizedIntervalSec(null))
        assertEquals(120, FeedWheel.normalizedIntervalSec(999))
    }
}
