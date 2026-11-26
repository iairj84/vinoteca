package com.wineverdict.app.data

import com.wineverdict.app.ai.AiProvider
import java.time.YearMonth
import java.util.Locale

/**
 * The handful of numbers the spend meter keeps. SharedPreferences on the phone, a map in a test.
 */
interface SpendCounters {
    fun getLong(key: String, fallback: Long): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String, fallback: Int): Int
    fun putInt(key: String, value: Int)
}

/**
 * What this month has cost, counted from the token usage every provider reports back.
 *
 * The app used to have no idea what it spent: a request went out and the answer came back, and the
 * bill — small, but real on a paid key — was only visible in the provider's console. Providers
 * return `usage.prompt_tokens` and `usage.completion_tokens` on every answer, so the meter records
 * those against the model that answered, prices them at that model's published rate, and keeps the
 * total for the calendar month. Past the limit it refuses to send anything, which is the difference
 * between a budget and a hope.
 *
 * Money is kept in nano-dollars so the arithmetic stays whole: $0.25 per million tokens is exactly
 * 250 nano-dollars a token, and nothing drifts as calls accumulate.
 */
class SpendMeter(
    private val counters: SpendCounters,
    private val month: () -> YearMonth = { YearMonth.now() }
) {

    data class Total(
        val month: YearMonth,
        val spentNanos: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val limitNanos: Long
    ) {
        /** How much of the limit is gone. Zero, not full, when there is no limit to measure against. */
        val fraction: Float
            get() = if (limitNanos <= 0L) 0f else {
                (spentNanos.toDouble() / limitNanos).coerceIn(0.0, 1.0).toFloat()
            }

        /** Nothing more is sent: the month's money is gone. */
        val blocked: Boolean get() = limitNanos > 0L && spentNanos >= limitNanos

        /** At or past [WARN_PERCENT] of the limit, but not yet over it. */
        val warning: Boolean
            get() = !blocked && limitNanos > 0L &&
                spentNanos * 100L >= limitNanos * WARN_PERCENT

        val remainingNanos: Long get() = (limitNanos - spentNanos).coerceAtLeast(0L)

        val spent: String get() = dollars(spentNanos)
        val limit: String get() = dollars(limitNanos)
        val remaining: String get() = dollars(remainingNanos)
        val percent: Int get() = (fraction * 100).toInt()
    }

    /** The monthly cap, in cents. Zero means no cap. */
    var limitCents: Int
        get() = counters.getInt(KEY_LIMIT_CENTS, DEFAULT_LIMIT_CENTS).coerceIn(0, MAX_LIMIT_CENTS)
        set(value) = counters.putInt(KEY_LIMIT_CENTS, value.coerceIn(0, MAX_LIMIT_CENTS))

    fun total(): Total {
        val current = month()
        return Total(
            month = current,
            spentNanos = counters.getLong(key(current, "nanos"), 0L),
            inputTokens = counters.getLong(key(current, "in"), 0L),
            outputTokens = counters.getLong(key(current, "out"), 0L),
            limitNanos = limitCents.toLong() * NANOS_PER_CENT
        )
    }

    /**
     * Adds one answered request. A provider that reports nothing is not guessed at: without real
     * counts there is nothing honest to add, and a wrong number here would either stop the user
     * early or let the month overspend.
     */
    fun add(provider: AiProvider, model: String, inputTokens: Int, outputTokens: Int) {
        if (inputTokens <= 0 && outputTokens <= 0) return
        val current = month()
        val cost = inputTokens.toLong() * TokenPrices.input(provider, model) +
            outputTokens.toLong() * TokenPrices.output(provider, model)
        if (cost <= 0L) {
            // A free model still counts its tokens, so the screen can say what the free tier did.
            counters.putLong(key(current, "in"), counters.getLong(key(current, "in"), 0L) + inputTokens)
            counters.putLong(key(current, "out"), counters.getLong(key(current, "out"), 0L) + outputTokens)
            return
        }
        counters.putLong(key(current, "nanos"), counters.getLong(key(current, "nanos"), 0L) + cost)
        counters.putLong(key(current, "in"), counters.getLong(key(current, "in"), 0L) + inputTokens)
        counters.putLong(key(current, "out"), counters.getLong(key(current, "out"), 0L) + outputTokens)
    }

    private fun key(month: YearMonth, slot: String): String = "$month.$slot"

    companion object {
        const val DEFAULT_LIMIT_CENTS = 100
        const val WARN_PERCENT = 80L
        const val MAX_LIMIT_CENTS = 100_000

        private const val NANOS_PER_CENT = 10_000_000L
        private const val KEY_LIMIT_CENTS = "spend.limit.cents"

        /** "$0.42", or three decimals while it is still under a dime. */
        fun dollars(nanos: Long): String {
            val value = nanos / 1_000_000_000.0
            val format = if (value >= 0.10 || value <= 0.0) "%.2f" else "%.3f"
            return "$" + String.format(Locale.US, format, value)
        }
    }
}

/**
 * Published prices, as nano-dollars per token (a price per million tokens, times a thousand).
 *
 * Only the models that cost money are listed. A `:free` model is free on any provider, and Groq's
 * free tier is free by definition; a paid Groq account would be under-counted here, which is why
 * the meter says "estimated" rather than promising a bill.
 */
object TokenPrices {

    private data class Price(val prefix: String, val input: Long, val output: Long)

    // Gemini, November 2026. Flash-Lite first: "gemini-3.1-flash" would otherwise match its lite
    // sibling, and the app's own default is the lite model.
    private val gemini = listOf(
        Price("gemini-3.1-flash-lite", 250, 1_500),
        Price("gemini-3.5-flash-lite", 300, 2_500),
        Price("gemini-2.5-flash-lite", 100, 400),
        Price("gemini-3-flash-lite", 250, 1_500),
        Price("gemini-2.5-flash", 300, 2_500),
        Price("gemini-3-flash", 500, 3_000),
        Price("gemini-3.1-flash", 500, 3_000),
        Price("gemini-3-pro", 2_000, 12_000),
        Price("gemini-2.5-pro", 1_250, 10_000)
    )

    /** What an unpriced Gemini model is assumed to cost: the Flash-Lite class, the app's default. */
    private val geminiFallback = Price("", 250, 1_500)

    fun input(provider: AiProvider, model: String): Long = price(provider, model).first

    fun output(provider: AiProvider, model: String): Long = price(provider, model).second

    private fun price(provider: AiProvider, model: String): Pair<Long, Long> {
        val id = model.trim().lowercase().substringAfterLast('/')
        if (id.contains(":free") || id.startsWith("free")) return 0L to 0L
        return when (provider) {
            AiProvider.GEMINI -> (gemini.firstOrNull { id.startsWith(it.prefix) } ?: geminiFallback)
                .let { it.input to it.output }

            // Groq and OpenRouter: the free tiers this app documents. Nothing is billed, so
            // nothing is counted against the cap.
            AiProvider.GROQ, AiProvider.OPENROUTER -> 0L to 0L
        }
    }
}
