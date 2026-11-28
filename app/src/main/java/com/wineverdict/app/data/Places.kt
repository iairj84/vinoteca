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

/**
 * Metres are what is measured and stored; yards and miles are what is said.
 *
 * The circles below are the round numbers a person picks — twenty-five yards to five hundred — kept
 * to the nearest metre so a stored radius is one integer and the backup format does not move.
 */
private const val METRES_PER_YARD = 0.9144
private const val METRES_PER_MILE = 1609.344

/** Fifty yards: the circle a house claims by default. */
const val PLACE_RADIUS_M = 46

/** Fifteen yards — a driveway — and five hundred, which is as wide as a place is. */
const val PLACE_MIN_RADIUS_M = 14
const val PLACE_MAX_RADIUS_M = 457

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

/** "50 yd", "0.6 mi" — the radius as it is spoken rather than as it is stored. */
fun radiusWords(radiusM: Int): String = distanceWords(radiusM.toDouble())

/**
 * Yards up to half a mile, then miles with one decimal.
 *
 * Yards are rounded to five, because a circle is drawn by eye: forty-nine and fifty are the same
 * circle, and a picker that said "49 yd" would be pretending to a precision nobody chose. An older
 * place stored in metres reads just as naturally — 150 m is 165 yd.
 */
fun distanceWords(metres: Double): String {
    val yards = metres / METRES_PER_YARD
    if (yards < 880) return "${(yards / 5).roundToInt() * 5} yd"
    return String.format(Locale.US, "%.1f mi", metres / METRES_PER_MILE)
}

/** The circles the picker offers: 25, 50, 100, 200 and 500 yards, to the nearest metre. */
val PLACE_RADIUS_CHOICES = listOf(23, 46, 91, 183, 457)

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


