package com.vinoteca.app.data

/**
 * Three things to look for, from the taps only.
 *
 * This is a shortlist of styles, not bottles: the app has no catalogue, so pretending to know a
 * specific wine would be the same invention the file is meant to prevent. Each line names a style,
 * a price band and the reason it follows from the answers — and the user can scan one when they find
 * it, because the label reader already matches a found bottle.
 */
object Shortlist {

    data class Pick(val look: String, val why: String)

    fun forProfile(profile: PalateProfile): List<Pick> {
        val band = bandWords(profile)
        val avoids = profile.choices(QuickStart.Q_AVOID)
        val dry = profile.choice(QuickStart.Q_SWEET) != null &&
            profile.choice(QuickStart.Q_SWEET) != QuickStart.SWEET_SWEETER
        val noOak = QuickStart.AVOID_OAK in avoids
        val light = QuickStart.AVOID_STRONG in avoids

        val picks = mutableListOf<Pick>()

        when (profile.choice(QuickStart.Q_STYLE)) {
            QuickStart.STYLE_CRISP -> {
                picks += Pick(
                    "A Chablis or Muscadet, $band",
                    "the crisp, chalky end of the axis you picked"
                )
                picks += Pick(
                    "A dry Riesling from Alsace, $band",
                    "same acid, more perfume"
                )
                picks += if (noOak) {
                    Pick("An unoaked Chardonnay, $band", "the body you want without the oak you don't")
                } else {
                    Pick("A white Burgundy, $band", "the same axis with more weight")
                }
            }
            QuickStart.STYLE_SMOOTH -> {
                picks += Pick(
                    "A Pinot Noir from Oregon or Burgundy, $band",
                    "mellow tannin, texture first"
                )
                picks += Pick(
                    "A Cru Beaujolais, $band",
                    "same softness, more freshness"
                )
                picks += Pick(
                    "A Syrah from the northern Rhône, $band",
                    "a step darker without losing the smoothness"
                )
            }
            QuickStart.STYLE_BIG -> {
                picks += Pick(
                    "A cool-site Syrah, $band",
                    "body and structure without jam"
                )
                picks += Pick(
                    "A structured Cabernet Sauvignon, $band",
                    "the bold end of the axis"
                )
                picks += Pick(
                    "A Malbec or a Douro red, $band",
                    "weight and grip at the price you gave me"
                )
            }
            QuickStart.STYLE_SWEET -> {
                picks += Pick(
                    "An off-dry Riesling, $band",
                    "sweetness balanced by acid"
                )
                picks += Pick(
                    "A demi-sec Vouvray, $band",
                    "the same balance with more body"
                )
                picks += Pick(
                    "A Moscato d'Asti, $band",
                    "light, low alcohol, gently sweet"
                )
            }
            else -> {
                picks += Pick(
                    "A dry Cabernet Franc, $band",
                    "structure and freshness in one bottle — a good first test"
                )
                picks += if (dry) {
                    Pick("A Chablis, $band", "the crisp end, since you have no white data at all")
                } else {
                    Pick("An off-dry Riesling, $band", "the sweet end, in its most refreshing form")
                }
                picks += Pick(
                    "A cool-site Syrah, $band",
                    "the bold end, if you want to test the other direction"
                )
            }
        }

        if (light) {
            // Keep the third pick from contradicting the ceiling the user just set.
            picks[2] = Pick(
                "A lighter-bodied red at 13% or under, $band",
                "the same direction, inside the alcohol limit you asked for"
            )
        }

        return picks.distinctBy { it.look }.take(3)
    }

    private fun bandWords(profile: PalateProfile): String =
        when (profile.choice(QuickStart.Q_BUDGET)) {
            QuickStart.BUDGET_UNDER_12 -> "under \$12"
            QuickStart.BUDGET_12_20 -> "at \$12–20"
            QuickStart.BUDGET_20_35 -> "at \$20–35"
            QuickStart.BUDGET_35_UP -> "at \$35 and up"
            else -> "in your usual band"
        }
}