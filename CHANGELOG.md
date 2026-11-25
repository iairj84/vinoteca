# Changelog

Newest first. Wine Verdict 1.0.0 restarted the version line when the app was renamed from
Vinoteca, so every 1.0.x entry is newer than the 1.7.x entries below it.

### Unreleased

**A tap is not a measurement.** "Avoid strong" no longer becomes a 13.5% ceiling — it contradicted
the 5★ Zinfandel in the same cellar, and the assistant then defended a number nobody typed. The
preference still reaches the model, worded as a preference; a numeric ceiling exists only when a
number is given. A gate that cannot be checked from a record is no longer counted as broken, and a
bottle with no printed ABV is no longer hedged down from *Buy it* to *Worth a look*. When a loved
(4★+, or favourited) bottle would break a ceiling you set, the Taste screen says so in one line.

**Oak is a preference now too.** "Heavy and oaky" no longer runs a test: it is a line in the brief
("restrained oak — vanilla and toast in the background"), and the oak gate, its enum and the
"tasting tags mention oak" failure are gone. An oak tag in the notes is a style, not a measurement.

**You can type a ceiling.** The Taste screen takes an alcohol ceiling in % vol (5–25, or nothing),
stored in the profile, written into mytaste.md as *your* number rather than one "inferred … ours",
and checked against every bottle record. That is what makes the loved-bottle notice reachable.

**Three questions a tap cannot answer.** The quick start now ends with optional free-text lines —
wines you loved and why, one you didn't and why, anything you're curious to try — carried into the
taste brief verbatim, capped at 200 characters, and never turned into a rule. The Taste screen also
takes "what links the wines you love?", which the brief had a slot for but nothing to fill it.

**A list gets advice, not a spreadsheet.** The list now asks for two or three bottles with a reason
and a couple of tempting ones to skip, instead of a rank for every line — the answer that kept
putting the first three sparkling wines first. The advice call thinks before it answers, has its own
60-second timeout, and gets the cellar summary and taste brief. The per-wine judgement is kept for
one release as the fallback, used only when the advice answer carries no usable pick — never after a
timeout or a rate limit, because a second 4,000-token request cannot fit in the same minute — and the
debug panel labels it. Picks are matched by line number; the reason text arrives on the cards in a later
step.

**A list now answers with picks, not pills.** The advice is the result: up to three bottles with
"Order this"/"Second"/"Third", each with the name and prices the phone read off the page, the
headline, and the reason in full — then a by-the-glass line, a compact Skip list and an optional
note. The rest of the page follows as the wines that were read, without ranks. The Buy/Leave chip
is gone from list cards (a shelf and a single bottle keep it); a ranked line keeps its place, and a
line that breaks one of your limits still says why. When the advice answer did not come back, the
older per-wine ranking says "Basic ranking" so the two cannot be mistaken for each other.

**The glass/bottle toggle is back on the advice result, and advice has its own model.** Choosing a
pour is the first question at a table, and it disappeared when the ranked section it lived in was
replaced: it is at the top of "What to order" again. Glass shows only the picks the list actually
pours by the glass, Bottle hides the by-the-glass answer, Not sure shows both; a list with no glass
prices says so instead of showing an empty answer.

The advice call and the chat now go to an advice model of their own — a third field on each
provider's card in Settings — and Groq leads whenever it has a key, because reasoning over a whole
list is the job its model does best on a free tier. Scan reads keep the vision and chat models and
the primary-provider order.

**Advice is checked before it is shown.** Every line the model writes about a list is verified
against the page: the line number has to exist, a glass has to be a wine the menu pours by the
glass, a wine cannot be called red when its section says rosado (or the reverse), and one bottle is
picked once. A line that fails is dropped rather than repaired, and the scan details panel lists
what was dropped and why. If no pick survives, the per-wine fallback runs as before.

**The list gets long enough to answer.** The judgement on a photographed wine list now asks for
4,000 tokens (plus a reasoning reserve on the way out) instead of 800, gets 130 seconds instead of
the 95-second scan budget, and shows "Thinking about the list…" while it works. The judge's own
timeout rises to 75 seconds so the longer deadline is not cancelled before it is reached.

**mytaste.md cannot contradict the gates.** Saving a ceiling or a "what links them" line on the
Taste screen now regenerates the file from the profile, the same way the quick start does, so the
file no longer says "no gate is set" beside a gate the app is enforcing.

**The judgement actually answers.**

`Thinking.OFF` sent `reasoning_effort: "none"`, which Groq rejects with a 400. The retry dropped
the field, so the model reasoned at its default: on an 800-token list judgement 798 tokens went to
reasoning, the answer was empty, `finish_reason` was `length`, and the reply was thrown away — the
cards fell back to the page read, in page order. `OFF` now maps to `low`, and `max_tokens` carries
a reasoning reserve (600 / 900 / 1,800 by level, ceiling 8,000). Measured on the 26-wine fixture
with `openai/gpt-oss-120b`: reasoning 798 → 133 tokens, and 4,115 characters of answer where there
had been none. Fine-print reads at 400 tokens were hit by the same bug.

### 1.0.11: the answer states its unit, and earns its reasons

A photographed list used to print one bottle price and one glass price on
the same card with one `Order it` button that committed to neither, let one
producer hold two of three picks, and let portable prose ("classic blend",
"unique grape variety" naming no grape) pass as reasons.

- **Unit first.** A Glass / Bottle / Not sure toggle sits above the picks;
  the answer states its unit in one line, re-ranks by the price actually
  paid, and filters before capping — a bottle-only rank leader no longer
  takes its glass-poured sibling down with it. A glass answer cannot contain
  a bottle-only wine; a bottle-only list says so and offers the bottle.
  The button carries its unit (`Order a glass` / `Order the bottle`) and is
  disabled until one is chosen. Not sure ranks by bottle and names the
  recommended pour, with any cheaper pour as a separate note.
- **One producer, one pick.** At most one wine per producer; the slot is
  dropped, never substituted.
- **Reasons must be earned.** Every description and reason is validated:
  it must name a grape, subregion, vintage, ABV or producer contrast unique
  to its row (cuvée words don't count), carry no neighbour's fact, no
  superlative about the list, and no unrecorded claim (`old vines`,
  `hand-harvested`, `estate-bottled`, `family`). Failures fall back to a
  facts-only line.
- **The strip.** Every card carries ABV, both real prices, the integer
  break-even, the most specific place — with per-field provenance
  (`(menu)` for printed prices, `(as read)` elsewhere) instead of a blanket
  source claim.

### 1.0.10: one card, one wine — no more mixed bottles

A recommendation card could show one wine's name and price next to another
wine's description (Miquel Pons on top, Naveran's Chardonnay underneath).
Diagnosis: (b) import bug — the renderer always built each line from a single
row object, but the judgement's `what` (which echoes wine titles) could land
on the wrong row at import, so the stored row itself was merged.

- **One card from one row.** `ListIdentity.buildCard` is now the only card
  constructor; the shortlist carries row ids only and the section resolves
  them through a Map. No positional indexing across lists in card assembly.
- **Guard before render.** Title, price and detail are checked against the
  row; a card naming a foreign producer is dropped with its rowId (logged via
  `Log.w`) instead of showing a mixed bottle. The importer also refuses to
  attach a `what` that names a different wine on the page.
- **Ranking unchanged.** Same wines, same order, same reasons — only the
  binding changed. Seven identity tests (the three Nov 25 cards rejected,
  order-shuffle stability, unknown-id drop, price check) pass.

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

