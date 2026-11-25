package com.vinoteca.app.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** What we currently believe about one provider+model pair. */
data class Quota(
    val tokensRemaining: Int? = null,
    val requestsRemaining: Int? = null,
    val windowResetAt: Long = 0L,
    val blockedUntil: Long = 0L
)

/**
 * Paces requests. Two modes, because providers differ:
 *
 *  - **Reported budget.** Groq sends `x-ratelimit-remaining-*` on every response, so when those
 *    headers are present they are used exactly: spend the real remaining tokens, wait for the real
 *    reset.
 *  - **Configured pace.** Gemini (and any custom endpoint) reports nothing useful, so each provider
 *    carries a requests-per-minute setting and requests are simply spaced at least that far apart.
 *
 * Nothing here assumes a provider's headers exist.
 */
class RateLimiter {

    private val mutex = Mutex()
    private val _quota = MutableStateFlow<Map<String, Quota>>(emptyMap())
    val quota: StateFlow<Map<String, Quota>> = _quota.asStateFlow()
    private val lastUse = ConcurrentHashMap<String, Long>()

    /** Suspends until [key] (provider:model) can take a request of [estimatedTokens]. */
    suspend fun awaitBudget(
        key: String,
        estimatedTokens: Int,
        minIntervalMs: Long,
        onWait: ((Long) -> Unit)? = null
    ) {
        while (true) {
            val waitMs = mutex.withLock {
                val now = System.currentTimeMillis()
                val current = _quota.value[key] ?: Quota()
                val live = if (current.windowResetAt in 1..now) {
                    current.copy(tokensRemaining = null, requestsRemaining = null, windowResetAt = 0L)
                } else {
                    current
                }
                if (live != current) _quota.value = _quota.value + (key to live)

                val spaced = if (live.tokensRemaining == null && live.requestsRemaining == null) {
                    val last = lastUse[key] ?: 0L
                    (last + minIntervalMs - now).coerceAtLeast(0L)
                } else {
                    0L
                }
                when {
                    live.blockedUntil > now -> live.blockedUntil - now
                    spaced > 0L -> spaced
                    live.tokensRemaining != null && live.tokensRemaining < estimatedTokens ->
                        (live.windowResetAt - now).coerceAtLeast(1_000L)
                    live.requestsRemaining != null && live.requestsRemaining <= 0 ->
                        (live.windowResetAt - now).coerceAtLeast(1_000L)
                    else -> 0L
                }
            }
            if (waitMs <= 0L) {
                lastUse[key] = System.currentTimeMillis()
                return
            }
            onWait?.invoke(waitMs)
            delay(waitMs.coerceIn(250L, 120_000L))
        }
    }

    /** Records the headers of a successful response; an empty map just stamps the request time. */
    fun record(key: String, headers: Map<String, String>) {
        val now = System.currentTimeMillis()
        val tokens = headers["x-ratelimit-remaining-tokens"]?.toDoubleOrNull()?.toInt()
        val requests = headers["x-ratelimit-remaining-requests"]?.toDoubleOrNull()?.toInt()
        val reset = parseDurationMillis(headers["x-ratelimit-reset-tokens"])
            ?: parseDurationMillis(headers["x-ratelimit-reset-requests"])
        _quota.value = _quota.value + (
            key to Quota(
                tokensRemaining = tokens,
                requestsRemaining = requests,
                windowResetAt = if (reset != null && reset > 0) now + reset else 0L,
                blockedUntil = 0L
            )
            )
    }

    /** Records a 429: nothing more goes to this key until the wait is over. */
    fun penalise(key: String, retryAfterMs: Long?) {
        val now = System.currentTimeMillis()
        val wait = (retryAfterMs ?: 20_000L).coerceIn(1_000L, 300_000L)
        _quota.value = _quota.value + (
            key to Quota(
                tokensRemaining = null,
                requestsRemaining = 0,
                windowResetAt = now + wait,
                blockedUntil = now + wait
            )
            )
    }

    companion object {

        /** Groq bills each image at a fixed 2,048 tokens; other providers bill far less. */
        const val IMAGE_TOKENS = 2048

        /** Roughly four characters to a token for English prompts. */
        fun textTokens(text: String): Int = (text.length / 4) + 8

        /** Input estimate plus the output the request is allowed to produce. */
        fun estimateTokens(promptChars: Int, images: Int, maxTokens: Int): Int =
            (promptChars / 4) + (images * IMAGE_TOKENS) + maxTokens

        /** "7.66s", "1m2.4s", "350ms", "22" — providers mix formatted durations and plain seconds. */
        fun parseDurationMillis(raw: String?): Long? {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isEmpty()) return null
            if (value.all { it.isDigit() }) {
                return value.toLongOrNull()?.times(1000)
            }
            var total = 0.0
            var found = false
            Regex("([0-9]+(?:\\.[0-9]+)?)(ms|h|m|s)").findAll(value).forEach { match ->
                val amount = match.groupValues[1].toDoubleOrNull() ?: return@forEach
                found = true
                total += when (match.groupValues[2]) {
                    "ms" -> amount
                    "s" -> amount * 1000
                    "m" -> amount * 60_000
                    else -> amount * 3_600_000
                }
            }
            return if (found) total.toLong() else null
        }
    }
}