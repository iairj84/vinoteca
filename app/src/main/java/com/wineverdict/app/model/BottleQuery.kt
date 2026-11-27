package com.wineverdict.app.model

import kotlinx.serialization.Serializable

/**
 * The facts a chat proposal may select bottles by.
 *
 * One vocabulary for both kinds of proposal. A move and a field change scope the bottles the same
 * way, so there is one set of keys for the model to learn and one set of rules for the matcher to
 * enforce — the previous shape had a query for moves only, and a clause the keys could not carry was
 * quietly dropped.
 */
interface BottleQuery {
    val grape: String
    val type: String
    val producer: String
    val name: String
    val region: String
    val country: String
    val appellation: String
    /** Where the bottles live now; "unassigned" and its synonyms mean the ones with no place. */
    val fromLocation: String
    val vintageFrom: Int?
    val vintageTo: Int?
}

/**
 * A change to a bottle's own fields, carried as a query plus the new values.
 *
 * Null means "leave this field alone": a value the model did not mention must never be written,
 * because every key it leaves out is a field somebody owns. A blank [slot] is the exception — it is
 * present on purpose, and clears the shelf label.
 *
 * [note] is appended to the bottle's note as a dated line, the way the detail page's tasting note
 * does it, rather than replacing what is already written there.
 */
@Serializable
data class BottleUpdate(
    override val grape: String = "",
    override val type: String = "",
    override val producer: String = "",
    override val name: String = "",
    override val region: String = "",
    override val country: String = "",
    override val appellation: String = "",
    override val fromLocation: String = "",
    override val vintageFrom: Int? = null,
    override val vintageTo: Int? = null,
    val slot: String? = null,
    val price: Double? = null,
    val note: String? = null,
    val quantity: Int? = null,
    val vintage: Int? = null
) : BottleQuery {
    /** Whether the proposal changes anything at all. */
    val hasChange: Boolean
        get() = slot != null || price != null || note != null || quantity != null || vintage != null
}