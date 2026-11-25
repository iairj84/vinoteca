# Design notes

Why the scan, the verdict and the ranking work the way they do. The product README is
../README.md; release notes are ../CHANGELOG.md.

## Scanning a shelf quickly

Bottles per photo is what decides throughput, because providers bill per request and per image, not
per bottle. Groq's free tier gives each model 8,000 tokens a minute, 30 requests a minute and
200,000 tokens a day, and on its vision model an image costs a fixed 2,048 tokens whatever its size.
One photo a bottle therefore caps out around two bottles a minute, which is useless in a shop. The
batch path photographs **three to five bottles per frame** and spends one request on the frame:

| Approach | Tokens for 20 bottles | Bottles a minute, Groq free tier |
| --- | --- | --- |
| One bottle per photo, two images | ~114,000 | ~2.4 |
| One bottle per photo, one image | ~66,000 | ~2.4 |
| One frame of four bottles, one image | ~18,000 | ~9 |

The image cost is per request, not per bottle, so putting four bottles in a frame quarters it. The
queue then spreads frames across every vision model of every provider that has a key — each model is
metered separately, so two models is twice the throughput — and the frame prompt keeps its static
rules at the front so prompt caching can discount the repeated prefix.

Gemini's free tier is capped by requests rather than tokens for this workload: about 10 requests and
250,000 input tokens a minute on Flash-Lite, with a frame costing roughly a thousand of those
tokens. Ten frames a minute is roughly forty bottles, so the request cap, not the token budget, is
what binds there — which is why Gemini leads by default and Groq is the fallback.

On the Scan screen:

- **Analyse photo** — the careful two-image read (full frame plus a centre crop). It works out on its
  own whether you photographed one bottle, a shelf or a wine list.
- **Shoot a shelf** — the camera reopens after every shot and each frame is queued. Reading happens
  in background work, so shooting never waits on the network; bottles appear as they are read and
  **Add all** puts them in the cellar in one tap. Frames are copied out of the camera cache into the
  app's files, so a low-memory kill mid-shelf loses nothing.

Batch adds go through the same merge path as a CSV import, so a bottle caught in two overlapping
frames — or one already in the cellar — is matched on producer + cuvée + vintage instead of being
duplicated. Anything that reads badly can be reshot on its own for the full two-image pass.

