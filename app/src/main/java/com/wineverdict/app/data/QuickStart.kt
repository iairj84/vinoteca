package com.wineverdict.app.data

/**
 * The quick start: ten taps, no typing, every question skippable.
 *
 * These collect axes and constraints, not reasons. A tap cannot explain *why* a bottle worked, so a
 * tap must never write a rule the assistant will then defend — only [PalateRules] promotes an answer
 * into a checkable gate, and only for the two questions that ask for one (sweetness and what to
 * avoid).
 */
data class QuickStartOption(
    val id: String,
    val label: String,
    val note: String = ""
)

data class QuickStartQuestion(
    val id: String,
    val prompt: String,
    val help: String = "",
    val options: List<QuickStartOption>,
    val multi: Boolean = false,
    /** Asked after the three picks, once the user has already seen the value. */
    val afterPicks: Boolean = false
)

object QuickStart {

    // Q1 — what ends up in the glass
    const val Q_POURS = "q1_pours"
    const val POUR_RED = "red"
    const val POUR_WHITE = "white"
    const val POUR_ROSE = "rose"
    const val POUR_SPARKLING = "sparkling"
    const val POUR_DESSERT = "dessert"
    const val POUR_EVERYTHING = "everything"

    // Q2 — which axis is doing the work
    const val Q_STYLE = "q2_style"
    const val STYLE_CRISP = "crisp"
    const val STYLE_SMOOTH = "smooth"
    const val STYLE_BIG = "big"
    const val STYLE_SWEET = "a_little_sweet"
    const val STYLE_UNSURE = "unsure"

    // Q3 — sweetness
    const val Q_SWEET = "q3_sweet"
    const val SWEET_NONE = "none"
    const val SWEET_TOUCH = "touch"
    const val SWEET_SWEETER = "sweeter"

    // Q4 — budget
    const val Q_BUDGET = "q4_budget"
    const val BUDGET_UNDER_12 = "under_12"
    const val BUDGET_12_20 = "12_20"
    const val BUDGET_20_35 = "20_35"
    const val BUDGET_35_UP = "35_up"

    // Q5 — what lands, and therefore what the answer should lead with
    const val Q_LANDS = "q5_lands"
    const val LANDS_STORY = "story"
    const val LANDS_VALUE = "value"
    const val LANDS_UNUSUAL = "unusual"
    const val LANDS_SIMPLE = "simple"

    // Q6 — who it is for
    const val Q_AUDIENCE = "q6_audience"
    const val AUDIENCE_JUST_ME = "just_me"
    const val AUDIENCE_PLUS_ONE = "plus_one"
    const val AUDIENCE_GIFTS = "gifts"

    // Q7 — what to avoid (multi)
    const val Q_AVOID = "q7_avoid"
    const val AVOID_STRONG = "strong"
    const val AVOID_OAK = "oaky"
    const val AVOID_SWEET = "sweet"
    const val AVOID_NOTHING = "nothing"

    // Q8 — dish context
    const val Q_CONTEXT = "q8_context"
    const val CTX_WEEKNIGHT = "weeknight"
    const val CTX_WEEKEND = "weekend"
    const val CTX_TAKEOUT = "takeout"
    const val CTX_RESTAURANT = "restaurant"
    const val CTX_ALONE = "alone"

    // Q9 — adventure
    const val Q_ADVENTURE = "q9_adventure"
    const val ADVENTURE_FAMILIAR = "familiar"
    const val ADVENTURE_SIMILAR = "similar"
    const val ADVENTURE_SURPRISE = "surprise"

    // Q10 — repeats
    const val Q_REPEATS = "q10_repeats"
    const val REPEATS_NEVER = "never"
    const val REPEATS_FINE = "fine"

