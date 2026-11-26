package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the `mytaste.md` a quick-start user starts with, and renders the short prompt a model reads.
 *
 * The shape is a receipt: every line carries where it came from and how sure the app is, a field with
 * no source renders as *unknown*, and the file says what it does not know. That is what keeps a
 * taps-only profile honest — the taps are few, so provenance does the work the evidence cannot.
 *
 * Two things the generator will not do. It will not fill a field from general knowledge, and it will
 * not write a claim about a specific wine: bottle facts live in bottle records, with citations.
 *
 * The prompt is the other half, and it used to be written beside the receipt rather than rendered
 * from it — its own prose, its own grape list, its own memories. [renderModelPrompt] fixes that: every
 * line carries the id of the field that produced it, and [assertEveryLineRendered] refuses a line
 * with no field behind it. A sentence cannot be hand-added to the prompt any more.
 */
object PalateFile {

    const val MARKER = "<!-- quick start:"

    /** Kept here so the builder stays free of Android; [TasteStore] points at the same constants. */
    const val LOVED_START = "<!-- AUTO:LOVED:START -->"
    const val LOVED_END = "<!-- AUTO:LOVED:END -->"

    fun build(
        profile: PalateProfile,
        rules: PalateRules = PalateRules.from(profile),
        ratings: Int = 0,
        now: LocalDate = LocalDate.now()
    ): String {
        // Only ratings are tracked so far, and a rating is worth 2 of the weights below.
        val signals = ratings * 2
        val blocks = mutableListOf<String>()

        blocks += "# My taste"
        blocks += "<!-- GENERATED FILE — the quick start rewrites it. It leaves the phone only " +
            "inside a request to the model. -->\n" +
            "<!-- Rendered from the stored profile on $now. If this file and the stored profile " +
            "disagree, the stored profile wins for the gates and this file is regenerated. -->\n" +
            "$MARKER ${profile.baselineAnswered} of ${QuickStart.baseline.size} taps · " +
            "$signals weighted signals · $now -->"
        blocks += provisional(profile, ratings)
        blocks += RULE
        blocks += VOCABULARY
        blocks += PRECEDENCE

        blocks += "## Anchor line"
        blocks += anchor(profile)

        blocks += "## My taste"
        blocks += tasteSection(profile, rules)

        if (profile.hasNotes || profile.links.isNotBlank()) {
            blocks += "## In my own words"
            blocks += inMyWords(profile)
        }

        blocks += "## How to score a bottle"
        blocks += "Gates first. A bottle that fails a gate is never scored. **Unknown is not a pass.**"
        blocks += gateTable(profile, rules)
        blocks += GATE_NOTE

        blocks += "## Settings"
        blocks += settingsTable(profile)

        blocks += "## Pairing"
        blocks += PAIRING
        blocks += foodHabits(profile)

        blocks += "## What I enjoy"
        blocks += enjoy(profile)

        blocks += "## What I avoid"
        blocks += avoid(profile)

        blocks += "## House rules"
        blocks += houseRules(profile)

        blocks += "## Other drinkers"
        blocks += otherDrinkers(profile)

        blocks += "## Bottles I love"
        blocks += LOVED_START
        blocks += if (signals > 0) {
            "_No loved bottle is listed yet — the app fills this from favourites and 4★+ ratings._"
        } else {
            "_Nothing rated yet._"
        }
        blocks += LOVED_END

        blocks += "## How to answer"
        blocks += howToAnswer(profile)

        blocks += "## What this file doesn't know"
        blocks += unknowns(profile, rules, ratings)

        blocks += "## Conflicts"
        blocks += conflicts(ratings)

        blocks += "## Facts and honesty"
        blocks += FACTS

        blocks += "## What changes this file"
        blocks += CHANGES

        return blocks.joinToString("\n\n").trimEnd() + "\n"
    }

    /** Question and chosen labels, for the summary on the Taste screen. */
    fun answers(profile: PalateProfile): List<Pair<String, String>> =
        QuickStart.questions.mapNotNull { question ->
            val chosen = profile.choices(question.id)
            if (chosen.isEmpty()) return@mapNotNull null
            val labels = chosen.mapNotNull { id ->
                question.options.firstOrNull { it.id == id }?.label
            }
            if (labels.isEmpty()) null else question.prompt to labels.joinToString(", ")
        }

    private data class Fact(val value: String, val source: String, val confidence: Confidence)

    private fun provisional(profile: PalateProfile, ratings: Int): String {
        val taps = "${profile.baselineAnswered} of ${QuickStart.baseline.size} taps"
        return if (ratings == 0) {
            "> **Provisional.** $taps answered and no ratings. There is nothing rated in this app " +
                "yet, so every value below is a starting guess rather than a finding."
        } else {
            "> **Provisional.** $taps answered and $ratings rated — under the ten weighted signals " +
                "that would let behaviour overrule a tap. Until then, every value below is a " +
                "starting guess rather than a finding."
        }
    }

    /**
     * The sentence the user wrote that links their bottles, kept as an answer of its own.
     *
     * It used to render as "Not set yet — $ratings rated, and nothing here has read them into one
     * sentence", which printed the generator's silence as a fact about the user while the sentence
     * sat in the input. A written anchor is a *Yours* field; only its absence renders "Not set yet",
     * and then it names the answer that is missing.
     */
    private fun anchor(profile: PalateProfile): String {
        val sentence = profile.links.trim()
        if (sentence.isNotEmpty()) {
            return "- $sentence — *\"what links them\" (the quick start) · Yours*"
        }
        return "_Not set yet — the line that links your bottles has not been written. Answer " +
            "\"what links them\" in the quick start._"
    }

