package com.wineverdict.app.data

import com.wineverdict.app.ai.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * The cap is the only thing standing between a batch scan and a real bill, so these tests pin the
 * arithmetic it depends on: the published price per model, what 80% means, where "blocked" starts,
 * and that a new month starts from zero without losing the old one.
 */
class SpendMeterTest {

    private class Memory : SpendCounters {
        val longs = mutableMapOf<String, Long>()
        val ints = mutableMapOf<String, Int>()
        override fun getLong(key: String, fallback: Long): Long = longs[key] ?: fallback
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }

        override fun getInt(key: String, fallback: Int): Int = ints[key] ?: fallback
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }
    }

    private var now = YearMonth.of(2026, 11)
    private val counters = Memory()
    private val meter = SpendMeter(counters) { now }

    @Test
    fun `flash-lite is billed at its published rate`() {
        // $0.25 in and $1.50 out per million: 10,000 tokens in is 2.5 million nanos,
        // 1,000 out is 1.5 million, so four tenths of a cent.
        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 10_000, 1_000)
        val total = meter.total()
        assertEquals(4_000_000L, total.spentNanos)
        assertEquals(10_000L, total.inputTokens)
        assertEquals(1_000L, total.outputTokens)
        assertEquals("$0.004", total.spent)
    }

    @Test
    fun `the lite price wins over its non-lite sibling`() {
        // "gemini-3.1-flash-lite" starts with "gemini-3.1-flash", so the table order matters:
        // billing the lite model at the full model's rate would overstate a scan sixfold.
        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 1_000_000, 0)
        assertEquals(250_000_000L, meter.total().spentNanos)
    }

    @Test
    fun `eighty percent is a warning and the limit is a stop`() {
        meter.limitCents = 100
        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 3_200_000, 0) // $0.80
        assertTrue(meter.total().warning)
        assertFalse(meter.total().blocked)

        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 800_000, 0) // the other $0.20
        assertFalse(meter.total().warning)
        assertTrue(meter.total().blocked)
        assertEquals(0L, meter.total().remainingNanos)
        assertEquals(100, meter.total().percent)
    }

    @Test
    fun `a zero limit never blocks`() {
        meter.limitCents = 0
        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 900_000_000, 1_000_000)
        assertFalse(meter.total().blocked)
        assertFalse(meter.total().warning)
        assertEquals(0f, meter.total().fraction)
    }

    @Test
    fun `a new month starts at zero and keeps the old one`() {
        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 10_000, 1_000)
        now = YearMonth.of(2026, 12)
        assertEquals(0L, meter.total().spentNanos)
        assertEquals(0L, meter.total().inputTokens)
        assertEquals(4_000_000L, counters.longs["2026-11.nanos"])
        meter.add(AiProvider.GEMINI, "gemini-3.1-flash-lite", 10_000, 0)
        assertEquals(2_500_000L, meter.total().spentNanos)
    }

    @Test
    fun `free models cost nothing but still count their tokens`() {
        meter.add(AiProvider.OPENROUTER, "nvidia/nemotron-3.5-super-vl:free", 8_000, 400)
        meter.add(AiProvider.GROQ, "openai/gpt-oss-120b", 2_000, 200)
        val total = meter.total()
        assertEquals(0L, total.spentNanos)
        assertEquals(10_000L, total.inputTokens)
        assertEquals(600L, total.outputTokens)
    }

    @Test
    fun `a model missing from the table is priced as flash-lite`() {
        meter.add(AiProvider.GEMINI, "gemini-9-experimental", 1_000_000, 0)
        assertEquals(250_000_000L, meter.total().spentNanos)
    }

    @Test
    fun `a limit set by the user survives and is clamped`() {
        meter.limitCents = 250
        assertEquals(250, meter.limitCents)
        meter.limitCents = -5
        assertEquals(0, meter.limitCents)
    }

    @Test
    fun `dollars read at a useful precision`() {
        assertEquals("$0.00", SpendMeter.dollars(0))
        assertEquals("$0.004", SpendMeter.dollars(4_000_000))
        assertEquals("$0.42", SpendMeter.dollars(420_000_000))
        assertEquals("$1.00", SpendMeter.dollars(1_000_000_000))
    }
}