Pacing does not assume any provider's headers. `ai/RateLimiter.kt` works in two modes: when a
response carries `x-ratelimit-remaining-tokens`, `x-ratelimit-remaining-requests` and
`x-ratelimit-reset-tokens` (Groq does), it waits only when the next request would not fit; when it
does not (Gemini's compatibility layer), it spaces requests by the provider's **requests per minute**
setting instead. Both modes are keyed per provider and model, so one provider's budget never stalls
the other. A `429` is respected through `retry-after` (or the "try again in Ns" in the error body),
the frame is retried up to three times, and the queue card shows the ETA from the configured pace.
The card's rate is what the limits allow: a frame still takes as long as the model takes to read it.

## Reference notes, and why answers are cautious

A label scan writes `expert_notes` from the model's general knowledge. The bottle page's **Look up
tasting notes** button is the part that actually checks. Its first version called Gemini's native
`generateContent` with the `google_search` tool, which does not work on a free key at all: Google
gates Search grounding behind billing, so the answer is 429 with a quota of zero and waiting never
helps. `WebLookup` now does the retrieval itself — a keyless search for the producer, cuvée and
vintage, then the first three pages that answer, stripped to text — and the model is asked only to
report what those pages say. The URLs it cites are checked against the pages actually fetched and
kept in `Wine.notesSource`; the note and its tags replace the guessed ones, the host names appear
under the note, and a near-miss vintage or thin source comes back as a caveat instead of as notes.
Either provider can run the extraction, no extra service is involved, and the lookup shares the
app's rate limiter.

The extraction asks for plain labelled lines — `MATCH`, `NOTE`, `ABV`, `TAGS`, `SOURCES`, `CAVEAT` — rather
than JSON, because a compatibility layer that silently drops `response_format` turns a JSON request
into prose and the note is then lost for no reason; a JSON answer is still read first when a model
offers one. `parseExtraction` accepts labels in any case with a preamble around them, treats
`MATCH: no` (or a refusal, or a note under 40 characters) as a miss that is stamped and not saved,
and throws with the first 200 characters of the answer when there is nothing to read, so the card
shows what came back instead of a message that leaves the reader guessing. An answer that describes
the wine and then admits "specific tasting notes for the 2018 vintage are not available" counts as a
miss too (`metaAnswer` in `parseExtraction`) — it used to be saved as a tasting note, which put a
database summary in the record under a critic's authority.

### A verdict only where there is a decision

1.6.5 and 1.6.6 tried to give every bottle in the cellar a 1–5 match line. It failed for a structural
reason, not a wording one: the score started neutral and moved on whatever the record happened to
hold, so the number measured the record rather than the wine, and in a cellar of similar reds the
strongest clauses were the same two every time — "the red you usually pour" and "ready to drink now",
both true by construction. Ranking a bottle you already own is also answering a question that was
settled at the till.

So the cellar page carries no score. It shows the facts (ABV in the tag row, the window, the sourced
notes) and a **Your rules** card that runs `PalateRules.checks` and prints every gate as it stands:
`✓` under the ceiling, `✕` over it, `?` when the label does not say — then one plain sentence when
nothing is broken, which is explicitly not the same claim as a good match.

The verdict moved to `BuyAdvice`, where a decision exists: a bottle in a shop or on a restaurant
list. There the label in your hand prints the ABV, the price tag is next to it, and the app knows
your limits, so `Buy it` / `Worth a look` / `Leave it` (`Order the bottle` / `Maybe` / `Skip it` at a table)
is an answer rather than a grade. It is built only from things the app can check: a gate the user
set failing (over the alcohol ceiling, or sweet by style when they asked for none, is a *Leave it*;
oak named in the published tags is only a caution), a drinking window already past, the price against
the band they gave (shop mode only — a list price carries a markup, so a restaurant scan judges the
wine, not the deal), whether the cellar already holds that producer and cuvée, and, when it does not,
the gap it fills ("no Cabernet Franc in your cellar"). Missing facts are listed as unknown and never
counted in either direction.

The gap and the cleared limit are reported, not counted. `BuyAdvice` fills four buckets — what rules
the bottle out, what argues against this evening, what argues for the money, and what the bottle
simply passes — and only the third can produce a `Buy it`: a price inside the band the user gave.
Passing the alcohol ceiling is a filter cleared and "your first bottle from Catalonia" is a reason to
be curious; counting either as an argument for the money is what made a nine-wine restaurant list come
back as nine bottle pills, and counting the ceiling would only move the bug one layer down, to
"every wine under 13.5% is a buy". A bottle's gap is printed under the reasons in a quieter line and
never lifts the verdict. A list, whose prices carry a markup and whose wines are never ranked against
each other, therefore answers `Maybe` for a bottle that breaks nothing and `Skip it` for one that
does — and when no limits are set at all, the top of the list says so once instead of every card
guessing in the same voice.

### A list is ranked, not graded

Nine cards carrying the same word is not an answer to "which one do I order", and that is what a
list produced: with nothing printed about alcohol or oak, a per-bottle verdict has the same inputs on
every line and therefore the same output. So a list is ranked against itself in `ListPicks`.

The first version of that ranking leaned on `personal_fit`, which is a judgment of one wine against
`mytaste.md` — and with that file empty, as it is on a fresh install or after the app data is cleared,
the model honestly scores every wine neutral and the page ranks flat again. So the read now returns
`list_rank` as well (from the line reader for a list, from the structured reader for a bottle): the model's own order down the page, 1 for the bottle it would order first, a
different number for every line, with `fit_reason` naming what puts that bottle there. That is a
comparison between the bottles on the page rather than a verdict on each in isolation, so it keeps
its meaning with an empty taste file. `ListPicks` sorts on that order first, then on `personal_fit`,
then on what the app can check locally — a limit of the user's the bottle clears or breaks, its
window, whether it is already at home. `BuyAdvice` exposes that last part as `strength`, which
nothing displays.

The best line is called out as `Order the bottle`, two runners-up are numbered `2nd pick` and `3rd pick`, the
rest of the page carries its place (`4th`, `5th` …) instead of repeating one word, and a summary above
the cards names the ones worth ordering so the answer is visible without scrolling nine identical
cards. A line that breaks one of the user's limits keeps `Skip it` and stays out of
the ranking whatever the fit says — second-best of nine is still a recommendation. A tie is not
broken by invention: when the top score is shared, those cards say `Best pick` instead of crowning
one. When the read really does come back level, `ListPicks` says which of the three it is in
(`READ`, `PRICE`, `NONE`) and the banner above the cards changes with it: a price fallback names the
cheapest and the priciest line instead of crowning a bottle, those two cards carry `Cheapest` and
`Priciest`, and a page where nothing differs says so. The cards stay in menu order, because the list in the reader's hand is in that order too.

`personal_fit` still exists on the label scan: a model that looked at *that* label against the
profile is a real per-bottle judgment, and it shows as `AI match N/5` when it arrives. The app no
longer manufactures a substitute for it.

The scene is not a setting. One prompt reads the photo, names what it is in `"scene"` (bottle, shelf,
list, other) and lists every bottle it can identify; `ScanParsing.parseScene` turns that into the same
`ScannedLabel` shape the batch queue already used, and the screen draws a label card for one bottle or
a card per wine for a shelf or a list. That removed the three chips, and with them a silent bug: a wine
list scanned under the "shop shelf" chip had its list prices judged against a retail price band. A lone
bottle photographed is the ordinary shop case, so its card carries the same verdict as a shelf — the
price band only enters when a price tag was actually read. A reply that says "other" while listing a
bottle is treated as what it read.

