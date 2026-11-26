package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The insight is a nicety beside a bottle the user already owns, so the parser's job is to keep
 * what arrived and drop what did not — never to fail loudly over one odd field.
 */
class WineInsightTest {

    private val answer = """
        {
          "headline": "A cool-climate Mencía that tastes of slate and red fruit.",
          "why_it_tastes_this_way": [
            { "claim": "cool Atlantic nights keep acidity high, so the fruit stays lifted", "basis": "region", "confidence": "high" },
            { "claim": "old vines give concentration", "basis": "producer", "confidence": "medium" },
            { "claim": "2018 was a wet year", "basis": "vintage", "confidence": "low" }
          ],
          "tasting_profile": { "body": "medium", "tannin": "low", "acidity": "high", "notes": ["cherry", "slate", "herbs"] },
          "drinking_window": { "from": 2026, "to": 2032, "confidence": "medium" },
          "food_pairings": ["grilled lamb", "mushrooms"],
          "for_you": "It is the lifted red you keep asking for."
        }
    """.trimIndent()

    @Test
    fun aFullAnswerIsReadFieldByField() {
        val insight = InsightParsing.parse(answer)!!
        assertEquals("A cool-climate Mencía that tastes of slate and red fruit.", insight.headline)
        assertEquals(3, insight.why.size)
        assertEquals("region", insight.why[0].basis)
        assertEquals("medium body · low tannin · high acidity", insight.taste?.summary)
        assertEquals(listOf("cherry", "slate", "herbs"), insight.taste?.notes)
        assertEquals("2026–2032", insight.window?.label)
        assertEquals("medium", insight.window?.confidence)
        assertEquals(listOf("grilled lamb", "mushrooms"), insight.pairings)
        assertEquals("It is the lifted red you keep asking for.", insight.forYou)
    }

    @Test
    fun aFencedAnswerWithASentenceInFrontIsStillRead() {
        val chatty = "Here is the insight you asked for:\n```json\n$answer\n```\nHope that helps."
        assertEquals("A cool-climate Mencía that tastes of slate and red fruit.", InsightParsing.parse(chatty)?.headline)
    }

    @Test
    fun aReplyWithNoAnswerInItIsNotAnInsight() {
        assertNull(InsightParsing.parse(null))
        assertNull(InsightParsing.parse(""))
        assertNull(InsightParsing.parse("I'm sorry, I can't help with that."))
        // JSON, but with nothing in it: an object shell is not an explanation.
        assertNull(InsightParsing.parse("""{"ok": true}"""))
    }

    @Test
    fun aHighClaimIsPlainAMediumOneIsHedgedAndALowOneIsDropped() {
        val claims = InsightParsing.parse(answer)!!.visibleClaims()
        assertEquals(2, claims.size)
        assertEquals("cool Atlantic nights keep acidity high, so the fruit stays lifted", claims[0])
        assertEquals("Typically, old vines give concentration", claims[1])
        assertTrue(claims.none { it.contains("wet year") })
    }

    @Test
    fun aHedgeIsNotWrittenTwice() {
        val already = """
            {"headline":"x","why_it_tastes_this_way":[
              {"claim":"Typically, Rioja ages in American oak","basis":"region","confidence":"medium"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("Typically, Rioja ages in American oak"),
            InsightParsing.parse(already)!!.visibleClaims()
        )
    }

    @Test
    fun anImpossibleYearDoesNotSurviveAsAWindow() {
        val wrong = """
            {"headline":"x","drinking_window":{"from":750,"to":2032,"confidence":"low"}}
        """.trimIndent()
        assertEquals("until 2032", InsightParsing.parse(wrong)?.window?.label)
    }

    @Test
    fun theSchemaKeysAreTheOnlyDialect() {
        // The prompt asks for the schema's names and the stored answer is written with them, so a
        // reply using the Kotlin field names is a malformed answer, not a second format to support:
        // its reasons are not read. (The page offers to ask again rather than showing half of it.)
        val short = """
            {"headline":"x","why":[{"claim":"old vines give concentration","basis":"producer","confidence":"high"}],
             "taste":{"body":"full","tannin":"high","acidity":"low","notes":["fig"]}}
        """.trimIndent()
        val insight = InsightParsing.parse(short)!!
        assertTrue(insight.why.isEmpty())
        assertNull(insight.taste)
    }

    @Test
    fun aStoredInsightReadsBackAsTheAnswerItWas() {
        // The record keeps the answer as JSON; parsing what we wrote has to give the same fields.
        val first = InsightParsing.parse(answer)!!
        val stored = kotlinx.serialization.json.Json {
            encodeDefaults = true
        }.encodeToString(WineInsight.serializer(), first)
        val again = InsightParsing.parse(stored)!!
        assertEquals(first, again)
    }

    @Test
    fun aNullForYouIsNothingRatherThanTheWordNull() {
        val none = """{"headline":"x","for_you":null}""".trimIndent()
        assertNull(InsightParsing.parse(none)?.forYou)
        val quoted = """{"headline":"x","for_you":"null"}""".trimIndent()
        assertNull(InsightParsing.parse(quoted)?.forYou)
    }
}
