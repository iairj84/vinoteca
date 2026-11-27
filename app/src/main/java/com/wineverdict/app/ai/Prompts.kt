package com.wineverdict.app.ai

import com.wineverdict.app.data.Dish
import com.wineverdict.app.data.GateScope
import com.wineverdict.app.data.PalateRules
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.drinkStatus
import com.wineverdict.app.model.locationName
import java.time.LocalDate

/**
 * All prompts that reach the model. Each one states the currency, the date and
 * what must not be invented, because a wrong wine record is worse than none.
 */
object Prompts {

    fun today(): String = LocalDate.now().toString()

    /**
     * One prompt for whatever the shutter saw: a single label, a shop shelf or a restaurant list.
     *
     * The app used to make the user say which one it was with three chips. That question is in the
     * photo, and a wrong chip quietly changed how prices were judged (a list price once counted as a
     * bad deal against a retail band). The model names the scene in the same reply, so the screen
     * can draw a label card or a row of verdicts without being told which.
     */
    fun scan(
        tasteMd: String,
        currency: String,
        knownBottles: List<String> = emptyList()
    ): String {
        val known = if (knownBottles.isEmpty()) {
            "(my cellar is empty — there is nothing to compare against)"
        } else {
            knownBottles.distinct().take(40).joinToString("\n") { "- $it" }
        }
        return """
        You are a careful wine reader inside a personal cellar app. The photo is one of three things:
        a single bottle's label, a shop shelf of bottles, or a restaurant wine list.

        STEP 1 — name the scene in "scene".
        - "bottle": one bottle dominates the frame and I am photographing its label, even at an angle
          or half-lit.
        - "shelf": two or more bottles standing in a row, usually with price tags.
        - "list": a printed wine list, menu page or drinks card — mostly text, often two columns,
          usually with a glass price and a bottle price printed under each wine.
        - "other": no wine is legible (a person, an empty shelf, a blurry shot).
        Say what the photo shows, not what I might have wanted it to show.

        STEP 2 — get the wines out of the photo. This is the part that matters, and it differs by
        scene. Accuracy first: this request may take a few extra seconds, and that is fine.
        - scene "bottle": transcribe the label into "label_text": every legible word, one line per
          block of text, top to bottom, keeping capitals, accents and spelling exactly as printed.
          Include the small print: appellation, "mis en bouteille ...", ABV, volume, lot numbers,
          and the back label when it is visible. Never translate, tidy, expand or guess a word:
          where letters are doubtful keep the word and put "?" in their place. Then put THAT bottle
          as the single entry in "bottles".
        - scene "shelf": one entry in "bottles" per distinct wine you can identify. Leave
          "label_text" empty — the point is which wines are in front of me, not the wording.
        - scene "list": one entry in "bottles" per wine printed on the list, read in order down the
          left column and then the right. Leave "label_text" empty. This is not optional and it is
          not a transcription task in the usual sense: a legible list with an empty "bottles" array
          is a failed reading. Skip only the letterhead, section headings ("WHITE", "RED",
          "ORANGE", "GLASS"), page numbers and any line with no wine on it, and say in "notes" how
          many lines you skipped.
          For each list line: the wine's name is what is printed — producer, cuvée and region in
          that order, with the year taken out into "vintage". Text in brackets after the name is the
          grape or the blend: put those in "grapes" ("Xarel·lo, Macabeu, Parellada" → three grapes).
          "GLASS - 14" is "glass_price"; "BOTTLE - 54" is "price". When only a glass price is
          printed, leave "price" null and fill "glass_price". Never mix the two.
          Then rank the lines against each other: "list_rank" is 1 for the bottle on this page you
          would order first, 2 for the next, down to the last line. Every line gets its own number
          — the numbers run 1 to the number of wines on the page and no two lines share one. This
          is a comparison down the page, not a score, so nine decent wines still have a first and a
          last, and "fit_reason" says what puts this bottle there rather than somewhere else.

        STEP 3 — fill the rest ONLY from those words plus what the photo plainly shows. A field you
        cannot justify that way stays "" or null. Never fill a gap with the wine it probably is.
        Grounding rules, no exceptions:
        - "vintage" must be a four-digit year you can point to in "label_text" or on the list.
          750ml, 75cl, 12.5%, a lot number, a page number and a barcode are never a year. Doubtful
          means null.
        - "alcohol" must be an ALC x.x% BY VOL figure you can point to in "label_text" or on the
          list. A wine list almost never prints one, so null is the normal answer there. Never
          estimate it from grape, appellation or region.
        - "grapes" holds only varieties printed on the label or the list, or the ones the
          appellation legally requires when the label is silent. Otherwise [].
        - "price" is a number printed on a tag or list next to this bottle, nothing else.
        - "type" is the wine style. On a list, a section heading such as RED, WHITE, ORANGE or
          SPARKLING is a reliable guide when the line itself does not say.

        STEP 4 — check your own work before replying. Re-read "label_text" (or the list line) and
        confirm: producer and name are not swapped or merged, the vintage is not the volume or ABV,
        the appellation is a place and not the brand, accents are copied exactly, and on a list you
        have one entry per wine rather than one entry for the page and "list_rank" uses every
        number from 1 to the number of wines exactly once. Fix what you can now. Then list
        every field you are still unsure about in "uncertain_fields", and in "next_tip" write the
        one photo or check that would settle the most (for example "photograph the back label for
        the ABV", "move closer so the vintage is legible", "shoot the left column and the right
        column separately so no line is cut off"). Empty "uncertain_fields" and empty "next_tip"
        only when everything is clearly legible.

        Reply with ONLY this JSON:
        {
          "scene": "bottle"|"shelf"|"list"|"other",
          "bottles": [
            {
              "producer": string,
              "name": string,
              "vintage": number|null,
              "country": string,
              "region": string,
              "appellation": string,
              "grapes": [string],
              "type": "red"|"white"|"rose"|"sparkling"|"dessert"|"fortified"|"orange",
              "alcohol": number|null,
              "barcode": string,
              "price": number|null,
              "glass_price": number|null,
              "currency": string,
              "drink_from": number|null,
              "drink_to": number|null,
              "expert_notes": string,
              "flavor_tags": [string],
              "personal_fit": number,
              "list_rank": number,
              "fit_reason": string,
              "value_verdict": "bad"|"fair"|"good"|"great"|"unknown",
              "confidence": "high"|"medium"|"low",
              "label_text": string,
              "read_notes": string,
              "uncertain_fields": [string],
              "next_tip": string
            }
          ],
          "notes": string
        }

        Field rules:
        - "producer" is the winery, estate, château, domaine, bodega, cantina or brand that made the
          wine — normally the largest or most repeated name on the front label, or the first words of
          a list line. "name" is the cuvée, vineyard or bottling name with the producer and the year
          taken out (for example "Barolo Riserva", "Les Clos", "Reserva"). Never put the same words
          in both fields.
        - "vintage" is the four-digit year printed on the label or the list, between 1900 and
          ${today().take(4)}. Do not read 750ml, 75cl, 12.5%, a lot number or a barcode as a year.
          Non-vintage bottles get null.
        - "appellation" is the legally defined origin (AOC/AOP, DOC/DOCG, DO, AVA, GI): a place, never
          a brand. "region" is the wider region around it, "country" the country.
        - "grapes" holds only varieties printed on the label or the list, plus the ones the appellation
          legally requires when the label is silent (Barolo is Nebbiolo, Chablis is Chardonnay, Chianti
          Classico is mostly Sangiovese). When that is not certain, use [].
        - "type" is the wine style, not the colour of the glass or the bottle.
        - "alcohol" is the ALC x.x% BY VOL figure printed on the label or the list. Read it whenever it
          is legible and use null when it is not. Never estimate it from the grape, the appellation or
          the region: an invented ABV makes the alcohol limit lie.
        - "price" is the number on the shelf tag, or the BOTTLE price on a wine list, in $currency.
          Null when no price is visible. Shelf tags sit above or below a bottle and often belong to
          the wine next to it: pair one with a bottle only when the alignment is unambiguous.
        - "glass_price" is the per-glass price on a wine list, in $currency. Null everywhere else.
        - "value_verdict" is only for a shop shelf: is the price good for this wine. On a restaurant
          list always write "unknown", because a list price carries a markup and is not a deal.
        - "flavor_tags" is three to six short tasting words the label or the list actually supports
          (blackcurrant, cedar, butter, vanilla). Use [] when the label says nothing about how it
          tastes: those tags are what checks the bottle against the oak I avoid.
        - "expert_notes": only for the "bottle" scene — two to four sentences in the voice of a good
          sommelier, from general wine knowledge about this wine and appellation. No scores, critics,
          awards or prices you cannot see. Leave it "" for a shelf or a list; "fit_reason" is enough
          there.
        - "drink_from"/"drink_to": realistic drinking-window years, or null. Today is ${today()}. Be
          conservative rather than optimistic.
        - "personal_fit" is 1-5 for how well this wine suits MY TASTE PROFILE below (3 = neutral,
          5 = exactly my kind of wine). "fit_reason" is one or two short sentences: what it tastes
          like and why it fits or does not.
        - When MY TASTE PROFILE is empty there is nothing to fit: every "personal_fit" may honestly
          be 3. Do not let that flatten the page. Rank on the wines themselves — which is the more
          interesting bottle, which suits the food the list suggests, which is the fairer price for
          what it is — and make "fit_reason" say what this bottle has that the others do not.
        - "list_rank" is your order down the page: 1 = the bottle you would order first from this
          list, then 2, 3 and so on, each number used once, 1 to the number of wines read. It is a
          comparison between these bottles, so it must not be the same number twice; a page where
          every line ties is a ranking you have not done. It is null for a single bottle and for a
          shop shelf.
        - On a list, "fit_reason" is never empty: it is the one sentence that tells these cards
          apart, so it names the difference ("the only rosé here, and half the price of the cavas").
        - "confidence": "high" only when producer, name and vintage are all clearly legible, the
          ABV was read or the back label is not visible to read it from, and you are sure which
          bottling this is. "medium" when one field had to be inferred — name it in
          "uncertain_fields". "low" when the label is hard to read, partly hidden, or you are
          guessing at the producer. When in doubt, pick the lower confidence.
        - "barcode" only when the digits are legible.
        - "notes" is for the frame as a whole: bottles you skipped and why, a heading you did not
          count as a wine, glare, a price tag you could not align, anything doubtful. Empty when
          nothing was.
        - List each distinct wine once, even when the shelf holds several bottles of it. A short
          correct list beats a long guessed one — but a readable list with no entries is not short,
          it is empty, and that is a failure.
        - Keep the answer small enough to finish. For a shelf or a list: no "expert_notes", no
          drinking window unless the page prints one, and "fit_reason" of at most twelve words. A
          page of wines that stops halfway is worse than a page of terse lines that completes.
        - Read at most 24 wines from one photo. If the page holds more, read the first 24 and say in
          "notes" how many were left so I can shoot the rest.
        - JSON only. No markdown fences, no commentary.

        BOTTLES ALREADY IN MY CELLAR — when a bottle is one of these, reuse the exact spelling:
        $known

        MY TASTE PROFILE (mytaste.md):
        $tasteMd
    """.trimIndent()
    }

