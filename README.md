# Wine Verdict

An Android wine cellar notebook with an AI sommelier. It was called Vinoteca up to 1.7.17;
1.0.0 renames it — a crowded name shared with several other wine apps — with no behaviour
change. The applicationId is new (`com.wineverdict.app`), so it installs alongside any
Vinoteca still on the phone; move the cellar across with Settings → Backup in the old app
and Restore in this one. Scan a label to add a bottle, photograph a
shop shelf to get a ranked buying recommendation, and ask questions about what you own — all
against your own `mytaste.md` taste profile.

Native Kotlin + Jetpack Compose. No account, no backend of ours: the cellar, notes and taste file
live in the app's private storage. The only network calls go to an AI API you pick — Google
Gemini or Groq — with your own key.

## What it does

- **Scan a label** — photo → a vision model reads producer, name, vintage, region, grapes,
  style, ABV, drinking window, sommelier-style reference notes and, when the model offers one, a
  1–5 match against your taste profile. Everything is shown for review before it is saved.
- **In a shop or at a restaurant** — photo → the model reads the bottles, the ABV and any visible
  price, and each one gets a buy-or-leave verdict built from your own limits (alcohol ceiling,
  sweetness, oak), your price band and what the cellar is missing. A restaurant mode reads a wine
  list and skips the price-band judgment, because list prices carry a markup.
- **Cellar** — quantity, storage location (racks / fridges you define) and slot, price paid,
  drinking window with a Hold / Drink now / At peak / Past peak badge, search and filters.
- **Notes** — reference notes from the model, plus your own dated tasting notes and a 1–5 star
  rating.
- **Ask** — chat with your cellar in context: “Recommend a wine from my cellar to go with chicken
  pot pie”, “What's at its best right now?”, or attach a photo taken in the shop.
- **Taste profile** — a real `mytaste.md` file you edit in the app, with a hand-written half and a
  generated “Bottles I love” half rebuilt from your favourites and 4★+ bottles. Import/export it as
  a file.

## Build

Requirements: JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). Android Studio will
create `local.properties` for you; on a bare machine create it yourself:

```
sdk.dir=/path/to/Android/sdk
```

Then:

```
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleSideload     # minified build for sharing (~1.5 MB)
./gradlew :app:installDebug         # install on a connected device
```

The `sideload` build type runs R8 with `-dontobfuscate` and the serialization keep rules, and is
signed with the debug key so it installs straight from a download — fine for sideloading, not for
publishing to a store. Both builds are debug-signed.

### Releasing on GitHub

Push this repo to GitHub, then a version is one tag:

```
git tag v1.0.0 && git push origin v1.0.0
```

`.github/workflows/release.yml` builds `:app:assembleSideload` on GitHub's machines and attaches
`wine-verdict-v1.0.0.apk` to the release — no local toolchain needed. The workflow refuses to publish
when `versionName` in `app/build.gradle.kts` does not match the tag, so bump both together
(`versionCode` +1, `versionName` to the tag) before tagging. Install the release APK straight from
the release page; it is signed with the debug key, same as the local sideload build.

Prebuilt APKs: `artifacts/vinoteca-1.0-debug.apk` (unminified, 17.7 MB) and the 1.6 MB sideload
build (1.6.4), published for phone installs at
<https://vinoteca-download-n6uxmj-preview.freebuff.site>. The page and APK live in `deploy/`.

## Label reading

A label scan sends two images of the same photo — the full frame and a zoomed centre crop, where the
small print actually survives the phone camera — and asks the model to transcribe every legible word
into `label_text` before it names anything. Fields must be traceable to that transcription, so an
unreadable label comes back empty with `confidence: low` rather than as a plausible guess. `wine_found`
lets the app say "that is not a label" instead of inventing a bottle.

Bottles already in the cellar are listed in the prompt so a repeat scan reuses the exact spelling, and
the result card has a **Re-read the label more carefully** button: a second pass that receives the
first reading plus a crop and runs at higher reasoning effort (one extra request on the free tier).
On Groq's `qwen/qwen3.8-27b` an image costs a fixed 2048 input tokens and three images are allowed
per request, so the first pass (two images) stays inside an 8k tokens-per-minute free budget.
Gemini bills a 1536px frame at roughly 1,000 tokens, so the same pass is cheaper there.

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
your limits, so `Buy it` / `Worth a look` / `Leave it` (`Order it` / `Maybe` / `Skip it` at a table)
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
back as nine `Order it` pills, and counting the ceiling would only move the bug one layer down, to
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

