package com.wineverdict.app.data

import android.content.Context
import java.util.Locale

/**
 * The region this phone can honestly report about itself.
 *
 * A question asks for "Portland", and the map knows a dozen Portlands. Something has to settle it,
 * and the only honest source is the phone: where it is, where its owner said home is, or — failing
 * both — how it is set up. What it must never do is pick the first search hit and call that settled.
 *
 * [state] is the part that can choose between two American towns; [country] can only rule countries
 * out. A phone that knows only its country is still useful — "Springfield" has twelve American
 * answers but "Vancouver" has two, one in Canada — and the answer says which of the two it knows.
 */
data class Region(
    val city: String = "",
    val state: String = "",
    val country: String = "",
    val countryCode: String = "",
    /** How it knows, said to the user: "your location", "your Home place", "your phone's region". */
    val source: String = ""
) {
    val known: Boolean get() = city.isNotBlank() || state.isNotBlank() || country.isNotBlank()

    /** "Oregon", "Oregon, United States", "the United States" — what a reply can print. */
    val words: String
        get() = when {
            state.isNotBlank() && country.isNotBlank() -> "$state, $country"
            state.isNotBlank() -> state
            country.isNotBlank() -> country
            else -> ""
        }
}

/**
 * Where the phone says it is, in the order that is most true.
 *
 * 1. **A fresh fix**, reverse-geocoded: this is where the user is standing, which is what "tonight"
 *    means. It needs the location permission the app already asks for elsewhere, and no fix in the
 *    budget is not an error — it is the next source's turn.
 * 2. **The saved place called Home**, reverse-geocoded: a user who drew a circle around their house
 *    has already answered "which region am I in" better than the locale can.
 * 3. **The phone's locale region**: the country only, and said as such. It settles a town that is
 *    unique in its country and honestly fails on one that is not.
 *
 * Null-free: an empty [Region] means the phone knows nothing, and the caller says so rather than
 * guessing a state.
 */
object DeviceRegions {

    suspend fun of(context: Context, finder: PlaceFinder): Region {
        WhereAmI.fix(context)?.let { fix ->
            finder.townAt(fix.lat, fix.lng)?.let { hit ->
                if (hit.city.isNotBlank() || hit.region.isNotBlank()) {
                    return from(hit, "your location")
                }
            }
        }
        finder.savedPlaces()
            .firstOrNull { Search.fold(it.name) == "home" }
            ?.let { home ->
                finder.townAt(home.lat, home.lng)?.let { hit ->
                    if (hit.city.isNotBlank() || hit.region.isNotBlank()) {
                        return from(hit, "your Home place")
                    }
                }
            }
        val locale = Locale.getDefault()
        if (locale.country.isNotBlank()) {
            return Region(
                country = locale.getDisplayCountry(Locale.ENGLISH).ifBlank { locale.country },
                countryCode = locale.country,
                source = "your phone's region"
            )
        }
        return Region()
    }

    private fun from(hit: PlaceHit, source: String): Region = Region(
        city = hit.city.trim(),
        state = hit.region.trim(),
        country = "",
        countryCode = "",
        source = source
    )
}