    private fun tasteSection(profile: PalateProfile, rules: PalateRules): String {
        val facts = mutableListOf<Fact>()
        style(profile)?.let {
            facts += Fact(it, source(profile, QuickStart.Q_STYLE), Confidence.YOURS)
        }
        sweetness(profile)?.let {
            facts += Fact(it, source(profile, QuickStart.Q_SWEET), Confidence.YOURS)
        }
        if (QuickStart.AVOID_OAK in profile.choices(QuickStart.Q_AVOID)) {
            // "I don't want heavy oak" turned into "restrained oak, vanilla in the background" is an
            // inference: the wording is his, the value is ours, and the line says so.
            facts += Fact(
                "Oak restrained: vanilla and toast in the background, never the flavour",
                source(profile, QuickStart.Q_AVOID),
                Confidence.OURS
            )
        }
        rules.maxAlcohol?.let {
            facts += Fact("${fmt(it)}% or under", "typed on the Taste screen", Confidence.YOURS)
        }
        // The optional interview is part of the same field list the prompt is rendered from, so it
        // appears here too: a fact a model reads must never be missing from the receipt beside it.
        QuickStart.extras.forEach { question ->
            if (question.briefLead.isBlank()) return@forEach
            val phrases = profile.choices(question.id).mapNotNull { id ->
                question.options.firstOrNull { it.id == id }?.brief?.takeIf { phrase -> phrase.isNotBlank() }
            }
            if (phrases.isEmpty()) return@forEach
            val value = if (question.multi) phrases.joinToString(", ") else phrases.first()
            facts += Fact(
                "${question.briefLead}: $value",
                "\"${question.prompt}\" · optional interview",
                Confidence.YOURS
            )
        }
        if (facts.isEmpty()) {
            return "_Unknown — no answer here describes what a wine should taste of. The quick start " +
                "asks which axis does the work, not what the wine tastes like._"
        }
        return bullets(facts)
    }

    /**
     * The gate table, rendered from [GateRegistry] — never assembled row by row.
     *
     * Four rows, always, in the authored order. The old table had two because G2 and G4 were dropped
     * and the numbering kept the scars; rendering the registry is what makes that impossible again.
     */
    private fun gateTable(profile: PalateProfile, rules: PalateRules): String {
        val specs = GateRegistry.specs(
            alcoholCeiling = rules.maxAlcohol,
            sugarCeiling = rules.maxSugar,
            sugarSource = if (rules.maxSugar != null) source(profile, QuickStart.Q_SWEET) else null
        )
        return buildString {
            appendLine("| Gate | Test | Type | Source | Confidence |")
            appendLine("|---|---|---|---|---|")
            specs.forEach { spec ->
                appendLine(
                    "| **${spec.title}** | ${spec.test} | " +
                        "${spec.severity.name.lowercase()} | ${spec.source} | ${spec.confidence.label} |"
                )
            }
        }.trimEnd()
    }

    private fun settingsTable(profile: PalateProfile): String {
        val rows = listOf(
            settingRow("Usually pours", pours(profile), QuickStart.Q_POURS, profile),
            settingRow("Style axis", style(profile), QuickStart.Q_STYLE, profile),
            settingRow("Budget", budget(profile), QuickStart.Q_BUDGET, profile),
            settingRow(
                "Repeats", repeats(profile), QuickStart.Q_REPEATS, profile,
                "Allowed, with disclosure" to "our default"
            ),
            settingRow(
                "Adventure", adventure(profile), QuickStart.Q_ADVENTURE, profile,
                "Welcome, with a stated reason" to "our default"
            ),
            settingRow("Buying for", audience(profile), QuickStart.Q_AUDIENCE, profile)
        )
        return buildString {
            appendLine("| Setting | Value | Source | Confidence |")
            appendLine("|---|---|---|---|")
            rows.forEach { appendLine(it) }
        }.trimEnd()
    }

    private fun settingRow(
        label: String,
        value: String?,
        questionId: String,
        profile: PalateProfile,
        default: Pair<String, String>? = null
    ): String {
        if (value != null) return "| $label | $value | ${source(profile, questionId)} | Yours |"
        if (profile.answered(questionId)) {
            return "| $label | Unknown | ${source(profile, questionId)} | Unknown |"
        }
        if (default != null) return "| $label | ${default.first} | ${default.second} | Ours |"
        return "| $label | Unknown — ask | not answered | Unknown |"
    }

    private fun foodHabits(profile: PalateProfile): String {
        val label = label(profile, QuickStart.Q_CONTEXT)
        if (label == null) {
            val why = if (QuickStart.Q_CONTEXT in profile.skipped) {
                "you skipped that question"
            } else {
                "not asked yet"
            }
            return "Food habits: unknown — $why. Ask before assuming I cook."
        }
        val habits = when (profile.choice(QuickStart.Q_CONTEXT)) {
            QuickStart.CTX_WEEKNIGHT -> "weeknight dinners at home"
            QuickStart.CTX_WEEKEND -> "weekend cooking"
            QuickStart.CTX_TAKEOUT -> "takeout or spicy food"
            QuickStart.CTX_RESTAURANT -> "eating out, where the list is the choice"
            QuickStart.CTX_ALONE -> "wine on its own, with no dish to match"
            else -> "unknown"
        }
        return "Food habits: $habits — *\"$label\" (${QUESTION_NUMBERS[QuickStart.Q_CONTEXT]}) · " +
            "Yours*. No dish is named yet, so nothing here says what I cook."
    }