The best line is called out as `Order it`, two runners-up are numbered `2nd pick` and `3rd pick`, the
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

### 1.7.2: slower reads, fewer misreads

Scanning now trades a few extra seconds for accuracy on purpose. The single-photo read sends
larger frames (2200px + an 1800px centre crop) at low reasoning instead of none, with the
temperature down to 0.1 and room for a longer answer; the background shelf queue gets the same
treatment (low reasoning, 2,200 tokens). The prompt makes the model transcribe first, justify
every vintage and ABV against that transcription, re-check its own work, and report what is still
doubtful (`uncertain_fields`) plus the one re-shot that would settle it (`next_tip`). The app
enforces the rest locally: a vintage outside 1900-this year, an ABV outside 5-22%, or an absurd
price is dropped with a note instead of being saved, `personal_fit` outside 1-5 is ignored, and a
`high` confidence with no vintage or no ABV is shown as `medium`. The label card and every
shelf/list card show a **To be sure** box naming the doubtful fields and the next photo to take -
usually the back label, which is where the ABV lives.

### 1.7.3: a legible wine list must produce wines

1.7.2 made the reading more careful but left one contradiction in the prompt: it told the model to
leave `label_text` empty for a "list" scene, and a model read that as "do not write anything down".
A restaurant list came back with `scene: list` and an empty `bottles` array, so the screen said "no
readable bottles" over a perfectly legible page.

The list case is now spelled out line by line: one entry per wine down the left column and then the
right, the bracketed text after the name becomes `grapes`, `GLASS - 14` is a `glass_price` and
`BOTTLE - 54` is `price`, and the section heading supplies the `type`. The prompt says plainly that
a legible list with an empty array is a failed reading. Two safety nets follow it: `parseScene` now
keeps the scene the model declared instead of rewriting an empty answer to `other`, so the message
can say "read as a list but no wines came out of it, shoot one column at a time"; and an empty
shelf or list triggers one automatic recovery pass (`Prompts.listRetry`) that asks for the wines and
nothing else. That pass costs an extra request only when the first read fails.

List cards now show both figures — `USD 50.00 · glass USD 13.00` — because at a table those are two
different decisions, and a glass price that came back higher than the bottle price is dropped as a
misread rather than displayed.

### 1.7.4: the answer has to fit

1.7.3 fixed the prompt, and the next scan failed differently: "The model did not return readable
JSON". The cause was the budget, not the wording. The free tier bills every image at a fixed 2,048
tokens, and the scan sent two of them, so on Groq a list of eighteen wines had barely a thousand
tokens left for an answer of eighteen objects. The reply stopped mid-object, the parser threw, and
the whole read was lost.

Three changes, in order of size:

- **One image for the first read.** The whole frame is what a list or a shelf needs; the centre crop
  only helps small print on a bottle. It is now sent as a second, cheap request (`Prompts.finePrint`,
  about 600 tokens of answer) and only when a single-bottle read came back without the ABV or the
  vintage — the two figures the verdict and the drinking window turn on. `withFinePrint` merges it
  into the blanks and nothing else, and the fields it answers stop being listed as uncertain.
- **A small answer by construction.** No `expert_notes` or invented windows for a shelf or a list, a
  twelve-word `fit_reason`, and at most 24 wines per photo with the remainder counted in `notes`.
- **A parser that survives a cut-off answer.** `AiJson.objectFrom` now salvages a truncated reply:
  it walks the `bottles` array keeping every complete object, closes the array by hand, and marks the
  result with a note saying it was cut off. A partial list with a visible caveat beats an error card,
  and the walk is verified against nested arrays and escaped quotes.

### 1.7.5: a wine list is not a JSON problem

The next scan after 1.7.4 took almost a minute and came back unreadable again, which settles it: the
failure was never a wording detail. One vision reply carrying eighteen nested objects is fragile on a
free tier — thinking tokens compete with the answer, the format has to satisfy a schema per bottle,
and any slip costs the whole page.

So the list path no longer uses JSON at all.

