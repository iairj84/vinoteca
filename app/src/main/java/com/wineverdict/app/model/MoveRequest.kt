package com.wineverdict.app.model

import kotlinx.serialization.Serializable

/**
 * A move the chat asked for, kept as a query instead of a list of bottles.
 *
 * The chat is handed a digest of the cellar that is capped well below the whole shelf — 55 lines on
 * the thin budget, 140 on the wide one — so a list of names the model wrote would silently miss every
 * bottle it never saw. A query is resolved by the app against every bottle on the shelf at the moment
 * the card is drawn, which is also what makes the card honest: it shows what will move, not what a
 * model remembered.
 *
 * Everything is optional except the place. A request with no match field at all resolves to nothing,
 * so a careless "move everything" cannot pour the whole cellar into one location.
 *
 * `producer` and `name` both search the producer and the cuvée: a scanned bottle's two name halves
 * are swapped often enough that splitting them would drop the bottles the asker meant. `region` and
 * `appellation` likewise read each other's fields.
 */
@Serializable
data class MoveRequest(
    val location: String = "",
    val slot: String = "",
    val grape: String = "",
    val type: String = "",
    val producer: String = "",
    val name: String = "",
    val region: String = "",
    val country: String = "",
    val appellation: String = "",
    val vintageFrom: Int? = null,
    val vintageTo: Int? = null
)
