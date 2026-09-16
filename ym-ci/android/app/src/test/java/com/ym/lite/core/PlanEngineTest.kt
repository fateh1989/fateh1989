package com.ym.lite.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanEngineTest {
    @Test fun deterministicAndSized() {
        val a = PlanEngine.generate(ZoneId.of("Europe/Stockholm"), LocalDate.of(2026,9,17), 30, 2, 18*60, 23*60, 7, 42)
        val b = PlanEngine.generate(ZoneId.of("Europe/Stockholm"), LocalDate.of(2026,9,17), 30, 2, 18*60, 23*60, 7, 42)
        assertEquals(60, a.size)
        assertEquals(a, b)
    }

    @Test fun noImmediateRepeatWhenPoolHasMoreThanOne() {
        val plan = PlanEngine.generate(ZoneId.of("UTC"), LocalDate.of(2026,9,17), 4, 2, 8*60, 20*60, 4, 9)
        for (i in 1 until plan.size) assertTrue(plan[i].mediaIndex != plan[i-1].mediaIndex)
    }

    @Test fun overnightWindowWorks() {
        val plan = PlanEngine.generate(ZoneId.of("UTC"), LocalDate.of(2026,9,17), 1, 3, 22*60, 2*60, 3, 7)
        assertEquals(3, plan.size)
        assertTrue(plan.zipWithNext().all { it.first.instant < it.second.instant })
    }
}
