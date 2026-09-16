package com.ym.lite.core

import java.time.*
import kotlin.math.max
import kotlin.random.Random

data class PlanSlot(val instant: Instant, val mediaIndex: Int)

object PlanEngine {
    fun generate(
        zoneId: ZoneId,
        firstDay: LocalDate,
        days: Int,
        dailyCount: Int,
        startMinutes: Int,
        endMinutes: Int,
        mediaCount: Int,
        seed: Long,
    ): List<PlanSlot> {
        require(days in 1..90)
        require(dailyCount in 1..20)
        require(mediaCount > 0)
        require(startMinutes in 0..1439 && endMinutes in 0..1439)

        val orderedMedia = (0 until mediaCount).shuffled(Random(seed))
        val out = ArrayList<PlanSlot>(days * dailyCount)
        var globalIndex = 0

        repeat(days) { dayOffset ->
            val day = firstDay.plusDays(dayOffset.toLong())
            val windowStart = day.atStartOfDay(zoneId).plusMinutes(startMinutes.toLong())
            var windowEnd = day.atStartOfDay(zoneId).plusMinutes(endMinutes.toLong())
            if (!windowEnd.isAfter(windowStart)) windowEnd = windowEnd.plusDays(1)
            val durationMinutes = Duration.between(windowStart, windowEnd).toMinutes()
            val rng = Random(seed xor day.toEpochDay())

            repeat(dailyCount) { slot ->
                val center = ((slot + 0.5) * durationMinutes / dailyCount).toLong()
                val segment = max(1L, durationMinutes / dailyCount)
                val jitterRange = max(0L, segment / 5)
                val jitter = if (jitterRange > 0) rng.nextLong(-jitterRange, jitterRange + 1) else 0L
                val minute = (center + jitter).coerceIn(0, max(0L, durationMinutes - 1))
                val mediaIndex = orderedMedia[globalIndex % orderedMedia.size]
                out += PlanSlot(windowStart.plusMinutes(minute).toInstant(), mediaIndex)
                globalIndex++
            }
        }
        return out.sortedBy { it.instant }
    }

    fun parseClock(text: String): Int {
        val parts = text.trim().split(":")
        require(parts.size == 2)
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        require(h in 0..23 && m in 0..59)
        return h * 60 + m
    }
}
