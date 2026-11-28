package com.wineverdict.app.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The evidence half of a place question, against the live search.
 *
 * Excluded from `:app:testDebugUnitTest` like the other live instruments (the class name starts
 * with `Live`), spends no model quota, and asserts nothing — it prints what discovery, the one
 * confirmation search per name and the closure filter actually produce. Run it when the search
 * endpoint's HTML changes, because that is what silently turns every place question into "I found
 * nothing":
 *
 *     ./gradlew :app:testDebugUnitTest -Plive --tests "*LivePlaceScoutTest"
 *
 * A keyless engine refuses a burst with an anomaly page, and this instrument asks four searches in
 * a row; "SEARCH BUSY" below is that refusal, not a defect.
 */
class LivePlaceScoutTest {

    @Test
    fun printWhatThePortlandQuestionFinds() = runBlocking {
        val parsed = PlaceScout.ask("Where should I go for dinner tonight in Portland with good wine?")
        if (parsed == null) {
            println("NOT RECOGNISED AS A PLACE QUESTION")
            return@runBlocking
        }
        // What the screen does when the phone is standing in the town it was asked about: the state
        // is appended, because "Portland" alone also means Portland, Maine.
        val ask = parsed.copy(where = "Portland, Oregon")
        val web = WebLookup()
        val hits = try {
            PlaceScout.discoveryQueries(ask).flatMap { query ->
                println("QUERY: $query")
                web.search(query)
            }
        } catch (e: Exception) {
            println("SEARCH BUSY: ${e.message}")
            return@runBlocking
        }
        val names = PlaceScout.candidates(hits, ask)
        println("hits=${hits.size} candidates=$names")
        val venues = names.take(PlaceScout.MAX_CANDIDATES).mapNotNull { name ->
            val own = try {
                web.search(PlaceScout.confirmQuery(name, ask))
            } catch (e: Exception) {
                println("SEARCH BUSY ON CONFIRM $name: ${e.message}")
                emptyList()
            }
            delay(200)
            PlaceScout.confirmed(name, own, ask)
        }
        val ranked = PlaceScout.ranked(venues)
        println("confirmed=${ranked.size}")
        ranked.forEach { venue ->
            println(
                "${venue.name} | closed=${venue.closed.ifBlank { "-" }} | " +
                    "wine=${venue.wine} | ${venue.host} | ${venue.snippet.take(90)}"
            )
        }
    }
}
