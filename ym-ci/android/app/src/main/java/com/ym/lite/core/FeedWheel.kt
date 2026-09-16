package com.ym.lite.core

object FeedWheel {
    fun clampIndex(current: Int, size: Int): Int {
        if (size <= 0) return 0
        return current.coerceIn(0, size - 1)
    }

    fun nextIndex(current: Int, size: Int): Int {
        if (size <= 0) return 0
        return (clampIndex(current, size) + 1) % size
    }

    fun previousIndex(current: Int, size: Int): Int {
        if (size <= 0) return 0
        val safe = clampIndex(current, size)
        return if (safe == 0) size - 1 else safe - 1
    }

    fun normalizedIntervalSec(value: Int?): Int = (value ?: 8).coerceIn(3, 120)
}