    private fun enjoy(profile: PalateProfile): String {
        val facts = mutableListOf<Fact>()
        pours(profile)?.let {
            facts += Fact(it, source(profile, QuickStart.Q_POURS), Confidence.YOURS)
        }
        style(profile)?.let {
            facts += Fact(it, source(profile, QuickStart.Q_STYLE), Confidence.YOURS)
        }
        sweetness(profile)?.let {
            facts += Fact(it, source(profile, QuickStart.Q_SWEET), Confidence.YOURS)
        }
        if (facts.isEmpty()) {
            return "_Unknown — nothing here says what a good bottle tastes like. Three ratings will " +
                "say more than a guess._"
        }
        return bullets(facts) + "\n\n_Soft: from a tap, not a rating._"
    }

    private fun avoid(profile: PalateProfile): String {
        val avoids = profile.choices(QuickStart.Q_AVOID)
        if (avoids.isEmpty()) {
            return "_Unknown — nothing has been rejected yet. Ask before assuming a wine is unwanted._"
        }
        if (QuickStart.AVOID_NOTHING in avoids) {
            return bullets(
                listOf(Fact("Nothing excluded yet", source(profile, QuickStart.Q_AVOID), Confidence.YOURS))
            )
        }
        val facts = mutableListOf<Fact>()
        if (QuickStart.AVOID_STRONG in avoids) {
            facts += Fact("Strong, high-alcohol wines", source(profile, QuickStart.Q_AVOID), Confidence.YOURS)
        }
        if (QuickStart.AVOID_OAK in avoids) {
            facts += Fact("Heavy oak: vanilla, toast, buttery", source(profile, QuickStart.Q_AVOID), Confidence.YOURS)
        }
        if (QuickStart.AVOID_SWEET in avoids) {
            facts += Fact("Sweet wines", source(profile, QuickStart.Q_AVOID), Confidence.YOURS)
        }
        return bullets(facts)
    }

    private fun houseRules(profile: PalateProfile): String {
        val out = mutableListOf<String>()
        out += when (profile.choice(QuickStart.Q_BUDGET)) {
            QuickStart.BUDGET_UNDER_12 ->
                "- **Under \$12:** normal. Anything over \$12 needs a reason. — " +
                    "${source(profile, QuickStart.Q_BUDGET)} · Yours"
            QuickStart.BUDGET_12_20 ->
                "- **\$12–20:** normal. Under \$12 is a bonus; over \$20 needs a reason. — " +
                    "${source(profile, QuickStart.Q_BUDGET)} · Yours"
            QuickStart.BUDGET_20_35 ->
                "- **\$20–35:** normal. Over \$35 needs a reason. — " +
                    "${source(profile, QuickStart.Q_BUDGET)} · Yours"
            QuickStart.BUDGET_35_UP ->
                "- **\$35 and up:** normal. Price is not a selling point. — " +
                    "${source(profile, QuickStart.Q_BUDGET)} · Yours"
            else -> "- **Price:** unknown — no band was given. Do not assume one."
        }
        out += when (profile.choice(QuickStart.Q_LANDS)) {
            QuickStart.LANDS_STORY -> "- **Provenance:** lead with the place and the producer — " +
                "${source(profile, QuickStart.Q_LANDS)} · Yours"
            QuickStart.LANDS_VALUE -> "- **Provenance:** lead with what it cost — " +
                "${source(profile, QuickStart.Q_LANDS)} · Yours"
            QuickStart.LANDS_UNUSUAL -> "- **Provenance:** lead with what makes it unusual — " +
                "${source(profile, QuickStart.Q_LANDS)} · Yours"
            QuickStart.LANDS_SIMPLE -> "- **Provenance:** lead with whether it works — " +
                "${source(profile, QuickStart.Q_LANDS)} · Yours"
            else -> "- **Provenance:** unknown — no preference was stated. Lead with whether it works."
        }
        out += when (profile.choice(QuickStart.Q_REPEATS)) {
            QuickStart.REPEATS_NEVER ->
                "- **Repeats:** not the same bottle or grape twice in a month without saying why — " +
                    "${source(profile, QuickStart.Q_REPEATS)} · Yours"
            QuickStart.REPEATS_FINE ->
                "- **Repeats:** allowed when it's the best answer, but the answer must say it's a " +
                    "repeat — ${source(profile, QuickStart.Q_REPEATS)} · Yours"
            else -> "- **Repeats:** allowed when it's the best answer, but the answer must say it's " +
                "a repeat — our default · Ours"
        }
        out += "- **Departures:** welcome, but say why it's a departure."
        out += "- **Tone:** no slogans, no exclamation marks, no \"Enjoy!\", no adjectives that " +
            "could go on any bottle. Internal vocabulary never appears on screen."
        return out.joinToString("\n")
    }

    private fun otherDrinkers(profile: PalateProfile): String =
        when (profile.choice(QuickStart.Q_AUDIENCE)) {
            QuickStart.AUDIENCE_GIFTS ->
                "Gifts and hosting. I am choosing for somebody else: apply the dish and price " +
                    "rules and my answer style, but not my sweetness, oak or alcohol gates. Say " +
                    "out loud where their taste and mine disagree — *${source(profile, QuickStart.Q_AUDIENCE)} · Yours*"
            QuickStart.AUDIENCE_PLUS_ONE ->
                "Me and someone else, mostly. Apply my gates, and ask when their taste differs — " +
                    "*${source(profile, QuickStart.Q_AUDIENCE)} · Yours*"
            QuickStart.AUDIENCE_JUST_ME ->
                "Usually just me — my gates apply. When it's for someone else, ask about their " +
                    "taste and theirs outranks mine — *${source(profile, QuickStart.Q_AUDIENCE)} · Yours*"
            else ->
                "Not known yet — ask when it changes the answer."
        }