    /**
     * The judgement on a list the phone already read.
     *
     * No image goes up: the wines, regions and prices are text, and the model answers as the
     * sommelier a diner asks when every name on the page is unfamiliar. It used to be asked only
     * how well each bottle matched `mytaste.md`, and with that file empty it honestly scored every
     * line neutral — which left a nine-bottle page ranked on price, the one thing the reader could
     * already see. So the judgement is about the wines themselves now: what each one is, and which
     * is the better bottle for the money. The taste file moves a wine up or down; it is no longer
     * the only thing the model is allowed to consult.
     */
    fun listJudge(tasteMd: String, currency: String, bottles: List<ScannedLabel>): String {
        val menu = bottles.mapIndexed { index, label ->
            val wine = label.wine
            buildString {
                append("${index + 1}. ${wine.title}")
                val place = listOf(wine.appellation, wine.region, wine.country)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(", ")
                if (place.isNotBlank()) append(" — ").append(place)
                if (wine.grapes.isNotEmpty()) {
                    append(" (").append(wine.grapes.joinToString(", ")).append(")")
                }
                append(" — ").append(wine.type.label.lowercase())
                wine.price?.let {
                    append(", bottle ").append(currency).append(' ')
                        .append("%.0f".format(java.util.Locale.US, it))
                }
                label.glassPrice?.let {
                    append(", glass ").append(currency).append(' ')
                        .append("%.0f".format(java.util.Locale.US, it))
                }
            }
        }.joinToString("\n")
        val taste = tasteMd.trim().ifBlank {
            "(I have not told you what I like — rank on the wines)"
        }
        return """
        You are the sommelier a diner asks when every name on the list is unfamiliar. They cannot
        tell a Cava from a Crémant and they are not going to look up nine producers at the table:
        tell them what each wine is, and which one to order.

        The names and prices below were read off the page by the phone. They may be misspelled.
        Do not change a price, do not invent a wine that is not there, and if a line looks garbled,
        judge the wine you can recognise and say so in "what".

        Use what you know. The producer, the appellation, the grape and the vintage are the whole
        point of this answer: that a Chablis is Chardonnay grown cold, that a Barolo needs a
        decade, that a small grower's Cava is not the supermarket one at the same price. Say which
        bottle is worth the money on this page. Never answer that nothing is against a wine, never
        repeat the price back as the reason, and never rank the lines level.

        THE REASON TEST — every "what" and every "why" has to pass it. Put a different wine from
        this page in front of the same sentence: if it would still be true, the sentence is a
        label, not a reason, and it has to be rewritten. "Exceptional producer" is true of every
        bottle that producer makes; "one of the world's greatest wines" is true wherever that
        bottle is poured. Reputation on its own — exceptional, top-tier, world-class, iconic,
        legendary, renowned, prestigious, famous, high-end — tells the reader nothing about the
        wine in front of them. Name the specific thing instead: the grape, the style, the
        appellation's tier, the vintage, the kind of producer (estate-grown, sherry house, co-op),
        or the money. The WINES list gives each line's grapes, place and vintage: those are that
        line's distinctive facts. Never describe a wine with another line's grape, place or
        vintage, and never compare this page against wines you were not given (no "unlike the
        heavier", no "broader", no "cloying", no "on this list" superlatives). Name the grape: a
        rosé made from Trepat says Trepat.

        For every numbered wine reply with exactly one line, in this exact shape:
        number | rank | fit | what | why

        - "number" is the wine's number above.
        - "rank" is 1 for the bottle to order first on this page down to ${bottles.size}, with
          every number from 1 to ${bottles.size} used exactly once and no ties. Rank on which is
          the better wine for what it costs here: the producer's standing, the appellation's
          quality tier, the vintage, the style, and the price asked.
        - "fit" is 1-5 for how well this wine suits MY TASTE FILE below (3 = neutral). When that
          file is empty, score how good the bottle is at its price: 5 a genuinely good bottle,
          3 ordinary, 1 poor.
        - "what" is at most fourteen words on what this wine actually is: the grape and the style,
          the kind of producer or appellation it comes from, and how it tastes ("a Jerez sherry
          house's red from Tintilla, served chilled, dry and saline"). Describe the bottle, not its
          fame. If you do not know the wine, say that ("unfamiliar producer, generic tank-made
          Cava") instead of inventing a reputation, a score, an award or a tasting note.
        - "why" is at most fourteen words on why it sits where it does on this page, and it passes
          the reason test. It is a comparison, so it names what this bottle has that the others
          here do not, or what it costs against them ("the only chilled red here, a third of the
          Albariño's price"). Never blank, never "good wine", never "nice", never a restatement of
          the price, never a producer's reputation.
        - The rank-1 "why" is the answer to "what do I order", so it has to say what it beats on
          this page. If it costs several times what the cheaper lines do, that price is the
          argument to make: say what the money buys, or put a cheaper bottle first.

        Where MY TASTE FILE names something I dislike, move a wine that has it below one that does
        not. That is a nudge, not the ranking: with the file empty the wines still get an order.

        WINES
        $menu

        MY TASTE FILE
        $taste
        """.trimIndent()
    }