- **A one-word scene check first.** `Prompts.sceneCheck` sends a small image and asks for a single
  word, capped at eight tokens. It routes the photo before any expensive request is spent on it.
- **A list is copied as lines.** `Prompts.listLines` asks for one line per wine in a fixed field order
  (`producer | name | vintage | region | grapes | type | bottle | glass | confidence`, `-` for
  anything not printed). There are no braces to break: a reply that stops early loses its last lines
  and every complete line before it still parses. `ScanParsing.parseListLines` accepts bullets, a
  header row, currency symbols and semicolon lists, drops a line that is cut off, and never invents a
  vintage or a price.
- **Everything else is unchanged.** A bottle or a shelf still uses the structured reader, and a list
  the line read cannot make sense of falls back to it rather than showing nothing.

Two requests instead of one, but both are small: the scene check is a word, the list read is about
eighteen short lines. That should take seconds, not a minute.

### 1.0.9: the ranking says why this bottle, and what it costs

1.0.8 stopped ranking by price, but the answer was still thin: three names, three producer blurbs
("exceptional producer", "one of the world's greatest") and no price, so the bottle at 260 looked
like the safe order. The numbers could repeat too — a read that wrote "1, 2, 2" was taken
literally and the page printed "1. 2. 2."

- **The reason test.** Every "what" and "why" now has to survive one question: put a different
  wine from the same page in front of the sentence — if it is still true, it is a label, not a
  reason. Reputation words on their own are banned as the whole answer, and the rank-1 line has
  to say what it beats, including its price against the cheaper bottles.
- **Unique numbers.** The read's order is renumbered 1..n before it is scored, breaking a repeated
  rank by fit and then by page order, so no two cards share a place and "best first" really is
  1, 2, 3.
- **The price on every pick.** Each line of "Worth ordering, best first" now carries the bottle
  price, with what the wine is and why it sits there underneath it.

### 1.0.8: the judgement says what the wines are, not what they cost

A photographed list came back as nine cards that each said the same thing — nothing is against
it — plus the cheapest and the priciest. If you do not know the producer, that is not an answer:
price was the one thing you could already read off the page.

The judgement was the fault. It asked only how well each bottle matched `mytaste.md`, and with
that file empty the honest answer is "neutral" for every line, which ranks nothing. It now asks
the model for what the reader actually needs: what each wine is — the style, the standing of the
producer or the appellation, how it tastes — and which bottle is the better wine for the money
on that page. The taste file moves a wine up or down instead of being the only thing consulted.

- **A "what it is" line per wine.** `Prompts.listJudge` returns `number | rank | fit | what | why`,
  and the description is kept as the wine's notes when it is added to the cellar. The vision read
  carries the same field, so a photo path gets it too.
- **The ranking is no longer optional.** It gets 45 seconds of the scan's budget, and when it
  fails the app reads the page from the photo instead of leaving a price order on screen.
- **Less noise under each card.** "Your first bottle from Catalonia" is a cellar curiosity, not
  an argument for ordering off a menu, and the missing-ABV caveat is said once per list rather
  than under every line.

### 1.0.7: lists are judged from text, not shown to a vision model

The phone reads the names and prices; judging them never needed eyes. A list the phone
reads now goes to a text-only judgement — rank, fit and a one-line why per wine, matched by
number — at a fraction of the tokens and in the shape a free tier answers fastest. No image
goes up for a list at all. Bottles and photos the phone cannot read still take the vision
path, unchanged.

### 1.0.6: the phone reads the list, so a stalled model no longer empties the screen

The on-device text reader was already pulling every wine and its prices off a printed list
in about a second — and the app showed that as raw text and threw it away. When all three
providers failed on one photographed list (one ran out of room, two timed out), the usable
answer had been sitting in the preview the whole time.

A printed list is regular enough to parse on the phone: a wine's lines, then its "GLASS - 13
BOTTLE - 50" line, with section headings marking the type. Those become real cards — name,
producer, vintage, region, grapes, both prices — within a second or two of the photo, ranked
on price because nothing on a list says how a bottle tastes. If the model answers, its read
replaces them; if it does not, the cards stay and a note says where they came from.

The line read also gets 2,600 output tokens and a 35-second ceiling, because twenty wines is
about two thousand tokens and the old cap both cut answers off and timed them out.