    private fun howToAnswer(profile: PalateProfile): String {
        val reason = when (profile.choice(QuickStart.Q_LANDS)) {
            QuickStart.LANDS_STORY ->
                "Lead with where it comes from and who made it — provenance is the reason that lands."
            QuickStart.LANDS_VALUE ->
                "Lead with what it costs — a good bottle at a good price is the reason that lands."
            QuickStart.LANDS_UNUSUAL ->
                "Lead with what makes it unusual — novelty is the reason that lands."
            QuickStart.LANDS_SIMPLE ->
                "Lead with whether it will work. No performance, no sign-off."
            else -> "Lead with whether it will work."
        }
        val repeatLine = if (profile.choice(QuickStart.Q_REPEATS) == QuickStart.REPEATS_NEVER) {
            "6. If the best bottle is a repeat, say so, and say why the earlier logic no longer applies."
        } else {
            "6. If the best bottle is a repeat, recommend it and say so."
        }
        return listOf(
            "1. $reason",
            "2. Walk the gates first, then score. A gate that can't be checked is unknown, not passed.",
            "3. One bottle. One sentence on why it wins, one on why the runner-up loses.",
            "4. Never suggest anything on the avoid list, whatever the dish.",
            "5. If a pairing rule and a gate disagree, say the conflict out loud rather than picking one.",
            repeatLine
        ).joinToString("\n")
    }

    private fun unknowns(profile: PalateProfile, rules: PalateRules, ratings: Int): String {
        val gaps = mutableListOf<String>()
        // The ten-tap baseline only: the optional interview is detail, and listing twelve unanswered
        // questions as unknowns would make the file read like a form that failed.
        QuickStart.baseline.filter { !profile.answered(it.id) }.forEach { question ->
            gaps += "- ${FIELD_NAMES[question.id] ?: question.prompt}: not known — ask rather than assume."
        }
        if (profile.choice(QuickStart.Q_STYLE) == QuickStart.STYLE_UNSURE) {
            gaps += "- The style axis: not known — \"Honestly, not sure\" is an answer, not a style."
        }
        if (rules.maxAlcohol == null) {
            gaps += "- My alcohol ceiling: not known — no number was given, so do not invent one."
        }
        gaps += "- Bottle facts: not in this file — they live in bottle records, with citations."
        gaps += if (ratings == 0) {
            "- What I actually like, as opposed to what I tapped: not known — nothing is rated."
        } else {
            "- Whether these answers survive the next bottle: not known — $ratings rated so far."
        }
        return gaps.joinToString("\n") + "\n\nIf a question depends on one of these, ask rather than assume."
    }

    /** The optional answers, verbatim: nothing here is turned into a rule. */
    private fun inMyWords(profile: PalateProfile): String = buildList {
        // The links sentence is the anchor line above, so it is not repeated here.
        profile.lovedNote.trim().takeIf { it.isNotBlank() }?.let { add("Wines I loved: $it") }
        profile.dislikedNote.trim().takeIf { it.isNotBlank() }?.let { add("One I didn't enjoy: $it") }
        profile.curiousNote.trim().takeIf { it.isNotBlank() }?.let { add("Curious to try: $it") }
    }.joinToString("\n\n")

    private fun conflicts(ratings: Int): String =
        if (ratings == 0) {
            "None. What you said and what you pick agree so far."
        } else {
            "None detected yet. $ratings rated, and behaviour needs ten weighted signals before it " +
                "can overrule an answer."
        }

    private fun bullets(facts: List<Fact>): String =
        facts.joinToString("\n") { "- ${it.value} — *${it.source} · ${it.confidence.label}*" }