    /**
     * The advice question a photographed wine list gets now.
     *
     * The judgement before this asked for one line per wine — 26 rows of five fields — which is a
     * spreadsheet rather than an answer. A diner wants two or three bottles explained, a couple of
     * tempting ones declined, and a glass if the list has one. Advice also survives a small model,
     * where ranking every line spreads the attention thin and the top of the page wins by default —
     * which is why the first three sparkling lines came back as "best first".
     *
     * Wines are referred to by number and never by name: the numbers come from the phone's reading
     * of the page, so the model cannot merge two lines or impose a spelling the cards have to live
     * with, and a pick that names no line is simply wrong.
     */
    fun listAdvice(
        brief: String,
        cellarSummary: String,
        currency: String,
        bottles: List<ScannedLabel>,
        food: String? = null
    ): String {
        val wines = bottles.mapIndexed { index, label ->
            val wine = label.wine
            buildString {
                append("${index + 1}. ${wine.title}")
                append(" — ").append(wine.type.label.lowercase())
                val place = listOf(wine.appellation, wine.region, wine.country)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(", ")
                if (place.isNotBlank()) append(" — ").append(place)
                if (wine.grapes.isNotEmpty()) {
                    append(" (").append(wine.grapes.joinToString(", ")).append(")")
                }
                wine.price?.let {
                    append(" — bottle ").append(currency).append(' ')
                        .append("%.0f".format(java.util.Locale.US, it))
                }
                label.glassPrice?.let {
                    append(", glass ").append(currency).append(' ')
                        .append("%.0f".format(java.util.Locale.US, it))
                }
            }
        }.joinToString("\n")
        val taste = brief.trim().ifBlank {
            "(I have not told you what I like — recommend on the wines themselves)"
        }
        val owned = cellarSummary.trim().ifBlank { "(nothing yet)" }
        val dish = food?.trim().orEmpty().ifBlank { "(none given)" }
        return """
        You are an experienced sommelier helping one diner order from a restaurant wine list.
        Read the WHOLE list before deciding. Then recommend what this diner should order.

        Knowledge rules:
        - Use your general knowledge of regions, grapes, styles, vintages and producers.
          When something is typical rather than printed on the list, say "typically".
        - Do not state producer-specific specifics you cannot be sure of (months in oak,
          blend percentages, exact alcohol, scores, awards). Region- and grape-level
          reasoning is preferred: climate, elevation, soils, grape character, ageing style.
        - Never invent a wine, price or vintage. Refer to wines ONLY by their line number.
        - A wine's colour/style is the section it is listed under. A wine listed under
          ROSADO is a rosé even if the grape is usually red.

        Taste: read MY TASTE below. Treat oak, alcohol and sweetness as preferences.
        Connect your reasons to the bottles I loved when you can.
        Budget: prefer bottles inside my restaurant range; go above it only with a reason.

        Reply in exactly this format, nothing before or after:

        PICK 1 | <line number> | <one-line headline>
        WHY: <2-4 sentences: what it is, why it tastes that way, why it suits me>
        PICK 2 | <line number> | <headline>
        WHY: <2-4 sentences>
        PICK 3 | <line number> | <headline>          (optional)
        WHY: <2-4 sentences>
        GLASS | <line number> | <one sentence>        (best by-the-glass option, if any)
        SKIP | <line number> | <one sentence why>     (2-4 lines, the tempting wrong choices)
        NOTE: <optional one sentence, e.g. a splurge worth knowing about>

        MY TASTE
        $taste

        WHAT I ALREADY OWN (summary)
        $owned

        FOOD (if given)
        $dish

        WINES
        $wines
        """.trimIndent()
    }