### 1.0.5: the audit's quick wins

An external read of the scan path, verified against the code and fixed where it checked out:
the second provider now starts at once when the first fails fast instead of waiting out the
hedge delay for a dead answer; the rate limiter reserves its slot inside the lock and admits
reads against three-fifths of the token cap instead of stalling on the worst case; the
on-device text reader is one shared client running beside the upload instead of ahead of it;
the label crop encodes during the first request; the preview skips a JPEG round trip; and a
cut-off answer is retried rather than parsed for its reasoning text.

Streaming replies and on-device ranking stay out: the first is a day's work for perceived
rather than real speed, the second changes what the cards say. Both are recorded in the
audit for later.

### 1.0.4: no more template cards, and a bottle read in one wait

A small model sometimes answers by echoing the schema — a card titled "name · Fields:
producer", a type of "Red", grapes called "grapes". Those words are now treated as blank,
and a line whose producer and name are both template words is dropped, so a failed read says
so instead of inventing a bottle.

A bottle scan also stops doing its two label reads one after the other: the rich read and the
small-print pass go out together and the slower one sets the wait, which is most of the minute
that a single bottle used to take.

### 1.0.3: free scans via OpenRouter

A third provider card, OpenRouter (free): paste a key from openrouter.ai/keys, Save, Test
connection. It uses a free vision model suited to wine lists, fixed in the app so there is
nothing to pick — about 15 requests a minute and 50 a day per key, 1,000 a day once the
OpenRouter account has bought 10 credits. It slots into the existing order: the first
provider with a key leads, the others are fallback, tap a row to change which is primary.

### 1.0.2: the updater learns its own name

One repo now publishes two product lines, and /releases/latest points at Vinoteca 1.7.18 —
so Wine Verdict offered the old app's APK as its own update. The check now walks the release
list and takes the newest release carrying a wine-verdict APK, ignoring the other line
entirely. If 1.0.1 already prompts you to "update", dismiss it: that prompt is the bug.

### 1.0.1: the key moves in one tap

Moving from Vinoteca meant retyping a 40-character key you can no longer see — the field
masks it and the provider dashboard may not show it again. Each provider card now has a
**Copy key** button: one tap copies the saved key, paste it into the other app, Save, Test
connection. Vinoteca 1.7.18 carries the same button, so the copy starts there.

### 1.0.0: a new name, because the old one was taken

Vinoteca belongs to several wine apps already, so this one is now Wine Verdict: same code,
same signing key, new applicationId. It installs as a fresh app next to any Vinoteca on the
phone, and the version restarts at 1.0.0 — which also keeps those old installs quiet, since
their updater only offers what is numerically newer. Backups move across: export from the
old app and import here; the importer reads both `vinoteca-backup` and `wineverdict-backup`
files.

### 1.7.17: a small APK, and a release that survives GitHub

v1.7.16 shipped a 41.7 MB APK: the bundled ML Kit text recogniser carries its OCR pipeline four
times over (`lib/x86`, `lib/x86_64`, `lib/arm64-v8a`, `lib/armeabi-v7a`, ~10 MB each). The app
itself is 1.7 MB. Switching to `play-services-mlkit-text-recognition` removes those libraries —
Play Services fetches the Latin model once on first use and shares it across apps — and the
sideload APK is back to 2.2 MB. `LocalOcr` is unchanged; if Play Services has not got the model
yet the preview is simply empty and the provider read carries on.

The v1.7.16 release workflow also failed twice, both times on GitHub's release API returning 500:
once creating the release, once finalising it. `softprops/action-gh-release` retries internally but
`generate_release_notes` is a second API call that 500s too. The publish step is now plain `gh`
with a five-attempt loop (create, or upload and un-draft an existing release), the notes are written
from this README instead of generated, and a final step fails the run unless the release is
non-draft with the APK attached — a green run now means the file is downloadable.

### 1.7.16: hedged reads and an on-device preview

Two changes for the scan that hung 121 seconds and the wait that showed nothing until then.

- **Hedged reads.** When two providers have keys, the list read asks the second one 12 seconds
  after the first and takes whichever answers — one stalled provider no longer costs its full
  timeout before the other is tried. Per-read deadlines drop to 25s + 20s, the fine-print and
  bottle-enrichment passes are skipped when the budget is nearly spent, and nothing in a scan
  outlives a 75-second total. No scan takes minutes.