    private fun pours(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_POURS)) {
            QuickStart.POUR_RED -> "Red, mostly"
            QuickStart.POUR_WHITE -> "White, mostly"
            QuickStart.POUR_ROSE -> "Rosé, mostly"
            QuickStart.POUR_SPARKLING -> "Sparkling, mostly"
            QuickStart.POUR_DESSERT -> "Sweet and dessert wines, mostly"
            QuickStart.POUR_EVERYTHING -> "A bit of everything — no default filter"
            else -> null
        }

    private fun style(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_STYLE)) {
            QuickStart.STYLE_CRISP -> "Crisp and fresh: acid ahead of body"
            QuickStart.STYLE_SMOOTH -> "Smooth and mellow: texture ahead of grip"
            QuickStart.STYLE_BIG -> "Big and bold: body and structure ahead of delicacy"
            QuickStart.STYLE_SWEET -> "A little sweet: sweetness welcome, but not a dessert wine"
            else -> null
        }

    private fun sweetness(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_SWEET)) {
            QuickStart.SWEET_NONE -> "Dry: no noticeable residual sugar"
            QuickStart.SWEET_TOUCH -> "Dry to barely off-dry: a touch of sweetness is fine"
            QuickStart.SWEET_SWEETER -> "Sweetness welcome, balanced by acid"
            else -> null
        }

    private fun budget(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_BUDGET)) {
            QuickStart.BUDGET_UNDER_12 -> "Under \$12 a bottle"
            QuickStart.BUDGET_12_20 -> "\$12–20 a bottle"
            QuickStart.BUDGET_20_35 -> "\$20–35 a bottle"
            QuickStart.BUDGET_35_UP -> "\$35 and up a bottle"
            else -> null
        }

    private fun repeats(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_REPEATS)) {
            QuickStart.REPEATS_NEVER -> "Not twice in a month without saying why"
            QuickStart.REPEATS_FINE -> "Allowed when it's the best answer, said out loud"
            else -> null
        }

    private fun adventure(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_ADVENTURE)) {
            QuickStart.ADVENTURE_FAMILIAR -> "Stay close to what I already drink"
            QuickStart.ADVENTURE_SIMILAR -> "One step out: same style, different producer or place"
            QuickStart.ADVENTURE_SURPRISE -> "Happy to be surprised, with the reason stated"
            else -> null
        }

    private fun audience(profile: PalateProfile): String? =
        when (profile.choice(QuickStart.Q_AUDIENCE)) {
            QuickStart.AUDIENCE_JUST_ME -> "Just me"
            QuickStart.AUDIENCE_PLUS_ONE -> "Me and someone else"
            QuickStart.AUDIENCE_GIFTS -> "Gifts and hosting"
            else -> null
        }

    private fun label(profile: PalateProfile, questionId: String): String? {
        val question = QuickStart.question(questionId) ?: return null
        val labels = profile.choices(questionId).mapNotNull { id ->
            question.options.firstOrNull { it.id == id }?.label
        }
        return if (labels.isEmpty()) null else labels.joinToString(", ")
    }

    /** `"None, please" (Q3)` — the words the user chose, and where they chose them. */
    private fun source(profile: PalateProfile, questionId: String): String {
        val words = label(profile, questionId) ?: return "not answered"
        val number = QUESTION_NUMBERS[questionId]
        return if (number == null) "\"$words\"" else "\"$words\" ($number)"
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    // ---- The prompt -------------------------------------------------------------------------

    /**
     * A prompt and, for every line, the field that produced it. `lineFields[i]` is null only for
     * formatting (a blank separator or the `## Notes` heading).
     */
    data class RenderedPrompt(val text: String, val lineFields: List<String?>)

    private data class PromptLine(val text: String, val field: String?)

    /**
     * Refuses any line with nothing behind it. Called by [renderModelPrompt] itself, so a sentence
     * added to the prompt by hand cannot ship; it throws in the test suite first.
     */
    fun assertEveryLineRendered(rendered: RenderedPrompt) {
        val lines = rendered.text.split("\n")
        check(lines.size == rendered.lineFields.size) {
            "prompt has ${lines.size} lines but ${rendered.lineFields.size} field ids"
        }
        lines.forEachIndexed { index, line ->
            if (line.isNotBlank() && line !in FORMATTING && rendered.lineFields[index] == null) {
                throw IllegalStateException(
                    "prompt line ${index + 1} has no field behind it: \"$line\""
                )
            }
        }
    }

    /**
     * Where a rendered field came from, and therefore its confidence. UNKNOWN means the field is
     * absent from this profile and must not reach a model.
     */
    fun fieldConfidence(profile: PalateProfile, fieldId: String): Confidence = when {
        fieldId == FIELD_EMPTY || fieldId == FIELD_GATES -> Confidence.OURS
        fieldId == FIELD_RESTAURANT -> Confidence.OURS
        fieldId == "alcohol_ceiling" -> Confidence.YOURS
        fieldId == "links" -> Confidence.YOURS
        fieldId.startsWith("notes.") -> Confidence.YOURS
        fieldId.startsWith("bottles.") -> Confidence.YOURS
        fieldId.startsWith("q") || fieldId.startsWith("x_") ->
            if (profile.answered(fieldId)) Confidence.YOURS else Confidence.UNKNOWN
        else -> Confidence.UNKNOWN
    }

    /** Lines the renderer owns: a separator, and the heading above the carried notes. */
    private val FORMATTING = setOf("## Notes")

    const val FIELD_EMPTY = "profile.empty"
    const val FIELD_GATES = "gates.severity"
    const val FIELD_RESTAURANT = "budget.restaurant"

    /**
     * The taste brief a model actually reads, rendered from the stored profile.
     *
     * `mytaste.md` is a receipt: provenance on every line, gate tables, precedence rules, what the
     * file does not know. That is the right shape for a person to audit and the wrong shape for a
     * prompt. On a list, nothing prints ABV or oak, so a gate that needs those facts fails or goes
     * unknown on every line.
     *
     * So the brief is short, first-person and about the wine: what I drink, what I like, the bottles
     * I loved, the budget in retail terms, and how far a pick may stray. Oak and alcohol are stated
     * as preferences because that is what a tap can honestly support — and whether they are
     * preferences or gates is generated from the gate severity, never authored.
     */
    fun renderModelPrompt(
        profile: PalateProfile,
        rules: PalateRules = PalateRules.from(profile),
        loved: List<Wine> = emptyList(),
        disliked: List<Wine> = emptyList(),
        links: String? = null,
        notes: String = "",
        maxChars: Int = BRIEF_MAX_CHARS
    ): RenderedPrompt {
        val lines = mutableListOf<PromptLine>()
        fun emit(text: String, field: String) {
            val clean = text.trim()
            if (clean.isNotEmpty()) lines += PromptLine(clean, field)
        }

        val specs = rules.specs()
        val hasHardGate = specs.any { it.severity == Severity.HARD && it.configured }

        val pours = when (profile.choice(QuickStart.Q_POURS)) {
            QuickStart.POUR_RED -> "red"
            QuickStart.POUR_WHITE -> "white"
            QuickStart.POUR_ROSE -> "rosé"
            QuickStart.POUR_SPARKLING -> "sparkling"
            QuickStart.POUR_DESSERT -> "sweet and dessert wine"
            QuickStart.POUR_EVERYTHING -> "a bit of everything"
            else -> null
        }
        val style = when (profile.choice(QuickStart.Q_STYLE)) {
            QuickStart.STYLE_CRISP -> "crisp, fresh wines where the acid leads"
            QuickStart.STYLE_SMOOTH -> "smooth, mellow wines where texture leads"
            QuickStart.STYLE_BIG -> "big, structured wines"
            QuickStart.STYLE_SWEET -> "a little sweetness, but not a dessert wine"
            else -> null
        }
        val sweet = when (profile.choice(QuickStart.Q_SWEET)) {
            QuickStart.SWEET_NONE -> "bone dry — no sweetness at all"
            QuickStart.SWEET_TOUCH -> "dry, with a touch of sweetness allowed"
            QuickStart.SWEET_SWEETER -> "sweeter than dry, balanced by acid"
            else -> null
        }
        var tasteFields = 0
        pours?.let { emit("I mostly drink $it.", QuickStart.Q_POURS); tasteFields++ }
        style?.let { emit("I like $it.", QuickStart.Q_STYLE); tasteFields++ }
        sweet?.let { emit("Sweetness: $it.", QuickStart.Q_SWEET); tasteFields++ }

        // Oak and alcohol as preferences. A list prints neither, so a gate on them is a rule that
        // can never be checked there — which is how every red on a list came back "unknown".
        val preferences = mutableListOf<String>()
        if (QuickStart.AVOID_OAK in profile.choices(QuickStart.Q_AVOID)) {
            preferences += "restrained oak — vanilla and toast in the background, not the flavour"
        }
        if (rules.maxAlcohol != null) {
            preferences += "moderate alcohol, about ${fmt(rules.maxAlcohol!!)}% or under when there is a choice"
        } else if (QuickStart.AVOID_STRONG in profile.choices(QuickStart.Q_AVOID)) {
            preferences += "moderate alcohol — strong, high-alcohol wines are not for me"
        }
        if (preferences.isNotEmpty()) {
            // Semicolons, because each preference already carries a dash clause of its own.
            val field = if (rules.maxAlcohol != null) "alcohol_ceiling" else QuickStart.Q_AVOID
            emit("I prefer ${preferences.joinToString("; ")}.", field)
            tasteFields++
        }

        val lovedLine = loved.take(4).map { bottleLine(it) }.filter { it.isNotBlank() }
        if (lovedLine.isNotEmpty()) {
            emit("Bottles I loved: ${lovedLine.joinToString("; ")}.", BOTTLES_LOVED)
            tasteFields++
        }

        val dislikedLine = disliked.take(3).map { bottleLine(it) }.filter { it.isNotBlank() }
        if (dislikedLine.isNotEmpty()) {
            emit("Bottles I didn't enjoy: ${dislikedLine.joinToString("; ")}.", BOTTLES_DISLIKED)
            tasteFields++
        }

        // Verbatim, but capped here as well as at entry: a hand-edited profile can be any length,
        // and the brief has a hard budget of its own.
        profile.lovedNote.trim().takeIf { it.isNotBlank() }?.let {
            emit("Wines I loved, in my words: ${it.take(PalateProfile.NOTE_MAX_CHARS)}", "notes.loved")
            tasteFields++
        }
        profile.dislikedNote.trim().takeIf { it.isNotBlank() }?.let {
            emit("A wine I didn't enjoy, in my words: ${it.take(PalateProfile.NOTE_MAX_CHARS)}", "notes.disliked")
            tasteFields++
        }
        profile.curiousNote.trim().takeIf { it.isNotBlank() }?.let {
            emit("Curious to try: ${it.take(PalateProfile.NOTE_MAX_CHARS)}", "notes.curious")
            tasteFields++
        }

        val linkLine = links ?: profile.links
        linkLine.trim().takeIf { it.isNotBlank() }?.let {
            // Their own sentence, so do not staple a second full stop onto it.
            emit(
                "What links them: ${it.take(PalateProfile.NOTE_MAX_CHARS).trimEnd('.', '!', '?')}.",
                "links"
            )
            tasteFields++
        }

        // The optional interview, one line per answered question and nothing for the rest. The
        // phrases live beside the chips in QuickStart, so the brief and the question cannot drift;
        // an option with no phrase ("the names are still new to me") is app context, not taste.
        QuickStart.extras.forEach { question ->
            if (question.briefLead.isBlank()) return@forEach
            val phrases = profile.choices(question.id).mapNotNull { id ->
                question.options.firstOrNull { it.id == id }?.brief?.takeIf { phrase -> phrase.isNotBlank() }
            }
            if (phrases.isEmpty()) return@forEach
            emit(
                if (question.multi) {
                    "${question.briefLead}: ${phrases.joinToString(", ")}."
                } else {
                    "${question.briefLead}: ${phrases.first()}."
                },
                question.id
            )
            tasteFields++
        }

        budgetBand(profile.choice(QuickStart.Q_BUDGET))?.let { retail ->
            emit("Budget: $retail a bottle at retail.", QuickStart.Q_BUDGET)
            tasteFields++
            // Retail x 2.5–3.5 is arithmetic on his band, not something he said. It is prefixed
            // derived: and is never labelled Yours.
            restaurantBand(profile.choice(QuickStart.Q_BUDGET))?.let { restaurant ->
                emit(
                    "derived: about $restaurant on a restaurant list (retail × 2.5–3.5).",
                    FIELD_RESTAURANT
                )
            }
        }

        when (profile.choice(QuickStart.Q_ADVENTURE)) {
            QuickStart.ADVENTURE_FAMILIAR -> {
                emit("Keep picks close to what I already drink.", QuickStart.Q_ADVENTURE)
                tasteFields++
            }
            QuickStart.ADVENTURE_SIMILAR -> {
                emit("One step out is welcome: same style, a different producer or place.", QuickStart.Q_ADVENTURE)
                tasteFields++
            }
            QuickStart.ADVENTURE_SURPRISE -> {
                emit("Surprise me when the reason is good.", QuickStart.Q_ADVENTURE)
                tasteFields++
            }
            else -> Unit
        }

        if (tasteFields == 0) {
            emit(
                "I have not told you what I like yet — recommend on the wines themselves and say why.",
                FIELD_EMPTY
            )
        }

        // Generated from the gate registry, never written by hand. A model told the ceiling is a
        // preference will recommend a 14.6% bottle and call it a departure.
        emit(
            if (hasHardGate) {
                "Gates: hard. A bottle that fails one is not offered."
            } else {
                "Preferences, not rules — a departure costs a stated reason."
            },
            FIELD_GATES
        )

        // The user's own notes section, carried from the file they wrote. It is last so a cap cuts
        // it rather than the taste facts above it.
        val notesText = notes.trim().take(600)
        if (notesText.isNotBlank()) {
            lines += PromptLine("", null)
            if (!notesText.startsWith("## ")) lines += PromptLine("## Notes", null)
            notesText.split("\n").forEach { lines += PromptLine(it, "notes.assistant") }
        }

        val text = lines.joinToString("\n") { it.text }
        val rendered = if (text.length <= maxChars) {
            RenderedPrompt(text, lines.map { it.field })
        } else {
            cap(lines, maxChars)
        }
        assertEveryLineRendered(rendered)
        return rendered
    }

    /**
     * A hard cap, cut at a line boundary when one falls late enough that keeping it still says
     * something: half a sentence and an ellipsis beats half a word. A heading whose section did not
     * fit is worse than no section at all.
     */
    private fun cap(lines: List<PromptLine>, maxChars: Int): RenderedPrompt {
        val room = maxChars - 1
        val kept = mutableListOf<PromptLine>()
        var length = 0
        for (line in lines) {
            val add = if (kept.isEmpty()) line.text.length else line.text.length + 1
            if (length + add > room) break
            kept += line
            length += add
        }
        // A heading whose section did not fit is worse than no heading, and a trailing separator
        // would vanish into trimEnd — either would leave more field ids than lines.
        fun dropTail() {
            while (kept.isNotEmpty() && kept.last().text.isBlank()) kept.removeAt(kept.lastIndex)
        }
        dropTail()
        if (kept.isNotEmpty() && kept.last().text == "## Notes") {
            kept.removeAt(kept.lastIndex)
            dropTail()
        }
        if (kept.isEmpty()) {
            return RenderedPrompt("…", listOf(FIELD_EMPTY))
        }
        val body = kept.joinToString("\n") { it.text }.trimEnd()
        return RenderedPrompt(body + "…", kept.map { it.field })
    }

    private const val BOTTLES_LOVED = "bottles.loved"
    private const val BOTTLES_DISLIKED = "bottles.disliked"

    /**
     * About 330 words: the whole taste brief that rides along with a list or a label.
     *
     * It was 1,500 until the optional interview existed. Twelve answered questions at one short line
     * each pushed a full profile — four loved bottles, three disliked, three written notes — past the
     * cap, and the cut fell on the closing lines rather than on anything redundant.
     */
    const val BRIEF_MAX_CHARS = 2_000

    /** The brief as plain text. Kept for callers that do not need the field map. */
    fun brief(
        profile: PalateProfile,
        rules: PalateRules = PalateRules.from(profile),
        loved: List<Wine> = emptyList(),
        disliked: List<Wine> = emptyList(),
        links: String? = null,
        notes: String = "",
        maxChars: Int = BRIEF_MAX_CHARS
    ): String = renderModelPrompt(profile, rules, loved, disliked, links, notes, maxChars).text

    /** One loved or disliked bottle, named the way the user would name it. */
    private fun bottleLine(wine: Wine): String {
        val name = listOf(wine.producer, wine.name)
            .map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
        val year = wine.vintage?.let { " $it" }.orEmpty()
        val head = (name + year).trim().ifBlank { return "" }
        val place = wine.region.trim().ifBlank { wine.country.trim() }
        val withPlace = if (place.isBlank()) head else "$head, $place"
        val tags = wine.flavorTags.take(3).joinToString(", ")
        return if (tags.isBlank()) withPlace else "$withPlace — $tags"
    }

    /**
     * The "Bottles I love" block, rebuilt from rated and favourited wines.
     *
     * A reference note is a claim about a specific bottle, so it renders only with its source and the
     * date it was retrieved. A note with no source keeps its flavour claims out of the exercise
     * entirely: the bottle is named, region, grape, vintage and rating — nothing it tastes of.
     */
    fun lovedSection(wines: List<Wine>): String {
        val loved = wines
            .filter { it.favorite || (it.rating ?: 0) >= 4 }
            .sortedWith(
                compareByDescending<Wine> { (it.rating ?: 0) }
                    .thenByDescending { it.favorite }
                    .thenByDescending { it.updatedAt }
            )
        if (loved.isEmpty()) {
            return "_No loved bottles yet. Favourite a wine or rate it 4★ or more, then rebuild this section._"
        }
        return loved.take(30).joinToString("\n") { wine ->
            val origin = listOf(wine.region, wine.country)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(", ")
            val meta = listOf(wine.type.label, origin, wine.grapes.joinToString(", "))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val rating = wine.rating?.let { "$it★" } ?: "favourite"
            buildString {
                append("- **${wine.title}** — $meta · $rating")
                if (wine.flavorTags.isNotEmpty()) {
                    append("\n  - flavours: ${wine.flavorTags.joinToString(", ")}")
                }
                if (wine.myNotes.isNotBlank()) {
                    append("\n  - my note: ${wine.myNotes.replace("\n", " ").take(220)}")
                }
                if (wine.expertNotes.isNotBlank() && wine.notesSource.isNotBlank()) {
                    append(
                        "\n  - reference (source: ${wine.notesSource.trim().take(120)}, " +
                            "retrieved ${retrievedOn(wine.notesCheckedAt)}): " +
                            wine.expertNotes.replace("\n", " ").take(160)
                    )
                }
            }
        }
    }

    private fun retrievedOn(epochMillis: Long): String =
        if (epochMillis <= 0L) {
            "unknown"
        } else {
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }

    private fun budgetBand(choice: String?): String? = when (choice) {
        QuickStart.BUDGET_UNDER_12 -> "under \$12"
        QuickStart.BUDGET_12_20 -> "\$12–20"
        QuickStart.BUDGET_20_35 -> "\$20–35"
        QuickStart.BUDGET_35_UP -> "\$35 and up"
        else -> null
    }

    /** A list marks a bottle up: retail x 2.5 to 3.5 is the band to expect on a menu. */
    private fun restaurantBand(choice: String?): String? = when (choice) {
        QuickStart.BUDGET_UNDER_12 -> "up to about \$42"
        QuickStart.BUDGET_12_20 -> "\$30–70"
        QuickStart.BUDGET_20_35 -> "\$50–123"
        QuickStart.BUDGET_35_UP -> "\$88 and up"
        else -> null
    }

    private val QUESTION_NUMBERS = mapOf(
        QuickStart.Q_POURS to "Q1",
        QuickStart.Q_STYLE to "Q2",
        QuickStart.Q_SWEET to "Q3",
        QuickStart.Q_BUDGET to "Q4",
        QuickStart.Q_LANDS to "Q5",
        QuickStart.Q_AUDIENCE to "Q6",
        QuickStart.Q_AVOID to "Q7",
        QuickStart.Q_CONTEXT to "Q8",
        QuickStart.Q_ADVENTURE to "Q9",
        QuickStart.Q_REPEATS to "Q10"
    )

    private val FIELD_NAMES = mapOf(
        QuickStart.Q_POURS to "What I usually pour",
        QuickStart.Q_STYLE to "The style axis",
        QuickStart.Q_SWEET to "Sweetness",
        QuickStart.Q_BUDGET to "Budget",
        QuickStart.Q_LANDS to "What makes a bottle land",
        QuickStart.Q_AUDIENCE to "Who it is for",
        QuickStart.Q_AVOID to "What to avoid",
        QuickStart.Q_CONTEXT to "Food habits",
        QuickStart.Q_ADVENTURE to "How far a pick may stray",
        QuickStart.Q_REPEATS to "Repeats"
    )

    private val RULE =
        "**Rule of this file:** every line carries where it came from. A field with no source " +
            "renders as *unknown*. The generator may not fill a field from general knowledge, and " +
            "may not write a claim about a specific wine — bottle facts live in bottle records, " +
            "with citations."

    private val VOCABULARY = """
        **Confidence vocabulary**

        - **Yours** — you said it, or you rated a bottle.
        - **Ours** — inferred from your answers; the wording is yours, the exact number is our guess.
        - **Guess** — no input. Always soft, always visible, always correctable.
        - **Unknown** — not enough to say. Never passes a gate.
    """.trimIndent()

    private val PRECEDENCE = """
        **Precedence, highest first**

        1. Your explicit correction — locked until you change it.
        2. Behaviour, once there are 10+ weighted signals.
        3. Your intake answers.
        4. Our defaults.

        If 2 ever contradicts 3, the app says so before acting. It never switches silently.
    """.trimIndent()

    private val GATE_NOTE =
        "A **hard** gate cannot be broken. A **soft** gate may be broken only with a stated reason, " +
            "in one sentence, in the answer itself."

    private val PAIRING = """
        Pairing is worked out per dish **when I ask**, from what that dish demands: acid for fat, acid for tomato, salt, sweetness, heat, umami. It is not looked up.

        **Nothing is stored here about which grape suits which dish.** A sentence like "X is ruined by Y" is a claim about wine, claims need a source, and this file has no source for one.

        What is stored: the food I actually cook, when I've said so.
    """.trimIndent()

    private val FACTS = """
        - Facts about a bottle come from the label, a purchase record, or a cited source. Never from memory, and never from this file.
        - "Unknown" means the fact is missing. Say so. Never estimate, never round toward a pass.
        - Never say I own a bottle unless it is in my cellar list.
        - If a bottle is past its drinking window, say so plainly.
        - No number appears in an answer that isn't in the bottle record.
    """.trimIndent()

    private val CHANGES = """
        Signals are weighted: view 0.5 · scan 1 · rating 2 · dismiss 2 · save 3 · repeat purchase 4.

        - **Under 10 weighted signals:** intake answers decide. This file stays marked Provisional.
        - **10 to 20:** behaviour and answers are mixed. Any disagreement is surfaced once, in the Conflicts section, before it changes a recommendation.
        - **Over 20:** behaviour decides. Intake answers are kept only as history.
        - **Your correction:** overrides everything immediately and is locked — behaviour cannot walk it back. Say "change X" in chat and the file is rewritten here.

        It is a receipt, not a form: the quick start rewrites it. The advanced editor is the only place it is meant to be edited by hand, and after that the app keeps your version below the next generated one instead of replacing it.
    """.trimIndent()
}