    /**
     * The advice question a photographed shop shelf gets.
     *
     * The same shape as [listAdvice] and for the same reason — a diner and a shopper both want two or
     * three bottles explained, not a verdict under every line — with three differences that are
     * facts about a shop rather than a menu: the money is retail, so a printed price can be a bargain
     * or a rip-off and the answer has to say which ([VALUE]); a shelf is read as several photos, so
     * the picks are spread across everything the session read instead of three per frame; and a shop
     * sells bottles, not glasses, so there is no GLASS line.
     *
     * Numbers come from the caller and cover every bottle of the session, which is why the prompt
     * says "across every bottle listed here" rather than "on this page".
     */
    fun shelfAdvice(
        brief: String,
        cellarSummary: String,
        currency: String,
        bottles: List<ScannedLabel>
    ): String {
        val wines = bottles.mapIndexed { index, label ->
            val wine = label.wine
            buildString {
                append("${index + 1}. ").append(wine.title)
                append(" — ").append(wine.type.label.lowercase())
                val place = listOf(wine.appellation, wine.region, wine.country)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(", ")
                if (place.isNotBlank()) append(" — ").append(place)
                if (wine.grapes.isNotEmpty()) {
                    append(" (").append(wine.grapes.joinToString(", ")).append(")")
                }
                wine.price?.let {
                    append(" — ").append(currency).append(' ')
                        .append("%.0f".format(java.util.Locale.US, it))
                }
                label.valueVerdict.takeIf { it.isNotBlank() && it != "unknown" }?.let {
                    append(" — the shelf reads as ").append(it).append(" value")
                }
            }
        }.joinToString("\n")
        val taste = brief.trim().ifBlank {
            "(I have not told you what I like — recommend on the wines themselves)"
        }
        val owned = cellarSummary.trim().ifBlank { "(nothing yet)" }
        return """
        You are an experienced wine buyer in a shop, helping one shopper choose from what is on the
        shelf. Read EVERY bottle listed before deciding. Then say what this shopper should buy.

        Knowledge rules:
        - Use your general knowledge of regions, grapes, styles, vintages and producers.
          When something is typical rather than printed, say "typically".
        - Do not state producer-specific specifics you cannot be sure of (months in oak,
          blend percentages, exact alcohol, scores, awards).
        - Never invent a wine, price or vintage. Refer to bottles ONLY by their number.
        - A bottle's colour/style is the section it is listed under.

        Taste: read MY TASTE below. Treat oak, alcohol and sweetness as preferences.
        Budget: these are retail prices, so the range in MY TASTE is what I would pay in a shop.
        Prefer bottles inside it, and say when a bottle is good value at the price printed.

        Reply in exactly this format, nothing before or after:

        PICK 1 | <number> | <one-line headline>
        WHY: <2-4 sentences: what it is, why it tastes that way, why it suits me>
        VALUE: good|fair|poor — <one clause about the printed price>
        PICK 2 | <number> | <headline>
        WHY: <2-4 sentences>
        VALUE: good|fair|poor — <one clause>
        PICK 3 | <number> | <headline>            (optional)
        WHY: <2-4 sentences>
        VALUE: good|fair|poor — <one clause>
        SKIP | <number> | <one sentence why>       (1-3 bottles, the tempting wrong choices)
        NOTE: <optional one sentence>

        - Three picks at most, across every bottle listed here — not three per photo.
        - VALUE is about the money only: good = a bargain at the printed price, fair = about
          right, poor = overpriced for what it is. Omit the whole VALUE line for a bottle whose
          price is not printed; never guess one.
        - Every SKIP has to say why it is the tempting wrong choice, not just that it is cheap.

        MY TASTE
        $taste

        WHAT I ALREADY OWN (summary)
        $owned

        WINES
        $wines
        """.trimIndent()
    }