- **On-device preview.** ML Kit Latin text recognition (`LocalOcr`, bundled model, no network)
  reads the page in ~1s and the screen shows those lines immediately under "On your phone
  already — ranking follows". The same lines are sent with the photo as a hint, so the model
  has a head start on hard-to-read print. A stall now leaves a provisional list, never a blank
  screen. No API key is involved and nothing leaves the phone for it.

### 1.7.15: the retry uses the other key

The 1.7.14 retry went back to the provider that had just stalled. If a second provider has a key,
the smaller second attempt now goes there instead, and the progress row says so ("Retrying on
Groq · …"). One stalled provider no longer decides whether the photo is read at all.

### 1.7.14: a read that cannot hang

The screen sat at 121 seconds on "Reading the page with Google Gemini" with no answer: one HTTP
request open the whole time. Three things made that possible, and each is closed now.

- **A deadline per read.** A vision read gets 40 seconds. If it has not answered, the call is
  cancelled and the same request is made once more on a 1200px frame (about a third of the bytes)
  with 30 seconds to answer. Nothing readable in 70 seconds is reported as a failure, naming the
  provider, what was uploaded and what to do about it.
- **Cancellation reaches the network.** Requests were sent with a blocking `execute()`, so Stop and
  any timeout only stopped waiting for a socket that stayed open. They are enqueued now, the
  coroutine cancels the call, and a 150-second call cap is the backstop.
- **Smaller frame.** The scan sends 1800px at quality 85 instead of 2200px at 90: the same page and
  label text, roughly a third of the upload.

The progress row already names the provider and counts seconds, so a stalled read is now a visible
failure in under a minute rather than a spinner that may never end.

### 1.7.13: the pick says why

The ranked banner named three bottles and stopped there: a shortlist with no case for the top one,
and cards carrying a bare "Best pick" pill over empty space. The line reader now returns a `why`
with every wine — one short line, twelve words at most, saying what puts it where it is on that
page, and for rank 1 why it beats the rest. `ScanParsing` reads it into `fitReason`, `VerdictBlock`
shows it directly under the pill, and the banner above the cards is a `SectionCard` listing the
numbered picks with the read's reason for each, instead of one wrapped line of truncated titles.

### 1.7.12: one request for a list, and a wait that says what it is

A scan that sat on "Reading carefully — a few extra seconds…" for over a minute was doing two
things at once: two round trips before a list produced a single card, and, on a free tier, a wait
inside the rate limiter that nothing on screen mentioned.

- **The scene question moved into the list read.** `Prompts.sceneCheck` was a second request on a
  second image, asked before the real one. The list reader now answers it in its first line
  (`SCENE: list`), so a list or a shelf is one request instead of two. `ScanParsing.sceneLine` reads
  it, and when a model leaves it out the scene is inferred from how many wines came back. A bottle
  still gets the richer JSON read on top, because that is what carries notes, window and fit.
- **`rank` is part of the line format.** The ranking added in 1.7.10 was parsed out of the JSON
  reader, which a list never reaches — the line reader is the one that runs on a list. Each line now
  ends with its place on the page, every number used once, so `ListPicks` has an order to sort on
  instead of falling back to price.
- **The wait is on screen.** Both scan requests pass `onBudgetWait`, and the progress row shows the
  provider and model that is actually answering ("Reading the page with Groq · qwen/qwen3.8-27b"),
  the seconds elapsed, and, when the limiter is holding the request, "Waiting 38s for Groq's
  per-minute free-tier limit". A **Stop** button cancels it. Silence was the one thing that made a
  slow answer look like a hung app.
- **The privacy line names the provider that has the key.** It said Groq on every install, which is
  wrong for anyone reading with Gemini.

### 1.7.6: no single request decides whether the scan works

1.7.5 still dead-ended on the same list. The scene check did not route that photo to the line reader,
so it fell through to the structured reader and failed there — one format deciding the whole outcome
again, just one layer further out.

So every path now has a way out. The line reader handles a list, a shelf **and** a single bottle, and
the structured reader handles a bottle first because only it carries reference notes, a drinking
window and a fit. A bottle tries the rich read and falls back to lines; a list or shelf tries lines and
falls back to the rich read. Whatever fails, the other answers, and the screen only reports a problem
when both came back empty — with the provider's own message, not a guess about what went wrong.

The small-print pass now runs for any single wine missing its ABV or vintage, whichever reader
produced it.

The batch version is `NotesBackfill`. Settings → Reference notes walks every bottle whose
`notesCheckedAt` is still 0, one request each at the key's pace, and stamps the bottle whether or
not a note was found — which is what makes a hundred-bottle run stoppable and resumable without
paying twice for the same empty answer. A failure that will not fix itself — a rejected key, an
unreachable search — ends the run at the first bottle and shows the provider's message, instead of
spending the queue on the same error; three transient failures in a row do the same. `cellarDigest` lists up to 140 bottles when
the primary provider's minute budget can carry it (Gemini) and 55 when it cannot (Groq).

Ask is then held to what the record contains. `cellarDigest` sends name, type, region, appellation,
grapes, ABV, window, my note, flavour tags, and `sourced notes` (only when a page was cited). The
chat prompt forbids stating a bottle's oak, acid or fruit unless its line says so — general
knowledge must be marked as general — and a request for a plan (a meal, a day, three bottles) has a
fixed shape: the order, then per bottle *when* to open, chill or decant, *with* what, and *why* in
one sentence, in plain text with no markdown.

## Backup, restore and importing

**Settings → Backup and import.** The export is one JSON file (`wineverdict-backup-YYYY-MM-DD.json`)
holding every wine, storage location and your `mytaste.md`, plus the currency. API keys are never
included. Restore offers **Merge** (keeps what is on the phone, matches duplicates) or **Replace**.

**Import a CSV** reads CellarTracker and Vivino exports, and any spreadsheet whose headings look
like wine columns. Columns are matched by name from an alias table rather than by position, so
`Wine` / `Wine name` / `Winery` / `Producer` / `Master Varietal` / `BeginConsume` / `Bin` / `Location`
all land in the right place; unknown columns are ignored. Handled on the way in: UTF-8 and
windows-1252 files, comma/semicolon/tab delimiters, quoted fields with embedded commas and newlines,
CellarTracker's non-vintage `1001`, one-bottle-per-row exports, 100-point scores (kept as a line in
the reference notes), 1–5 star ratings, purchase price with currency, drinking-window years and
per-bottle locations.