    val questions: List<QuickStartQuestion> = listOf(
        QuickStartQuestion(
            id = Q_POURS,
            prompt = "What ends up in your glass?",
            help = "This only sets what I show you first. It never stops me suggesting anything.",
            options = listOf(
                QuickStartOption(POUR_RED, "Red"),
                QuickStartOption(POUR_WHITE, "White"),
                QuickStartOption(POUR_ROSE, "Rosé"),
                QuickStartOption(POUR_SPARKLING, "Sparkling"),
                QuickStartOption(POUR_DESSERT, "Sweet or dessert"),
                QuickStartOption(POUR_EVERYTHING, "A bit of everything")
            )
        ),
        QuickStartQuestion(
            id = Q_STYLE,
            prompt = "When a wine is right, it's usually…",
            help = "Pick the one that sounds most like a good night. \"Not sure\" is a real answer.",
            options = listOf(
                QuickStartOption(STYLE_CRISP, "Crisp and fresh"),
                QuickStartOption(STYLE_SMOOTH, "Smooth and mellow"),
                QuickStartOption(STYLE_BIG, "Big and bold"),
                QuickStartOption(STYLE_SWEET, "A little sweet"),
                QuickStartOption(STYLE_UNSURE, "Honestly, not sure")
            )
        ),
        QuickStartQuestion(
            id = Q_SWEET,
            prompt = "How do you feel about sweetness in a wine?",
            options = listOf(
                QuickStartOption(SWEET_NONE, "None, please"),
                QuickStartOption(SWEET_TOUCH, "A touch is fine"),
                QuickStartOption(SWEET_SWEETER, "I like it sweeter")
            )
        ),
        QuickStartQuestion(
            id = Q_BUDGET,
            prompt = "What's a normal bottle cost you?",
            options = listOf(
                QuickStartOption(BUDGET_UNDER_12, "Under \$12"),
                QuickStartOption(BUDGET_12_20, "\$12–20"),
                QuickStartOption(BUDGET_20_35, "\$20–35"),
                QuickStartOption(BUDGET_35_UP, "\$35 and up")
            )
        ),
        QuickStartQuestion(
            id = Q_LANDS,
            prompt = "When a bottle really lands, what do you tell people about it?",
            help = "This changes how I answer, not what I recommend.",
            options = listOf(
                QuickStartOption(LANDS_STORY, "The story behind it"),
                QuickStartOption(LANDS_VALUE, "How little I paid"),
                QuickStartOption(LANDS_UNUSUAL, "How unusual it was"),
                QuickStartOption(LANDS_SIMPLE, "That it just worked, no drama")
            )
        ),
        QuickStartQuestion(
            id = Q_AUDIENCE,
            prompt = "Who is it usually for?",
            options = listOf(
                QuickStartOption(AUDIENCE_JUST_ME, "Just me"),
                QuickStartOption(AUDIENCE_PLUS_ONE, "Me and someone else"),
                QuickStartOption(AUDIENCE_GIFTS, "Gifts and hosting")
            )
        ),
        QuickStartQuestion(
            id = Q_AVOID,
            prompt = "Anything you'd rather avoid?",
            help = "Pick as many as you like. This one becomes a real rule.",
            options = listOf(
                QuickStartOption(AVOID_STRONG, "Strong, high-alcohol wines"),
                QuickStartOption(AVOID_OAK, "Heavy and oaky"),
                QuickStartOption(AVOID_SWEET, "Sweet"),
                QuickStartOption(AVOID_NOTHING, "Nothing in particular")
            ),
            multi = true
        ),
        QuickStartQuestion(
            id = Q_CONTEXT,
            prompt = "Most of your wine goes with…",
            help = "I'll keep the pairing rules to these and drop the rest.",
            options = listOf(
                QuickStartOption(CTX_WEEKNIGHT, "Weeknight dinner at home"),
                QuickStartOption(CTX_WEEKEND, "Weekend cooking"),
                QuickStartOption(CTX_TAKEOUT, "Takeout or spicy"),
                QuickStartOption(CTX_RESTAURANT, "Restaurants"),
                QuickStartOption(CTX_ALONE, "On its own")
            ),
            afterPicks = true
        ),
        QuickStartQuestion(
            id = Q_ADVENTURE,
            prompt = "Pick a bottle…",
            options = listOf(
                QuickStartOption(ADVENTURE_FAMILIAR, "Only what I already know"),
                QuickStartOption(ADVENTURE_SIMILAR, "Something similar, different producer"),
                QuickStartOption(ADVENTURE_SURPRISE, "Surprise me")
            ),
            afterPicks = true
        ),
        QuickStartQuestion(
            id = Q_REPEATS,
            prompt = "Same bottle twice in a month?",
            options = listOf(
                QuickStartOption(REPEATS_NEVER, "Never — I want variety"),
                QuickStartOption(REPEATS_FINE, "Fine if it's the best answer")
            ),
            afterPicks = true
        )
    )

    fun question(id: String): QuickStartQuestion? = questions.firstOrNull { it.id == id }

    /** The steps in the order the screens appear: seven taps, the shortlist, three more taps, the file. */
    fun steps(): List<QuickStartQuestion> =
        questions.filter { !it.afterPicks } + questions.filter { it.afterPicks }
}