package com.wineverdict.app.data

/**
 * The dish classes the app ships with. This table is a constant, not something the model writes: the
 * wins and ruins columns are stable knowledge, and a fixed table removes a whole class of invention
 * from the pairing answer. Only the classes the user actually cooks are written into their file.
 */
data class DishClass(
    val id: String,
    val label: String,
    val demands: String,
    val wins: String,
    val ruins: String
)

object PairingTable {

    val all: List<DishClass> = listOf(
        DishClass(
            id = "tomato_beef",
            label = "Tomato + beef",
            demands = "Acid and herbs to meet the tomato",
            wins = "Cabernet Franc, Sangiovese, Mencía",
            ruins = "Young tannic Cabernet, jammy Zinfandel"
        ),
        DishClass(
            id = "roast_chicken",
            label = "Roast chicken",
            demands = "Almost nothing — the most forgiving dish",
            wins = "Pinot with grip, unoaked Chardonnay",
            ruins = "Very tannic reds"
        ),
        DishClass(
            id = "mushrooms",
            label = "Mushrooms",
            demands = "Earth and acid",
            wins = "Pinot Noir, Nebbiolo, Mencía",
            ruins = "Aromatic whites, heavy oak"
        ),
        DishClass(
            id = "grilled",
            label = "Grilled or charred meat",
            demands = "Fat and char to absorb tannin",
            wins = "Cool-site Syrah, structured Cabernet",
            ruins = "Delicate high-acid whites"
        ),
        DishClass(
            id = "spicy",
            label = "Spicy or takeout",
            demands = "A little sweetness and lower alcohol to meet the heat",
            wins = "Off-dry Riesling, Gewürztraminer, Beaujolais",
            ruins = "High-alcohol tannic reds"
        ),
        DishClass(
            id = "cream",
            label = "Cream or butter sauces",
            demands = "Acid to cut the fat",
            wins = "Chablis, dry Chenin, Champagne",
            ruins = "Heavy oaked whites"
        ),
        DishClass(
            id = "hard_cheese",
            label = "Hard cheese",
            demands = "Salt and fat want grip and acid",
            wins = "Structured reds, dry sherry, aged whites",
            ruins = "Delicate light reds"
        ),
        DishClass(
            id = "charcuterie",
            label = "Charcuterie",
            demands = "Salt and fat, nothing heavy",
            wins = "Cru Beaujolais, dry rosé, Champagne",
            ruins = "Big tannic reds"
        )
    )

    fun byId(id: String): DishClass? = all.firstOrNull { it.id == id }

    /**
     * The dish a standing food-context answer implies. This is the fallback when the question
     * itself names no food: the answer says what most wine goes with, so most nights there is a
     * plate, and the gate may use that allowance. "On its own" and an unanswered question name no
     * dish, so they give none rather than inventing one.
     */
    fun dishFor(contextId: String?): Dish? = when (contextId) {
        QuickStart.CTX_WEEKNIGHT -> Dish("weeknight dinner at home")
        QuickStart.CTX_WEEKEND -> Dish("weekend cooking")
        QuickStart.CTX_TAKEOUT -> Dish("takeout or something spicy", spicy = true)
        QuickStart.CTX_RESTAURANT -> Dish("eating out")
        else -> null
    }

    /** Which classes a quick-start context implies. Empty means "no dish data", never "everything". */
    fun forContext(contextId: String?): List<DishClass> {
        val ids = when (contextId) {
            QuickStart.CTX_WEEKNIGHT -> listOf("roast_chicken", "mushrooms")
            QuickStart.CTX_WEEKEND -> listOf("grilled", "tomato_beef")
            QuickStart.CTX_TAKEOUT -> listOf("spicy", "tomato_beef")
            QuickStart.CTX_RESTAURANT -> listOf("hard_cheese", "charcuterie")
            else -> emptyList()
        }
        return ids.mapNotNull { byId(it) }
    }

    fun markdown(classes: List<DishClass>): String = buildString {
        appendLine("| Dish | What it demands | Wins | Ruins it |")
        appendLine("|---|---|---|---|")
        classes.forEach { dish ->
            appendLine("| ${dish.label} | ${dish.demands} | ${dish.wins} | ${dish.ruins} |")
        }
    }.trimEnd()

    /** One row per class, for the summary on the Taste screen. */
    fun summary(classes: List<DishClass>): String =
        classes.joinToString(" · ") { "${it.label}: ${it.wins}" }
}