Duplicates are matched on producer + cuvée + vintage: an existing wine keeps its id and notes, takes
the higher bottle count and fills any blanks from the import. Importing the same file twice is
harmless. A preview shows the counts, the first few wines and any rows that were skipped before
anything is written.

## Keys: Gemini or Groq

Both providers are free to start and either one is enough. Google Gemini leads by default, because
its free tier carries far more frames a minute; Groq answers whenever Gemini has no key, is rate
limited or fails.

1. Create a key at <https://aistudio.google.com/apikey> (Gemini) or
   <https://console.groq.com/keys> (Groq).
2. In the app: **Settings →** the provider's card → paste the key → **Save** → **Test connection**.
   The test lists the models the key can actually reach.

Each provider keeps its own key, base URL, model ids and pace, all editable in Settings — point the
base URL at any other OpenAI-compatible endpoint and it works too.

Keys are typed into the app at runtime and stored only in its private `SharedPreferences`
(`vinoteca_settings`). Nothing is read from the build and nothing is written to this repository: there
is no `local.properties` key, no `.env`, no checked-in `google-services.json`, and the release
workflow needs no provider secrets. Keep it that way — a key pasted into an issue, a commit or a
workflow file is a leaked key, and the fix is to revoke it at the provider, not to delete the commit.

| | Gemini | Groq |
| --- | --- | --- |
| Base URL | `generativelanguage.googleapis.com/v1beta/openai` | `api.groq.com/openai/v1` |
| Vision model | `gemini-3.1-flash-lite` | `qwen/qwen3.8-27b` |
| Chat model | `gemini-3.1-flash-lite` | `openai/gpt-oss-120b` |
| Requests/minute | 10 | 30 |
| Token budget/minute | 250,000 | 8,000 |

**Which provider runs first** in Settings is the order requests try; the other one is the fallback.
Scans and questions always send the same request shape, so switching is a setting, not a code path.