    /**
     * "Why it tastes this way" for one bottle.
     *
     * Text only, and about the wine rather than about the reader: the reason it is worth a request
     * is that a label says what a bottle is and never why it tastes of anything. Every claim has to
     * carry a cause, and the confidence is asked for per claim so the page can print the solid ones
     * plainly and hedge the rest instead of averaging the whole answer into mush.
     */
    fun bottleInsight(wine: Wine, label: ScannedLabel?, brief: String): String {
        val printed = label?.labelText?.trim().orEmpty()
        val record = buildString {
            appendLine("producer: ${wine.producer.ifBlank { "(not read)" }}")
            appendLine("name: ${wine.name.ifBlank { "(not read)" }}")
            appendLine("vintage: ${wine.vintage ?: "(not printed)"}")
            appendLine("type: ${wine.type.label}")
            if (wine.grapes.isNotEmpty()) appendLine("grapes: ${wine.grapes.joinToString(", ")}")
            val place = listOf(wine.appellation, wine.region, wine.country)
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (place.isNotEmpty()) appendLine("place: ${place.joinToString(", ")}")
            if (wine.alcohol != null) appendLine("alcohol: ${wine.alcohol}%")
            if (wine.drinkFrom != null || wine.drinkTo != null) {
                appendLine("drink window: ${wine.drinkWindowLabel}")
            }
            if (wine.expertNotes.isNotBlank()) appendLine("notes already on the record: ${wine.expertNotes.take(400)}")
        }.trimEnd()
        val taste = brief.trim().ifBlank { "(nothing yet — explain the wine itself)" }
        return """
        You are a wine writer explaining one bottle to the person who owns it. The question is not
        what it tastes of but WHY it tastes that way: reason from region to grape to vintage to
        producer, and make every sentence a cause and an effect ("cool Atlantic nights keep the
        acidity high, so the fruit stays lifted rather than jammy").

        What you may use:
        - The label's own words, below, and the record beside them. Never contradict either.
        - General knowledge of regions, grapes, appellations, vintages and producers.
        - Producer-specific claims (oak regimen, yields, vine age) ONLY when you are confident. When
          you are not, write the regional or grape truth instead.
        Never invent awards, scores, prices, or a vintage that is not on the record.

        Confidence means:
        - high: widely true for this region, grape, style or a well-documented vintage.
        - medium: typical, but this producer or site may differ — write it as typical.
        - low: a guess. Omit it rather than shipping it.

        Reply with JSON only, no prose before or after, exactly these keys:

        {
          "headline": "one sentence on what this bottle is, in your own words",
          "why_it_tastes_this_way": [
            { "claim": "cause -> effect sentence", "basis": "region|grape|vintage|producer|style", "confidence": "high|medium|low" }
          ],
          "tasting_profile": { "body": "light|medium|full", "tannin": "low|medium|high", "acidity": "low|medium|high", "notes": ["three to six short flavour notes"] },
          "drinking_window": { "from": 2026, "to": 2032, "confidence": "high|medium|low" },
          "food_pairings": ["two or three short, ordinary suggestions"],
          "for_you": "one or two sentences linking it to what I like, or null"
        }

        Rules for the fields:
        - Three to six claims. Each one names its basis; no claim is a flavour list.
        - The drinking window is a year range on the record's calendar; a bottle with no vintage gets
          its range from the style, never from a guess at the year.
        - "for_you" is about MY TASTE below. If it says nothing useful, null.
        - No scores, no awards, no prices, no serving temperatures in Celsius and Fahrenheit both.

        WHAT THE LABEL PRINTED
        ${printed.ifBlank { "(nothing transcribed — work from the record)" }}

        THE RECORD
        $record

        MY TASTE
        $taste
        """.trimIndent()
    }

    /**
     * The recovery pass for the one failure that matters in a restaurant: a legible wine list that
     * came back with nothing. It asks for the wines and only the wines, with no scene decision and no
     * transcription to hide behind, and it is sent only after an empty first answer — one extra
     * request on a failure, never on a good read.
     */
    fun listRetry(tasteMd: String, currency: String): String = """
        A photo of a printed wine list did not give up any wines on the first reading. Read it again
        and list every wine on it. This is a reading task, not a summary: I need the individual wines,
        one JSON object each, in order down the left column and then down the right.

        For each line:
        - "producer" is the winery or bodega that begins the line;
        - "name" is the rest of the wine name, without the producer and without the year;
        - "vintage" is the four-digit year at the end of the line, or null when there is none;
        - "region" is the appellation or region printed after the name ("Rías Baixas", "Rioja",
          "Priorat", "Penedès");
        - "grapes" are the varieties given in brackets after the name, split into separate entries;
        - "price" is the BOTTLE price, "glass_price" is the GLASS price, both in $currency, and either
          is null when only the other is printed;
        - "type" follows the section heading the line sits under (WHITE, RED, ORANGE, SPARKLING).
        Skip the heading lines themselves, the letterhead, page numbers and anything that is not a
        wine. Never invent a wine, a vintage or a price: a line you cannot read is skipped, and you
        say so in "notes".

        Also rank the lines against each other in "list_rank": 1 for the bottle you would order
        first from this page, then 2, 3 and so on, with a different number on every line and no
        number left unused. "fit_reason" says in at most twelve words what sets that bottle apart
        from the rest of the page — a grape, a style, a producer, a price. Never leave it empty.

        Reply with ONLY this JSON:
        {
          "bottles": [
            {
              "producer": string,
              "name": string,
              "vintage": number|null,
              "country": string,
              "region": string,
              "appellation": string,
              "grapes": [string],
              "type": "red"|"white"|"rose"|"sparkling"|"dessert"|"fortified"|"orange",
              "alcohol": number|null,
              "price": number|null,
              "glass_price": number|null,
              "currency": string,
              "flavor_tags": [string],
              "personal_fit": number,
              "list_rank": number,
              "fit_reason": string,
              "confidence": "high"|"medium"|"low"
            }
          ],
          "notes": string
        }

        MY TASTE PROFILE (mytaste.md):
        $tasteMd
    """.trimIndent()

