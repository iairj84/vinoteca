package com.wineverdict.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The circle a place claims by default: about fifty yards. */
const val PLACE_RADIUS_M = 45

/** The narrowest circle worth having, and the widest that is still "a place". */
const val PLACE_MIN_RADIUS_M = 15
const val PLACE_MAX_RADIUS_M = 500

/**
 * A place the user named once and the app can recognise again: Home, a friend's house, the bar
 * around the corner.
 *
 * It exists because a geocoder cannot name a home — asked where a house is, every provider answers
 * with a street number. A place is the answer to "I am here, and here is called this", kept on the
 * phone with the circle it occupies, so the next night inside that circle is tagged without a
 * lookup and without typing.
 */
@Serializable
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** How far from the pin still counts as being there. */
    val radiusM: Int = PLACE_RADIUS_M
)

/** A point on the earth, in degrees. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Great-circle distance in metres.
 *
 * Haversine rather than a flat approximation: at forty-five metres the difference would not matter,
 * but the same function measures a place's radius and the distance shown beside it, and a formula
 * that is wrong by a few per cent at latitude is wrong in the one number the user chose.
 */
fun metresBetween(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val earth = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLng = Math.toRadians(bLng - aLng)
    val lat1 = Math.toRadians(aLat)
    val lat2 = Math.toRadians(bLat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * earth * asin(min(1.0, sqrt(h)))
}

/** Whether this point is inside the place's circle. */
fun Place.covers(lat: Double, lng: Double): Boolean =
    metresBetween(lat, lng, this.lat, this.lng) <= radiusM.toDouble()

/**
 * The saved place containing this point, the nearest one when circles overlap.
 *
 * Nearest rather than first: a flat inside a building and the bar downstairs can both claim a fix,
 * and the smaller circle is the truer answer only when the fix is inside it — which the distance
 * already says. Ties fall to the tighter radius, because that is the one that meant it.
 */
fun nearestPlace(places: List<Place>, lat: Double, lng: Double): Place? =
    places.asSequence()
        .filter { it.covers(lat, lng) }
        .map { it to metresBetween(lat, lng, it.lat, it.lng) }
        .minWithOrNull(
            compareBy<Pair<Place, Double>> { it.second }.thenBy { it.first.radiusM }
        )
        ?.first

/** "45 m", "1.2 km" — the radius as it is spoken rather than as it is stored. */
fun radiusWords(radiusM: Int): String {
    if (radiusM < 1000) return "$radiusM m"
    val km = radiusM / 1000.0
    return String.format(Locale.US, "%.1f km", km)
}

/** The radiuses the picker offers, and the one it starts on. */
val PLACE_RADIUS_CHOICES = listOf(25, 45, 75, 150, 300)

/**
 * The places on this phone, written whole like the cellar.
 *
 * A separate file from the drinks because it is a different kind of fact: a night happened once, a
 * place is somewhere the user goes. Losing the book's places would only cost retyping, so the file
 * is written through [SafeFile] and travels in the backup beside the nights.
 */
class PlaceStore(context: Context) {

    private val file = File(context.filesDir, "places.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    fun load() {
        val loaded = SafeFile.read(file) { text -> json.decodeFromString<List<Place>>(text) }
        if (loaded != null) _places.value = ordered(loaded)
    }

    /**
     * Adds a place, or replaces the one with the same id. A blank name is refused rather than
     * stored: an unnamed circle can never be chosen from a list, so it would only sit there.
     */
    fun save(place: Place): Place? {
        val name = place.name.trim()
        if (name.isBlank()) return null
        val clean = place.copy(
            name = name,
            lat = place.lat.coerceIn(-90.0, 90.0),
            lng = place.lng.coerceIn(-180.0, 180.0),
            radiusM = place.radiusM.coerceIn(PLACE_MIN_RADIUS_M, PLACE_MAX_RADIUS_M)
        )
        val existing = _places.value.any { it.id == clean.id }
        write(
            if (existing) _places.value.map { if (it.id == clean.id) clean else it }
            else _places.value + clean
        )
        return clean
    }

    fun remove(id: String) {
        write(_places.value.filterNot { it.id == id })
    }

    /** Adds a backup's places that this phone does not already hold, by id. */
    fun merge(others: List<Place>) {
        if (others.isEmpty()) return
        val byId = LinkedHashMap<String, Place>()
        _places.value.forEach { byId[it.id] = it }
        others.forEach { byId.putIfAbsent(it.id, it) }
        write(byId.values.toList())
    }

    /** Replaces every place with a backup's, for a restore that replaces the cellar too. */
    fun replaceAll(others: List<Place>) {
        write(others)
    }

    private fun write(next: List<Place>) {
        val clean = ordered(next)
        _places.value = clean
        SafeFile.write(file, json.encodeToString(clean))
    }

    private fun ordered(list: List<Place>): List<Place> =
        list.filter { it.name.isNotBlank() }.sortedBy { it.name.lowercase() }
}

/** Metres as words, rounded the way a person says a distance. */
fun metresWords(metres: Double): String =
    if (metres < 1000) "${metres.roundToInt()} m"
    else String.format(Locale.US, "%.1f km", metres / 1000.0)
