package com.wineverdict.app.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the location icon found when it was tapped. */
sealed interface HereResult {
    /** A saved place's circle contains the fix: the answer, and no lookup is needed for it. */
    data class Saved(val place: Place) : HereResult

    /** A named business is close enough to be where the user is standing. */
    data class Business(val hit: PlaceHit) : HereResult

    /** Somewhere with no name: a house, a street, a field. */
    data object NothingKnown : HereResult

    /** No permission, location off, or no fix in time. */
    data object NoFix : HereResult
}

/** The platform's own geocoder, kept behind an interface so the fallback order can be tested. */
interface AddressFinder {
    suspend fun find(query: String, near: LatLng?): PlaceHit?
}

/**
 * The business lookup behind the typeahead and the location icon.
 *
 * An interface so the order of the fallbacks can be tested without a network: the rule — saved
 * places first, names second, address third, typing last — is the whole feature, and it should not
 * be provable only by holding a phone in a bar.
 */
interface PlaceLookup {
    suspend fun search(query: String, near: LatLng?, limit: Int = 5): List<PlaceHit>
    suspend fun reverse(lat: Double, lng: Double): PlaceHit?
}

/**
 * The order in which a place is looked for, in one place so the field and the location icon cannot
 * disagree about it.
 *
 * Saved places come before every lookup, and the location icon checks them before it asks anybody
 * anything: a geocoder cannot know that a house is called Home, so a circle the user drew is the
 * only thing that can answer for it. Everything after that is best-effort — Photon for the names
 * Google will not give, the platform geocoder for an address when Photon has nothing, and the typed
 * text if both are silent.
 */
class PlaceFinder(
    private val photon: PlaceLookup,
    private val addresses: AddressFinder,
    private val saved: () -> List<Place>
) {

    /**
     * What the dropdown offers for what has been typed so far: saved places first, because they are
     * already on the phone and mean exactly what the user meant, then Photon, then an address.
     */
    suspend fun suggestions(query: String, near: LatLng?): List<PlaceHit> {
        val typed = query.trim()
        if (typed.length < 2) return emptyList()
        val local = saved()
            .filter { Search.matches(listOf(it.name), typed) }
            .map { PlaceHit(name = it.name, lat = it.lat, lng = it.lng) }
        val found = runCatching { photon.search(typed, near) }.getOrDefault(emptyList())
        val fallback = if (found.isEmpty()) {
            runCatching { addresses.find(typed, near) }.getOrNull()?.let { listOf(it) }.orEmpty()
        } else {
            emptyList()
        }
        return (local + found + fallback)
            .distinctBy { it.label to it.street }
            .take(MAX_SUGGESTIONS)
    }

    /**
     * Where the user is, as the app can honestly name it.
     *
     * The saved circle first and nearest-first: standing in the bar downstairs from a flat whose
     * radius also reaches the pavement, the smaller circle wins — which is the one the user drew
     * about the bar.
     */
    suspend fun here(lat: Double?, lng: Double?): HereResult {
        if (lat == null || lng == null) return HereResult.NoFix
        nearestPlace(saved(), lat, lng)?.let { return HereResult.Saved(it) }
        val business = runCatching { photon.reverse(lat, lng) }.getOrNull()
        return if (business != null) HereResult.Business(business) else HereResult.NothingKnown
    }

    private companion object {
        /** Four rows is a dropdown; more is a wall over the night's own questions. */
        const val MAX_SUGGESTIONS = 4
    }
}

/**
 * The platform geocoder, which is free, keyless and on every phone — and which answers a restaurant
 * with its street address.
 *
 * That is still worth having as a fallback: an address pins the right building, and the user's own
 * words stay the label on the night. It is never the first choice, because a typeahead of addresses
 * cannot tell four Loyal Legions apart.
 */
class AndroidAddressFinder(private val context: Context) : AddressFinder {

    override suspend fun find(query: String, near: LatLng?): PlaceHit? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        val results = runCatching { geocoder.getFromLocationName(query.trim(), 3) }.getOrNull()
        val first = results?.firstOrNull() ?: return@withContext null
        val address = first.getAddressLine(0).orEmpty().trim()
        if (address.isBlank()) return@withContext null
        PlaceHit(
            name = address,
            // No town: the address already ends in it, and "— Modena" on top of "…Modena MO" reads
            // as a mistake rather than a distinction.
            city = "",
            street = address,
            lat = first.latitude,
            lng = first.longitude
        )
    }
}