    /**
     * The whole photo in one request: what it is, every wine on it, and the order they belong in.
     *
     * The scene used to be a separate question, which meant two round trips before a list said
     * anything — and on a free tier a round trip is the expensive part. It costs ten tokens to
     * answer here, next to a transcription the model is doing anyway.
     *
     * The format is still plain lines, not JSON: a cut-off answer loses its last lines and the rest
     * parses. Eighteen nested objects in one free-tier reply is how a list came back unreadable.
     */
    fun listLines(tasteMd: String, currency: String): String {
        val taste = tasteMd.trim().ifBlank {
            "(my taste file is empty — rank on the wines)"
        }
        return """
        Read the photo, and copy every wine in it into lines. No JSON. No headings. No commentary
        before or after.

        The first line is the scene, exactly one of these four:
        SCENE: list
        SCENE: shelf
        SCENE: bottle
        SCENE: other

        "list" is a printed wine list, menu or drinks card, mostly text. "shelf" is two or more
        bottles standing together. "bottle" is one bottle's label filling the frame. "other" is no
        wine in the photo at all.

        Then one line per wine, in the order printed: down the left column, then the right. Separate
        the fields with " | " in this exact order:

        producer | name | vintage | region | grapes | type | bottle price | glass price | confidence | rank | what | why

        Rules:
        - Write "-" for any field the page does not print. Never guess and never estimate.
        - "name" is the rest of the wine name without the producer and without the year.
        - "vintage" is the four-digit year, or "-". 750ml, an ABV, a page number and a lot code are
          never years.
        - "grapes" is the text in brackets after the name, separated by ";". "-" when there is none.
        - "type" is red, white, rose, sparkling, dessert, fortified or orange. Take it from the
          section heading (WHITE, RED, ORANGE, SPARKLING) when the line itself does not say.
        - "bottle price" is the BOTTLE figure, "glass price" is the GLASS figure, in $currency,
          numbers only. Never swap them. "-" when one is not printed.
        - "confidence" is high, medium or low, for that line on its own.
        - "rank" is where this line belongs on this page: 1 is the one you would order first, 2 the
          second, and so on. Use every number from 1 to the number of wines exactly once — no ties,
          no repeats, never blank. You know these wines: rank on the producer, the appellation, the
          vintage, the style and what the price asks, not on the order they are printed in. Where
          my taste file below says something, it moves a wine up or down.
        - "what" is at most fourteen words on what this wine actually is: the grape and the style,
          the kind of producer or appellation, and how it tastes. Say "unfamiliar producer" rather
          than inventing a reputation, a score or an award.
        - "why" is one short line, at most fourteen words, saying what puts this wine where it is
          on this page, and it has to pass one test: put a different wine from this page in front
          of the sentence — if it would still be true, it is a label, not a reason, so rewrite it.
          Reputation alone ("exceptional producer", "top-tier", "world-class", "one of the
          greatest") is never a reason. Name the line's own grape, place, vintage or price, never
          another line's, and never compare against wines off this page (no "unlike the heavier",
          no "broader", no "cloying", no "on this list"). For rank 1 it says why it beats the
          rest, and if it costs several times what the cheaper lines do, that price is part of
          the answer. Never "good wine", never "nice", never blank.
        - Skip the restaurant's name, section headings, page numbers and anything that is not a wine.
        - Copy the spelling and the accents exactly as printed. If a line cannot be read at all, skip
          it rather than inventing it.
        - At most 24 lines. If the page holds more, stop at 24.

        MY TASTE FILE
        $taste
        """.trimIndent()
    }

    /**
     * The small print, on its own. A phone photo of a 1.5-litre label loses the figures the buying
     * decision turns on — ABV above all — and they survive in the centre crop. Asking for them
     * separately keeps that request cheap: one image, about a hundred tokens of answer, and it runs
     * only when the first read came back without the numbers.
     */
    fun finePrint(currency: String): String = """
        This crop is the centre of a photo of one wine label. Read ONLY the small print and report
        what it actually says. Nothing is estimated: a figure you cannot see is null.

        Look for, in this order:
        - the alcohol figure: "ALC 13.5% BY VOL", "13,5% vol", "14.5% alc./vol.";
        - the vintage, as a four-digit year (never the volume, an ABV, a lot number or a barcode);
        - the volume (75cl, 750ml, 1.5L) — this is not the vintage;
        - the appellation or legal origin and the country;
        - the grape varieties, and "mis en bouteille" / estate-bottling wording;
        - the barcode digits when legible.

        Reply with ONLY this JSON:
        {
          "alcohol": number|null,
          "vintage": number|null,
          "volume_ml": number|null,
          "appellation": string,
          "region": string,
          "country": string,
          "grapes": [string],
          "barcode": string,
          "label_text": string,
          "uncertain_fields": [string],
          "next_tip": string,
          "notes": string
        }

        "label_text" is the small print copied word by word, keeping the spelling and accents.
        "uncertain_fields" names the figures still unreadable, and "next_tip" the one photo that would
        settle them. Currency for any price is $currency. JSON only, no commentary.
    """.trimIndent()