Thinking is set per request. The structured ones — label scan, shelf frame, the scan queue — ask for
JSON with thinking off (`reasoning_effort: "none"` on Groq, which honours it; Gemini's compatibility
layer only goes as low as `"low"`), because a schema is faster and more reliable without the model
talking itself out of it. The **Ask** tab is the exception: prose advice wants the reasoning, so it
asks for `"medium"` on either provider and its output cap is raised to leave room, since thinking is
billed as completion tokens. A model that rejects the field gets one plain retry without it.

Free-tier notes worth knowing: Gemini may use free-tier content to improve its products (paid tiers
do not); Groq's limits are per model, so listing two vision models doubles both throughput and the
daily cap. Because the cellar summary and taste file are sent with each question, long questions cost
tokens — the app caps the cellar digest at 55 bottles.

## How `mytaste.md` works

The file is seeded on first run and lives in the app's private files directory
(`/data/data/com.wineverdict.app/files/mytaste.md`). It has two halves:

```markdown
## What I enjoy
- **Body:** medium to full
...

## Bottles I love
<!-- AUTO:LOVED:START -->
- **2016 Barolo · Giacomo Conterno** — Red · Piedmont, Italy · Nebbiolo · 5★
  - my note: tar and roses, still tannic
<!-- AUTO:LOVED:END -->
```

- The hand-written half is yours; the app never rewrites it.
- **Rebuild from loved bottles** replaces only the block between the AUTO markers, using the wines
  you favourited or rated 4★ or more.
- **Ask** sends the whole file — up to 12,000 characters, about 3,000 tokens. Longer files are
  cut at that point and the model is told the rest is missing.
- **Scans and the queued shelf reads** share their tokens with a 2,048-token image, so they get
  whole sections of the file instead: what I enjoy, what I avoid, grapes and styles, house
  rules, then the gates and history, until 3,000 characters are spent. Sections are never cut
  in half, and a file with no `##` headings falls back to a stated cut of the top.
- A blind head cut was the 1.3 behaviour and it was wrong: a real file opens with scoring
  tables, so the first 2,600 characters were all machinery and none of the preferences. The
  assistant then invented verdicts its own rules forbade — the same bottle for a Caesar, a
  tikka and a bowl of macaroni — because the pairing table and the "never repeat" rule were
  below the cut.

## The quick start, and what it writes

A new install has no palate, so **Taste → Start the quick start** asks ten taps and nothing else: what
you pour, which axis is doing the work, sweetness, budget, what you tell people about a bottle you
loved, who it is for, what to avoid, dish context, how far to stray, and repeats. Every question can be
skipped — skipping is recorded as *unknown*, never filled with a default.

Between the seventh tap and the last three, the flow shows **three things to look for**: styles, not
bottles, because the app has no catalogue and naming a wine it cannot vouch for would be the same
invention the file exists to prevent. The last screen is the actual Markdown, so the file is read
before it becomes a contract rather than after.

Three things are deliberate:

- **Taps write axes and constraints, not rules.** The only checkable gates a tap can set are sweetness
  (from "none, please" or "a touch is fine"), oak, and an alcohol ceiling. The ceiling comes from the
  "avoid strong" chip, is written as a number, and is labelled *assumed* — a chip is not a
  measurement, and a number the user never gave must not look like one they did.
- **The thresholds are data, and the app checks them.** `PalateRules` compares a bottle record to the
  gates before any prompt is built: `Wine.alcohol == null` yields `UNKNOWN`, never `PASS`, and the
  cellar digest appends `app gate check: alcohol unknown …` so the model is told the fact is missing.
  Sugar and oak can only ever fail a check from a record, never pass one, because neither is printed
  on a label. The chat prompt says so explicitly.
- **The pairing table stays in the app, not in the file.** `PairingTable` still ships the eight dish
  classes and the Taste tab shows the ones implied by the dish-context answer. The generated file
  deliberately carries no grape-to-dish rows: each row is a claim about wine, and the file has no
  source to cite for it. Instead it tells the model to work the dish out when asked — acid for fat,
  acid for tomato, salt, sweetness, heat, umami — and stores only the food context the user gave.

