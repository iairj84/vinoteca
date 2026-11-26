package com.wineverdict.app.data

/**
 * The quick start: ten taps, three optional lines, every question skippable.
 *
 * These collect axes and constraints, not reasons. A tap cannot explain *why* a bottle worked, so a
 * tap must never write a rule the assistant will then defend — only [PalateRules] promotes an answer
 * into a checkable gate, and only sweetness does ("none, please" or "a touch is fine"). The free-text
 * answers at the end are the one place a reason does come from, and they never become a rule: they
 * ride in the brief verbatim.
 */
data class QuickStartOption(
    val id: String,
    val label: String,
    val note: String = "",
    /** How it reads in the taste brief. Blank means app-only context, never sent to a model. */
    val brief: String = ""
)

data class QuickStartQuestion(
    val id: String,
    val prompt: String,
    val help: String = "",
    val options: List<QuickStartOption>,
    val multi: Boolean = false,
    /** Asked after the three picks, once the user has already seen the value. */
    val afterPicks: Boolean = false,
    /** The line this question renders as in the taste brief, e.g. "Grapes I love". */
    val briefLead: String = ""
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

    val baseline: List<QuickStartQuestion> = listOf(
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

    // ---- The optional interview --------------------------------------------------------------
    //
    // The ten taps collect axes and constraints; a restaurant list prints none of them. These ask
    // the things a sommelier asks before recommending — which grapes and places have actually
    // worked, how much grip and acid you want in the glass, whether you will wait for a bottle —
    // and every one is optional. Nothing here becomes a gate: a list can be answered from what is
    // printed on it, and these are the facts that let the answer be about the wine rather than
    // about the price. An unanswered question simply does not appear in the brief.

    const val X_GRAPES = "x_grapes"
    const val X_PLACES = "x_places"
    const val X_SKIP_GRAPES = "x_skip_grapes"
    const val X_TANNIN = "x_tannin"
    const val X_ACID = "x_acid"
    const val X_BODY = "x_body"
    const val X_AGING = "x_aging"
    const val X_FUNK = "x_funk"
    const val X_SPARKLING = "x_sparkling"
    const val X_RESTAURANT = "x_restaurant"
    const val X_OAK = "x_oak"
    const val X_PRICE = "x_price"

    /**
     * Asked after the baseline, one at a time, each skippable. Chips only: a tap cannot say why,
     * and a reason is what the three written lines are for.
     */
    val extras: List<QuickStartQuestion> = listOf(
        QuickStartQuestion(
            id = X_GRAPES,
            prompt = "Which grapes have actually worked for you?",
            help = "Pick what you recognise. A list names grapes more often than anything else, so this " +
                "is the single most useful answer here.",
            options = listOf(
                QuickStartOption("pinot", "Pinot Noir", brief = "Pinot Noir"),
                QuickStartOption("cabernet", "Cabernet and Merlot", brief = "Cabernet and Merlot"),
                QuickStartOption("syrah", "Syrah / Shiraz", brief = "Syrah"),
                QuickStartOption("grenache", "Grenache / Garnacha", brief = "Grenache"),
                QuickStartOption("sangiovese", "Sangiovese", brief = "Sangiovese"),
                QuickStartOption("nebbiolo", "Nebbiolo", brief = "Nebbiolo"),
                QuickStartOption("tempranillo", "Tempranillo", brief = "Tempranillo"),
                QuickStartOption("zinfandel", "Zinfandel / Primitivo", brief = "Zinfandel"),
                QuickStartOption("chardonnay", "Chardonnay", brief = "Chardonnay"),
                QuickStartOption("sauvignon", "Sauvignon Blanc", brief = "Sauvignon Blanc"),
                QuickStartOption("riesling", "Riesling", brief = "Riesling"),
                QuickStartOption("chenin", "Chenin Blanc", brief = "Chenin Blanc"),
                QuickStartOption("albarino", "Albariño", brief = "Albariño"),
                QuickStartOption("sparkling", "Champagne and sparkling", brief = "Champagne and sparkling"),
                QuickStartOption("unsure", "The names are still new to me")
            ),
            multi = true,
            briefLead = "Grapes I love"
        ),
        QuickStartQuestion(
            id = X_PLACES,
            prompt = "Any places that keep showing up in bottles you liked?",
            help = "Rough is fine. \"Spain\" and \"Bierzo\" both work.",
            options = listOf(
                QuickStartOption("bordeaux", "Bordeaux", brief = "Bordeaux"),
                QuickStartOption("burgundy", "Burgundy", brief = "Burgundy"),
                QuickStartOption("loire", "the Loire", brief = "the Loire"),
                QuickStartOption("rhone", "the Rhône", brief = "the Rhône"),
                QuickStartOption("alsace", "Alsace", brief = "Alsace"),
                QuickStartOption("italy", "Italy", brief = "Italy"),
                QuickStartOption("spain", "Spain", brief = "Spain"),
                QuickStartOption("germany", "Germany and Austria", brief = "Germany and Austria"),
                QuickStartOption("portugal", "Portugal", brief = "Portugal"),
                QuickStartOption("usa", "the US West Coast", brief = "the US West Coast"),
                QuickStartOption("australia", "Australia and New Zealand", brief = "Australia and New Zealand"),
                QuickStartOption("southafrica", "South Africa", brief = "South Africa"),
                QuickStartOption("southamerica", "Argentina and Chile", brief = "Argentina and Chile"),
                QuickStartOption("other", "Greece, Georgia, Armenia", brief = "Greece, Georgia, Armenia"),
                QuickStartOption("nopattern", "I have not noticed a pattern")
            ),
            multi = true,
            briefLead = "Places I have liked"
        ),
        QuickStartQuestion(
            id = X_SKIP_GRAPES,
            prompt = "Anything on a list that makes you skip it?",
            options = listOf(
                QuickStartOption("cabernet", "Cabernet", brief = "Cabernet"),
                QuickStartOption("merlot", "Merlot", brief = "Merlot"),
                QuickStartOption("chardonnay", "Chardonnay", brief = "Chardonnay"),
                QuickStartOption("sauvignon", "Sauvignon Blanc", brief = "Sauvignon Blanc"),
                QuickStartOption("pinotgrigio", "Pinot Grigio", brief = "Pinot Grigio"),
                QuickStartOption("bigreds", "High-alcohol reds", brief = "high-alcohol reds"),
                QuickStartOption("none", "Nothing — I will try anything")
            ),
            multi = true,
            briefLead = "Grapes I skip"
        ),
        QuickStartQuestion(
            id = X_TANNIN,
            prompt = "In a red, how much grip do you want?",
            options = listOf(
                QuickStartOption("grip", "Real grip — drying tannin is the point", brief = "real grip, drying tannin is the point"),
                QuickStartOption("some", "Some grip, not a wall", brief = "some grip, not a wall"),
                QuickStartOption("soft", "Soft and round only", brief = "soft and round only")
            ),
            briefLead = "Tannin"
        ),
        QuickStartQuestion(
            id = X_ACID,
            prompt = "And acidity?",
            options = listOf(
                QuickStartOption("zippy", "Zippy — I want it fresh", brief = "zippy, fresh"),
                QuickStartOption("middle", "Somewhere in the middle", brief = "somewhere in the middle"),
                QuickStartOption("round", "Rounded and low-acid", brief = "rounded and low-acid")
            ),
            briefLead = "Acid"
        ),
        QuickStartQuestion(
            id = X_BODY,
            prompt = "Weight in the glass?",
            options = listOf(
                QuickStartOption("light", "Light — I want a second glass", brief = "light"),
                QuickStartOption("medium", "Medium", brief = "medium"),
                QuickStartOption("full", "Full and mouth-filling", brief = "full and mouth-filling")
            ),
            briefLead = "Body"
        ),
        QuickStartQuestion(
            id = X_AGING,
            prompt = "Would you rather drink a bottle now or wait for it?",
            help = "This decides whether a shop pick is a bottle for Tuesday or one for 2031.",
            options = listOf(
                QuickStartOption("now", "Now — I do not want to wait", brief = "now, I do not want to wait"),
                QuickStartOption("few", "A few years is fine", brief = "a few years is fine"),
                QuickStartOption("wait", "I like old bottles and will wait", brief = "I like old bottles and will wait")
            ),
            briefLead = "Cellaring"
        ),
        QuickStartQuestion(
            id = X_FUNK,
            prompt = "How do you feel about a little funk?",
            help = "The barnyard, cider-apple, nail-polish edge natural wine sometimes has.",
            options = listOf(
                QuickStartOption("clean", "Clean and precise — no funk", brief = "clean and precise, no funk"),
                QuickStartOption("bit", "A little is interesting", brief = "a little funk is interesting"),
                QuickStartOption("love", "I like natural wine, funk and all", brief = "I like natural wine, funk and all")
            ),
            briefLead = "Natural wine"
        ),
        QuickStartQuestion(
            id = X_SPARKLING,
            prompt = "How often is there bubbles in the glass?",
            options = listOf(
                QuickStartOption("often", "Often", brief = "often"),
                QuickStartOption("sometimes", "Sometimes", brief = "sometimes"),
                QuickStartOption("rarely", "Rarely", brief = "rarely")
            ),
            briefLead = "Sparkling"
        ),
        QuickStartQuestion(
            id = X_RESTAURANT,
            prompt = "At a restaurant, you would rather…",
            help = "This decides how a list is answered: one bottle for the table, or a glass each.",
            options = listOf(
                QuickStartOption("glass", "Order a glass and keep it simple", brief = "order by the glass"),
                QuickStartOption("bottle", "One bottle, the best value for the table", brief = "one bottle, the best value for the table"),
                QuickStartOption("sommelier", "Let the sommelier choose", brief = "let the sommelier choose"),
                QuickStartOption("avoid", "Wine lists intimidate me — keep it short", brief = "keep a list answer short, they are not at home on a wine list")
            ),
            briefLead = "At a restaurant"
        ),
        QuickStartQuestion(
            id = X_OAK,
            prompt = "Where do you stand on oak?",
            options = listOf(
                QuickStartOption("none", "None — I want the fruit", brief = "none, I want the fruit"),
                QuickStartOption("touch", "A touch, if it is in the background", brief = "a touch, in the background"),
                QuickStartOption("like", "I like it — vanilla and toast are fine", brief = "I like it, vanilla and toast are fine")
            ),
            briefLead = "Oak"
        ),
        QuickStartQuestion(
            id = X_PRICE,
            prompt = "For a bottle that is worth it, you would pay…",
            help = "On top of your normal bottle. It is a range for fair value, not a ceiling.",
            options = listOf(
                QuickStartOption("same", "Not much more — my normal price is the point", brief = "not much more than my normal price"),
                QuickStartOption("twice", "Up to twice my usual", brief = "up to twice my usual"),
                QuickStartOption("thrice", "Up to three times my usual", brief = "up to three times my usual"),
                QuickStartOption("reason", "Price is not the constraint, the reason is", brief = "price is not the constraint, the reason is")
            ),
            briefLead = "For a bottle that is worth it"
        )
    )

    /** Baseline and interview in one list: the answers summary, the file and the review read this. */
    val questions: List<QuickStartQuestion> get() = baseline + extras

    fun question(id: String): QuickStartQuestion? = questions.firstOrNull { it.id == id }

    fun isExtra(id: String): Boolean = extras.any { it.id == id }

    /** The ten, in the order the screens appear: seven taps, the shortlist, three more. */
    fun baselineSteps(): List<QuickStartQuestion> =
        baseline.filter { !it.afterPicks } + baseline.filter { it.afterPicks }
}