    /**
     * Several bottles in one photo. Groq bills an image at a fixed 2048 tokens whatever its size,
     * so four bottles in one frame cost a quarter of four separate requests — this is what makes
     * a shelf trip finish in a couple of minutes instead of twenty.
     *
     * The static rules come first and the per-shop hints last, so repeated calls keep the same long
     * prefix and Groq's prompt cache can discount it.
     */
    fun labelFrame(
        tasteMd: String,
        currency: String,
        knownBottles: List<String> = emptyList(),
        maxBottles: Int = 5
    ): String {
        val known = if (knownBottles.isEmpty()) {
            "(my cellar is empty — there is nothing to match against)"
        } else {
            knownBottles.distinct().take(20).joinToString("\n") { "- $it" }
        }
        return """
        You are a careful wine-label reader inside a personal cellar app. The photo shows bottles
        standing on a shelf, usually three to five of them.

        Identify each bottle separately, left to right. Accuracy first: take the extra seconds and
        read slowly rather than guessing quickly. For every bottle:
        1. Read the words printed on that label first.
        2. Fill only the fields those words support. Anything you cannot see stays "" or null — never
           fill a gap with the wine this bottle probably is. A vintage must be a year you can point
           to (never 750ml, an ABV or a lot number); an ABV must be a printed ALC x.x% figure, never
           an estimate; doubtful means null.
        3. Skip bottles that are turned away, buried in glare, or cut off at the edge of the frame,
           and count them in "not_read".
        4. Before replying, re-check each bottle against the volume and ABV, and list
           anything doubtful in "uncertain_fields" with the one re-shot that would settle it in
           "next_tip".

        A short correct list beats a long guessed one. Two bottles of the same wine side by side are
        listed once.

        Reply with ONLY this JSON:
        {
          "bottles": [
            {
              "producer": string,
              "name": string,
              "vintage": number|null,
              "country": string,
              "region": string,
              "appellation": string,
              "grapes": [string],
              "type": "red"|"white"|"rose"|"sparkling"|"dessert"|"fortified"|"orange",
              "alcohol": number|null,
              "barcode": string,
              "drink_from": number|null,
              "drink_to": number|null,
              "expert_notes": string,
              "flavor_tags": [string],
              "personal_fit": number,
              "fit_reason": string,
              "price_estimate": number|null,
              "currency": string,
              "confidence": "high"|"medium"|"low",
              "key_lines": string,
            "uncertain_fields": [string],
            "next_tip": string
            }
          ],
          "not_read": number,
          "notes": string
        }

        Field rules:
        - "key_lines": the words that identify this bottle, copied as printed — producer line, cuvée,
          vintage, appellation, ABV — separated by " | ". Not a full transcription; just the words
          you actually used. Empty when the label was barely legible.
        - "producer" is the winery, estate, château, domaine, bodega or brand that made the wine.
          "name" is the cuvée or bottling with the producer and the year taken out. Never put the
          same words in both.
        - "vintage" is the four-digit year printed on that label, between 1900 and ${today().take(4)}.
          A volume (750ml), an ABV, a lot number or a barcode is not a year. Non-vintage is null.
        - "appellation" is the legal origin (AOC/AOP, DOC/DOCG, DO, AVA, GI) — a place, never a brand.
        - "grapes": only varieties printed on the label, or the ones the appellation legally requires
          when the label is silent. Otherwise [].
        - "expert_notes": at most two sentences of general wine knowledge about this bottling. No
          scores, awards or prices you cannot see.
        - "personal_fit": 1-5 against MY TASTE PROFILE (3 = neutral, 5 = exactly my kind of wine).
        - "price_estimate": only when you have a defensible retail price; otherwise null.
        - "confidence" is per bottle: "high" only when producer, name and vintage are all legible.
        - "notes": what you could not read, in one short sentence.
        - If there is no wine in the photo at all, return an empty "bottles" array and say so.
        - JSON only. No markdown fences, no commentary.

        Today is ${today()}. Currency: $currency.

        BOTTLES ALREADY IN MY CELLAR — reuse the exact spelling when one of these appears:
        $known

        MY TASTE PROFILE (mytaste.md):
        $tasteMd
    """.trimIndent()
    }

    /**
     * A slower second pass, sent with the first reading in the prompt. Reading the same photo
     * twice catches the errors the first pass was confident about.
     */
    fun labelRecheck(tasteMd: String, currency: String, firstReading: String): String = """
        You are a careful wine-label reader doing a second pass over a photo you already read once.
        You receive two images: a zoomed crop of the label, and the whole photo.

        My first reading was:
        $firstReading

        Read the label again, slowly, word by word, and correct that reading. Check these in
        particular:
        - the producer name and the cuvée name, which are the easiest to swap or merge;
        - the vintage, which is easily confused with the volume, the ABV or a lot number;
        - the appellation and region, which are easily confused with the brand;
        - accents and spelling in French, Italian, Spanish and German names.

        Change a field only when you can now read it better; keep the ones that were right. Reply
        with ONLY the same JSON object as before, with "label_text" transcribed again from scratch
        and one extra field, "corrected": one sentence listing what you changed (empty when nothing
        changed).

        Today is ${today()}. Currency: $currency.

        MY TASTE PROFILE (mytaste.md):
        $tasteMd
    """.trimIndent()