The file it writes is a receipt, not a summary: every value carries the words the user chose and a
confidence (`Yours`, `Ours`, `Guess`, `Unknown`), and a field with no source renders as *unknown*. It
opens with the confidence vocabulary and the precedence rules, then the anchor line, the taste axes,
the gates with their sources, a settings table, the pairing policy, house rules, other drinkers, and
two honesty sections — **What this file doesn't know** and **Conflicts**. Around 6,700 characters with
all ten taps answered: inside the 12,000-character Ask budget, and section-ranked so a scan still
gets *What I enjoy* and *What I avoid* first. It is marked Provisional, its anchor line says "not set
yet", and the unknowns section lists every skipped question plus the missing alcohol ceiling, so the
assistant asks instead of inventing.

Retaking the quick start rewrites a file the quick start wrote. A file saved in the advanced editor is
kept, appended below the new one as *Notes from my previous file*.

## Editing the file by hand

**Settings → Advanced → Edit mytaste.md**, or **Taste → Edit mytaste.md (advanced)**, opens the raw
Markdown with import, export and *Rebuild from loved bottles*. Saving there marks the file as yours.
This sits behind Settings and is named as a file rather than as taste settings on purpose: the quick
start is the normal route, and this is the escape hatch for people who would rather type Markdown.

The Taste tab is now the summary: how much of the baseline is known, the answers, the gates the app
checks (with how many cellar bottles currently fail one or cannot be checked), the shipped pairing
classes with a note that none of them are written into the file, and the full text the model reads.

## Where things live

| Data | Location |
| --- | --- |
| Cellar, locations | `files/cellar.json` |
| Chat transcript (text only, no photos) | `files/chat.json` |
| Taste profile | `files/mytaste.md` |
| Quick-start answers | private `SharedPreferences` (`vinoteca_palate`) |
| Pending batch scans + their photos | `files/scan-queue.json`, `files/scan-queue/` |
| API keys, base URLs, model ids, pace, currency | private `SharedPreferences` (`vinoteca_settings`) |
| Captured photos | app cache, deleted by the system |

Uninstalling the app deletes all of it, which is what **Settings → Backup and import → Export
backup** is for: one JSON file with the cellar, the locations and `mytaste.md`.

## Code map

```
app/src/main/java/com/wineverdict/app/
├─ MainActivity.kt            bottom nav + navigation graph
├─ AppGraph.kt                service locator, scan→editor handoff
├─ model/Models.kt            Wine, CellarData, drinking-window logic
├─ data/                      cellar + chat persistence, settings, mytaste.md,
│                             quick-start answers + rules, pairing table,
│                             backup/restore, CSV import, scan queue
├─ ai/AiProvider.kt           Gemini / Groq: defaults, key pages, pace, notes
├─ ai/AiClient.kt             OpenAI-compatible chat/vision client, provider fallback
├─ ai/RateLimiter.kt          pacing from provider headers, or a configured interval
├─ ai/Prompts.kt              the three prompt contracts + cellar digest
├─ ai/ScanParsing.kt          tolerant parsing of model JSON into models
├─ ui/                        Cellar, Detail, Editor, Scan, Chat, Taste,
│                             Quick start, Advanced editor, Settings
└─ util/ImageUtils.kt         downscale + EXIF rotation → base64 JPEG
```

The cellar is one JSON document filtered in memory: a personal cellar is a few hundred bottles, so
this avoids a database and its migration surface entirely.

## Known limits

- Expert notes, prices and drinking windows from the model are estimates from general knowledge,
  not published critic reviews or live retail data. The UI says so wherever they appear.
- No barcode decoding: the vision model reads the label, including printed digits.
- No photo retention, so chat bubbles show “photo” after a restart rather than a thumbnail.
- Batch scanning identifies three to five bottles per photo, which is a little less careful per
  bottle than the two-image single scan; each bottle carries a confidence and anything doubtful can
  be reshot on its own.
- Defaults (base URL and model ids) can age: a retired model id returns a 404/400, so Settings
  exposes the model lists and **Test connection** lists what the key can reach.
- No cloud sync, no accounts, no multi-device support. Move a cellar between phones with the backup
  file.
- The app was compiled and packaged in a Linux VM without an emulator or device, so the UI flows
  have not been exercised on hardware. The quick start's logic is covered by a JVM harness (file
  generation, unknown-versus-pass gate checks, shortlist); its screens, and the write itself, are not.