    fun chat(
        tasteMd: String,
        cellar: CellarData,
        currency: String,
        rules: PalateRules? = null,
        /** How many bottles the digest may list. A provider with a small minute budget gets fewer. */
        digestLimit: Int = 55,
        /** The dish named by the question or the standing food answer; it changes the gate lines. */
        dish: Dish? = null,
        /**
         * What has actually been drunk, from the drink log. Blank on an install that has never
         * recorded a bottle, which is a different thing from a record that is empty.
         */
        drinks: String = ""
    ): String = """
        You are Wine Verdict, the assistant inside my personal wine cellar app. You are a knowledgeable,
        plain-spoken sommelier who also keeps track of my inventory.

        Today is ${today()}. My currency is $currency.

        MY TASTE PROFILE (mytaste.md — hand-written preferences plus the bottles I love):
        $tasteMd

        MY CELLAR RIGHT NOW (bottles I own; "qty 0" means it is gone):
        ${cellarDigest(cellar, rules, digestLimit, dish)}

        WHAT I HAVE DRUNK (recorded bottles I actually opened, newest first — a record, not a plan):
        ${drinks.ifBlank { "Nothing has been recorded yet." }}

        How to answer:
        - When I ask what to drink or what to open, recommend only bottles that appear in MY CELLAR with qty above 0. Name the vintage and producer exactly as listed.
        - Copy each bottle's name from its own line as it stands. Never put the region, the grape, the appellation or another bottle's name inside it.
        - What you know about a bottle is its line in MY CELLAR and nothing more: name, type, region, appellation, grapes, alcohol, drinking window, my note, the flavours line and any "app gate check". General knowledge of a grape, style or appellation is allowed, but only spoken as general ("a Barolo this young is tannic"), never as a fact about that bottle ("its restrained oak", "cool-site fruit", "bright acidity", "its likely fuller oak") unless its line says so. When a line carries no tasting words, add none: judge fit from type, grape, region, alcohol, window and my note.
        - Say why each pick matches the food, the occasion or my taste. Mention the drinking window when it matters (for example "drink now, past its peak", or "hold, still young").
        - A line marked "sourced notes" was read from published pages I looked up: you may state it as fact about that bottle. Everything else on the line is a record field, not a tasting note.
        - When I ask for a plan — a meal, a day, a number of bottles — use this shape and nothing else:
          First line: how many bottles, and the order to open them.
          Then, for each bottle, four short lines:
          the bottle, vintage as listed
          When: when to open it, and how long to chill or decant it
          With: which part of the meal it is for
          Why: one sentence, from that bottle's line, using the general-knowledge rule above
          Last line: what to do if a bottle runs out or is corked.
        - For a plain question, answer in a short list or one paragraph, under about 160 words. No filler, no heading on a simple answer.
        - Plain text only. No markdown: no asterisks for emphasis, no #, no backticks, no bold. A list is "- " at the start of a line, nothing else. Use a blank line between bottles so it is readable.
        - When I ask what I drank — tonight, yesterday, last week, this month — answer from WHAT I HAVE DRUNK. Each line there is a bottle that really left my cellar, with the day and the verdict I gave at the time. Never say you have no record while a line exists, and never stand in a bottle for a day with none: say nothing was recorded for that day.
        - A bottle in WHAT I HAVE DRUNK is gone from stock even when MY CELLAR still lists it: the cellar count is what is left, the drink is the fact. Do not offer tonight's bottle as something to open.
        - Prices in my cellar are what I paid; do not present them as current market prices.
        - If I attach a photo (a shelf, a wine list, a label), describe what you can actually read. Estimate a price only when a tag is visible, and say when you are unsure.
        - Never invent a bottle, price, score or availability. If you do not know, say so.
        - My taste file is the contract, not background reading. Apply its gates and its scoring before you pick, and follow its rules about how to answer: how many bottles to name, why the runner-up loses, and what not to say. If it forbids a sign-off, do not write one.
        - If my taste file disagrees with a cellar record — a region, a grape, a drinking window — trust the file and say the record is wrong. Cellar records come from label scans and can be mistaken.
        - Never repeat the bottle or the grape of your previous answer unless this question makes the earlier logic inapplicable, and then say which part no longer applies.
        - The app has already checked my taste file's numeric gates against each bottle record and appended "app gate check" where one fails, passes only by an allowance, or cannot be checked. A check marked unknown means the fact is missing: say it cannot be checked, and never call a gate passed unless the record shows the fact. A check marked conditional is a pass for this dish: state the number and the reason out loud ("14.2%, over your 14.0, allowed because there is food") rather than quietly accepting it.
        - Do not assert that a bottle passes a gate ("cool site", "under 14%", "vineyard-specific") unless MY CELLAR above gives its alcohol or its place. When that is missing, say the gate cannot be checked from what I have.
    """.trimIndent() + dishNote(dish)

    /** The dish the gates were checked against, so the model knows why a line says conditional. */
    private fun dishNote(dish: Dish?): String = if (dish == null) {
        ""
    } else {
        "\n\nThe dish in this question: ${dish.name}${if (dish.spicy) ", spicy" else ""}. " +
            "A gate line marked conditional is a pass only for this dish: name its number and the reason."
    }

    private fun cellarDigest(
        cellar: CellarData,
        rules: PalateRules? = null,
        limit: Int = 55,
        dish: Dish? = null
    ): String {
        if (cellar.wines.isEmpty()) return "(my cellar is empty)"
        val stocked = cellar.wines.filter { it.quantity > 0 }
        if (stocked.isEmpty()) return "(every bottle in my cellar is marked as drunk or sold)"

        val ordered = stocked.sortedWith(
            compareByDescending<Wine> { it.favorite }
                .thenByDescending { it.rating ?: 0 }
                .thenByDescending { it.quantity }
        )
        val shown = ordered.take(limit)
        val lines = shown.mapIndexed { index, wine ->
            buildString {
                append("${index + 1}. ${wine.title}")
                append(" | ${wine.type.label}")
                val origin = listOf(wine.region, wine.country).filter { it.isNotBlank() }.joinToString(", ")
                if (origin.isNotBlank()) append(" | $origin")
                if (wine.appellation.isNotBlank()) append(" | ${wine.appellation}")
                if (wine.grapes.isNotEmpty()) append(" | ${wine.grapes.joinToString("/")}")
                wine.alcohol?.let { append(" | ${"%.1f".format(java.util.Locale.US, it)}% vol") }
                append(" | drink ${wine.drinkWindowLabel}")
                append(" (${wine.drinkStatus().label})")
                append(" | qty ${wine.quantity}")
                val place = cellar.locationName(wine.locationId)
                if (place.isNotBlank() && place != "Unassigned") {
                    append(" | $place")
                    if (wine.slot.isNotBlank()) append(" / ${wine.slot}")
                }
                Verdict.of(wine.rating)?.let { append(" | my verdict: ${it.label}") }
                // A heart with no word is the old way of saying the same thing; never both.
                if (wine.favorite && wine.rating != Verdict.LOVED.rating) append(" | favourite")
                wine.price?.let { append(" | paid ${wine.currency} ${"%.2f".format(java.util.Locale.US, it)}") }
                if (wine.flavorTags.isNotEmpty()) {
                    append(" | flavours: ${wine.flavorTags.take(4).joinToString(", ")}")
                }
                if (wine.expertNotes.isNotBlank() && wine.notesSource.isNotBlank()) {
                    append(" | sourced notes: ${wine.expertNotes.replace("\n", " ").take(140)}")
                }
                if (wine.myNotes.isNotBlank()) {
                    append(" | my note: ${wine.myNotes.replace("\n", " ").take(100)}")
                }
                if (rules != null) {
                    // These are bottles the user already owns: a hard gate over the line is a
                    // reason not to buy another, not a reason to talk them out of the glass.
                    val gateNote = rules.digestNote(wine, dish, GateScope.OWNED)
                    if (gateNote.isNotBlank()) append(" | app gate check: $gateNote")
                }
            }
        }
        val extra = stocked.size - shown.size
        val suffix = if (extra > 0) "\n($extra more bottles are in the cellar but not listed here.)" else ""
        return lines.joinToString("\n") + suffix
    }
}