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
  1–5 match against your taste profile. Everything is shown for review before it is saved. Saving
  it also asks once *why* the bottle tastes the way it does: cause-and-effect reasons tagged with
  the confidence behind each (plain when high, "Typically" when it is reasoning from the region,
  hidden when it is guessing), a tasting profile, a drinking window, pairings and a line for you.
  The answer is kept on the bottle and cached per wine, so the same bottle is never asked twice and
  a shelf that holds a wine you already know takes the explanation it already has. When the one
  request fails, the bottle page says why and offers to ask again.
- **Add a bottle by name** — tap **+** on the cellar and type `2025 stoller rosé`: one search, then
  the bottlings the results actually name — producer, cuvée, vintage, colour, place, grapes and, when
  a snippet prints one, the ABV. Pick one and it goes into the cellar with those facts; the note, the
  drinking window and anything else missing are read from the pages afterwards, for that bottle only.
  Nothing is invented: a search that names no such wine adds nothing and says so. A bottling that is
  already in the cellar says so on its card — **Already in your cellar — 3 bottles. Adding makes 4.**
  — so you know the pick is one more bottle on that record, not a new one. **Scan the label** and
  **Type it in** are in the same dialog. The cellar's own search box still just narrows the list to
  bottles you own.
- **Have I had this?** — every wine read off a menu or a shelf is matched against the cellar and
  the drink log by producer, cuvée and vintage, and the card says so before the advice: "In your
  cellar · 2 bottles", "You had this yesterday", "On record, no bottles", with the verdict word when
  one was ever given. A wine you have already called **Not for me** is not a badge but a block: the
  word, the day you said it, in the error colour, and it is dropped from the advice's picks — with a
  line saying which pick went — because "what should I order" must not name a wine you have said no
  to. A wine the app knows nothing about says nothing.
- **In a shop or at a restaurant** — photo → the model reads the bottles, the ABV and any visible
  price. A shop shelf gets a buying answer: up to three bottles worth taking, each with the price
  from the photo, why it earns the spot and — when a price is printed — whether that price is good,
  fair or poor, plus a couple of bottles worth skipping and the reason. Below them the per-bottle
  verdict shrinks to what the app knows and the answer does not: a limit you set that the bottle
  breaks, a drinking window that has passed, a bottle already in your cellar. A bottle whose record
  says nothing gets no verdict at all rather than a hedge. Photograph a shelf as several frames in
  the scan queue and one tap — **What to buy from this shelf** — asks for the same answer across
  every frame, because three picks per photo is not a recommendation. A restaurant mode reads a wine
  list and answers the diner's question instead: two or three bottles with a reason each, a glass if
  the list pours one, and a few tempting lines to skip — a skip row that names several lines counts as
  each of them, and every line is checked against the page before it is shown. List prices carry a
  markup, so there is no price-band verdict there.
- **Cellar** — quantity, storage location (racks / fridges you define) and slot, price paid,
  drinking window with a Hold / Drink now / At peak / Past peak badge, search, filters and a sort.
  The search
  folds accents and punctuation, so `rose` finds *Rosé* and `st estephe` finds *St. Estèphe*, and
  every word has to match, so a second word narrows. Every
  bottle with stock carries **Drank one**, which takes it out of the cellar on the tap and then asks
  for the verdict and one line about it; Skip keeps the bottle counted as drunk and says nothing more.
  The same button sits under **Bottles** on the bottle's own page, where the notes and the rules are.
  A tap is not final: once the sheet is closed, an **Undo** snackbar puts the bottle back, and only
  the bottle — a verdict or a note given in the same breath stays.
  A **No bottles** filter shows every wine on record with none left — the wines kept off a list and
  the bottles you finished — instead of leaving them at the bottom of the list, and a
  **Saved wine menus** card above the search opens a wine list you kept. A **Drink up** filter,
  second in the row, is the bottles at their peak or past the window on their record — stock only,
  so a wine with none left is not a decision, and **Ready now** is left out because a bottle you may
  open is not one you must. Past peak sorts above at peak. A **To buy** filter, third in the row, is
  the bottles flagged for the next shop: a bottle's page carries a **To buy** chip that names its own
  state, and a flagged bottle says **To buy** on its row. The flag is its own — no verdict is needed
  to want a bottle again, and it travels in the backup with everything else. Every style has a chip
  of its own — red, white, rosé, sparkling, orange, dessert and fortified — after the questions about
  what to do with the shelf, so four new styles cannot push **No bottles** off the end of the
  scrolling row. **House order** beneath them names the order in force and offers four more:
  **Recently added**, producer, newest vintage and highest price. A bottle with no price or no
  vintage goes to the bottom of those two rather than the top, because a missing price is not a
  cheap one.
- **Where a night happened** — the *Where* box, on **Drank one**, on a night written in by hand and
  on a corrected night, is a place rather than a line of text. Typing offers nearby businesses by
  name and town — *Loyal Legion — Beaverton*, *… — Portland*, *… — Vancouver* — read from
  OpenStreetMap through komoot's Photon, with the places you have saved first in the list and the
  phone's own geocoder answering with an address when Photon has nothing. The location icon takes
  one fix and tags what is there: a saved place's circle first (*Home*, a friend's house), then a
  named business within about a hundred yards, and **nothing** for a house — a street number is not a
  place anybody said. Somewhere with no name offers **Save this spot**, which names it and gives it
  a circle of its own: 50 yards by default, and 25, 50, 100, 200 or 500 yards per place, renamed or
  re-cut in
  **Settings → Saved places**. Location is asked for the first time anything is typed in the box,
  because half a name needs to know the town before it can find the branch down the road; the icon
  asks too, for a place that was never typed. Refused, the box is exactly what it was before — the
  whole name still finds the place — and a phone with no fix says so instead of searching from
  nowhere.
- **Notes** — reference notes from the model, plus your own dated tasting notes and one of four
  verdicts: **Not for me**, **Fine**, **Good** or **Loved**.
- **A wine you don't own** — every pick off a wine list carries **Save this one**, and every list
  card in the full read does too. It keeps the wine with no bottles: it never counts as stock or
  value, but it gets a page, so the four words and the notes work on a wine that was never in the
  cellar and never will be. A wine already on the shelf is left exactly as it is, and the line says
  which of the two it found. Kept wines are found under Cellar's **No bottles** filter, not by
  scrolling.
- **Saved wine menus** — a restaurant wine list is kept when it is read: the wines as the page
  printed them, the day, the answer's picks and its closing note. The restaurant's own name is read
  from the page's letterhead when it prints one — by the phone's own reader, by the structured read
  and by the line reader that answers when those fail — so a menu is titled "Osteria Francescana" rather
  than its date; **Name it** overrides that. Each menu says how many wines **you**
  picked that night — the ones you ordered or later gave a verdict to — not how many the answer
  suggested, so three recommendations never read as three glasses. Open one from Cellar and give any wine on it a
  verdict — the answer is written on the cellar record when you have kept that wine, and on the
  list's own copy when you have not. Ordering a wine puts it on record too, matched by producer,
  cuvée and vintage: a bottle you already have is left exactly as it is rather than added twice. Thirty lists are kept, newest first, and each can be renamed
  or deleted. The photograph of the page is kept with it — a thumbnail in the index, the whole page
  on the list, tappable to read at full size — so a line the reading missed is still there to check.
  Photos live in app storage and are not part of the JSON backup. The whole page opens full screen,
  where pinching zooms in and dragging moves it, for the print that no reading captures.
- **The label you scanned** — a bottle added from a single-label scan keeps its photograph, shown
  on the bottle's page and openable full screen with pinch-to-zoom. A shelf or menu frame is one
  picture of several bottles, so it is not named as any one of their labels. Label pictures live in
  app storage beside `cellar.json` and travel in the JSON backup as base64, so a restored phone comes
  back with them; a photo whose wine is deleted is cleaned up on the next start.
- **Fill in the details** — one action in Settings completes whatever a bottle is missing: the
  reference notes, the flavour words, the ABV the alcohol gate needs, the drinking window, the
  country, region and appellation, and the grapes. One lookup answers all of it in one request,
  three bottles run at a time, and anything the pages did not print stays empty rather than
  estimated. A lookup that does not answer is not written off: it is asked again next time, and
  **Look again** re-asks the bottles whose pages had nothing to say.
- **Two reminders, and both say why** — an hour after a wine list is read, a single notification
  asks how the wines were and **reopens that list**, not the app's front door. A second list replaces
  the first nudge, the permission is asked for when the list is read rather than at first launch.
  Recording the evening settles it: the first wine you order or judge on that list cancels the nudge
  (and any notification already in the tray), because a reminder to say how a wine was is noise once
  you have said. **Settings → Weekly nudge** adds one more, and only if you turn it on: a Monday
  morning line naming the bottles at their peak or past their window, skipped entirely on a week with
  none, and a tap that opens the cellar on its **Drink up** filter. Nothing else is ever posted.
- **The cellar book** — every bottle that leaves the cellar is written down: **Drank one** with the
  verdict you gave at the time, and the minus button as a count adjustment with no verdict
  attributed to you. The **Journal** tab opens it — the nights newest first, each bottle with its
  word, its survey words and the time — so your own record is readable without a model in between.
  A search box finds a bottle by its words — accents and punctuation folded, so `rose` finds *Rosé*
  — and a calendar marks every night that has an entry, so
  an evening can be found by its date when the name is the part that is forgotten. **Today**
  comes back to this month and two double arrows move the calendar a year at a time; when the day
  being looked at has entries from the same date a year earlier, **A year ago tonight** names them
  under their date, and a tap opens that night. The calendar is built to be heard as well as seen:
  a day reads as *Thursday 27 November 2025, 2 entries* and says when it is the one being shown,
  the month and year arrows name the month and year they lead to, and the day cells grow with the
  text size instead of clipping their numbers. **Add** writes a
  night the app could not
  see — a restaurant bottle, or one from before it was installed — with an editable date, the
  verdict, one line, and a three-tap survey: up to six taste words and whether you would buy it
  again. **Where** is an optional line on every night — the restaurant, a friend's table — asked
  beside the date on a hand-written night and on **Drank one**, shown on the row as *At Osteria
  Francescana*, matched by the book's search, and given to Ask, so “what did I drink at that place”
  is answerable. **Tap a night to correct it** — the date, the verdict, the taste words,
  would-you-buy-it-again, the line and the place — or **Delete this night** to take it out after
  asking. A correction to a night that
  wrote onto its bottle (**Drank one**, or one you added by hand) is carried to the bottle's verdict
  and its dated line, leaving a later verdict of your own alone. A wine you do not own becomes a
  record with no bottles, so the answer lives under Cellar's
  **No bottles** filter and reaches the taste brief the way a rated bottle does. Ask reads the same
  record: “what did I drink yesterday”, “what did I have last week”, “what have I loved this month”.
  A bottle's page says when it was last drunk. **Undo** takes an inventory entry back with the
  bottle, since an undone tap never happened. A bottle rated or noted before the book existed is
  offered under **From your bottles**, and one tap writes those tastings in — dated by their note,
  or by when the bottle last changed, and marked as coming from the bottle rather than as a night
  the app saw. The seven- and thirty-day lines count nights, and the page says how many entries
  came from bottles. The whole book is in the JSON backup.
- **Ask** — chat with your cellar in context: “Recommend a wine from my cellar to go with chicken
  pot pie”, “What's at its best right now?”, or attach a photo taken in the shop. It is also given
  your saved wine menus — the place, the day, the wines you picked there and the ones you refused — so
  “what did I order at that place” is answerable, and a wine you turned down at that table is never
  offered again.
- **Ask can move bottles, and only what you confirm** — “add all my Cabernet Sauvignon to the Bar
  Fridge” comes back as a card, never as a done deal: the app matches the ask as a query against every
  bottle in the cellar (not the shortlist the model was shown), narrowed by grape, type, producer,
  region, country, appellation, a vintage range and where a bottle lives now — *only the ones with no
  location* included — lists every match with where each one lives now (all of them, however many),
  and writes nothing until you tap **Move**. The same card changes a bottle's own fields — shelf, price paid, note, count, vintage
  — with the old value shown beside the new and **Apply** before anything is written; a note is added
  as a dated line rather than overwriting what is there. A place you name that the cellar does not have
  yet is offered for creation on the same card. The chat itself has no write access, so it cannot
  claim a change it did not make.
- **Every bottle can carry its photograph** — a label scan keeps the picture it was read from, a
  wine added from a search comes with the photograph its page declares, and any bottle can be given
  one from the camera or the gallery. It sits beside the title as a thumbnail and opens full size
  with pinch to zoom, which is what a wrong vintage or cuvée is checked against — and the open
  picture can be replaced or removed. **Settings → Bottle pictures** walks the bottles that have
  none and asks the pages that name them for the photograph they publish, keeping only a picture
  shaped like one bottle or its label on a plain field, with the subject spanning the frame (no
  model, no quota, one bottle at a time, stoppable). A picture it pulls from a page is marked as the
  finder's, and **Check** measures every kept picture against the same rule: one that is not the
  bottle is looked for again, a page's picture with nothing better is taken off, and a photograph
  you took yourself is only ever replaced by one that does look like the bottle. The files are app-private; a label picture is in the JSON backup, and the page photographs of saved menus are not.
- **Taste profile** — a real `mytaste.md` file you edit in the app, with a hand-written half and a
  generated “Bottles I love” half rebuilt from the bottles you called Good or Loved. Import/export it as
  a file.
- **Updates, and a cellar value you can put away** — the app asks GitHub once at start-up whether a
  newer release exists, and offers it at the top of the cellar with **Update** and **Not now**.
  **Not now** is remembered for that release, so it is not asked again on every launch and it is asked
  again when a newer one is published; Settings → Updates still reports and installs a release the
  card is not offering. Nothing downloads or installs without a tap, and a failed check is silent.
  Settings → Currency has **Show the cellar value**, which takes the PAID total off the cellar label
  without touching the price of a single bottle.
- **Crash report** — a fatal error is written to a file on the phone and shown in Settings → About
  under **Last crash**, with Copy and Forget it. Nothing leaves the device.
- **Gates stand where you are standing** — a hard gate is a limit when buying, so a bottle over the
  ceiling is left on the shelf or off the wine list. A bottle already in the cellar is read as a
  glass: over the line it becomes a pass that says why (“drink it; not one to buy again”), and only
  stands if the rest of the profile fails with it.

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

### Emulator

An Android 14 emulator is set up on the development machine: the SDK is in `C:\dev` and the AVD is
`vinoteca-test` (Pixel 6). `emulator` and `avdmanager` are not on `PATH`, so they are called by full
path:

```
export PATH="/c/dev/platform-tools:$PATH"
/c/dev/emulator/emulator.exe -avd vinoteca-test -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect &          # boots in ~40 s
adb wait-for-device
adb shell getprop sys.boot_completed   # 1 when it is ready
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.wineverdict.app/.MainActivity
adb exec-out screencap -p > screen.png
adb shell cmd uimode night yes         # dark theme; `no` goes back
```

Git Bash rewrites adb arguments that start with `/`, so a device path needs a doubled slash
(`//sdcard/Download/...`).

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

Install the current build from the releases page:
<https://github.com/iairj84/wine-verdict/releases/latest> — the asset is `wine-verdict-v1.0.11.apk`.
The standalone download page and its worker live in `deploy/`.

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

## Backup, restore and importing

**Settings → Backup and import.** The export is one JSON file (`wineverdict-backup-YYYY-MM-DD.json`)
holding every wine, storage location and your `mytaste.md`, the cellar book and the label pictures,
plus the currency. API keys are never included. When bottles are on the shelf and the last export is
over a month old — or there has never been one — the cellar shows **Keep a copy off this phone** with
**Export backup**, and Settings says when the last one was made. Restore offers **Merge** (keeps what
is on the phone, matches duplicates) or **Replace**; label pictures are put back with the records that
name them, and a merge gives a picture to a bottle it matched but left bare.

**Import a CSV** reads CellarTracker and Vivino exports, and any spreadsheet whose headings look
like wine columns. Columns are matched by name from an alias table rather than by position, so
`Wine` / `Wine name` / `Winery` / `Producer` / `Master Varietal` / `BeginConsume` / `Bin` / `Location`
all land in the right place; unknown columns are ignored. Handled on the way in: UTF-8 and
windows-1252 files, comma/semicolon/tab delimiters, quoted fields with embedded commas and newlines,
CellarTracker's non-vintage `1001`, one-bottle-per-row exports, 100-point scores (kept as a line in
the reference notes), 1–5 star ratings rounded to the four verdicts, purchase price with currency, drinking-window years and
per-bottle locations. A cell that says `Unknown`, `N/A`, `None`, `null`, `-` or `?` is a missing
value, not a name: a designation holding one is not appended to the wine, and a placeholder country,
region, appellation or grape is stored as blank so the details pass can fill it. A name already
stored with the word on its end is cleaned the next time the cellar loads.

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
base URL at any other OpenAI-compatible endpoint and it works too. There are three model fields: the
vision models that read a photo, the chat models that read and name a bottle, and the advice model that answers a wine list, explains a bottle and runs the chat. The advice call, the bottle insight and the chat go to the advice model, but they do
not share a provider order. The advice call leads with Groq whenever it has a key — one large
reasoning request, then you read the answer. The chat leads with Gemini, because every turn resends
the conversation and Groq's free tier is 8,000 tokens a minute, which is two or three turns. Either
way the other provider answers when the first has no key, is rate limited or fails. When neither can
answer, the advice is retried once before the ranking fallback: Gemini's free tier returns a 503 for
that request about one attempt in three, and one bad minute should not turn a list into a score.

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

**Which provider runs first** in Settings is the order scans try; the other configured one is the
fallback. The advice call and the chat override the lead, because they are not the same shape of
request: a list is one large reasoning call (Groq leads), a chat resends the conversation every turn
(Gemini's 250,000-token minute leads). Switching either is still a setting, not a code path.

Thinking is set per request. The structured ones — label scan, shelf frame, the scan queue — ask for
JSON with thinking off, which both providers express as `reasoning_effort: "low"` — Groq rejects
`"none"` outright, and Gemini's compatibility layer has nothing below `"low"` — because a schema is
faster and more reliable without the model talking itself out of it. The **Ask** tab is the exception: prose advice wants the reasoning, so it
asks for `"medium"` on either provider and its output cap is raised to leave room, since thinking is
billed as completion tokens. A model that rejects the field gets one plain retry without it.

**What it costs, and the cap that holds it.** A paid Gemini key bills by the token, so the app
counts what it spends. Every answer carries the provider's own `usage` counts, and those are priced
at the answering model's published rate (the table lives in `data/SpendMeter.kt`) and added to a
running total for the calendar month: about a third of a cent for a label scan, half a cent for a
restaurant list, a sixth of a cent for a question. **Settings → Spend this month** shows that total
against a limit, which defaults to **$1.00** and is editable; at 80% the card turns amber, and at the
limit requests are refused with a message that says so rather than leaving the screen guessing. Set
the limit to `0` to remove the cap. Only models that cost money are priced: `:free` models, and
Groq's and OpenRouter's free tiers, add tokens to the count but nothing to the total. A provider
that reports no usage is not guessed at. The numbers behind the per-operation figures are measured
and recorded in `docs/COSTS.md`.

Free-tier notes worth knowing: Gemini may use free-tier content to improve its products (paid tiers
do not); Groq's limits are per model, so listing two vision models doubles both throughput and the
daily cap. Because the cellar summary and taste file are sent with each question, long questions cost
tokens — the app caps the cellar digest at 55 bottles on a thin budget. The conversation itself is
capped by tokens rather than by message count: every question carries the most recent turns that
fit in 8,000 tokens of history (2,000 on an 8,000-token-a-minute provider), oldest first out, and
the question you just typed always goes whole.

## How `mytaste.md` works

The file is seeded on first run and lives in the app's private files directory
(`/data/data/com.wineverdict.app/files/mytaste.md`). It has two halves:

```markdown
## What I enjoy
- **Body:** medium to full
...

## Bottles I love
<!-- AUTO:LOVED:START -->
- **2016 Barolo · Giacomo Conterno** — Red · Piedmont, Italy · Nebbiolo · Loved
  - my note: tar and roses, still tannic
<!-- AUTO:LOVED:END -->
```

- The hand-written half is yours; the app never rewrites it.
- **Rebuild from loved bottles** replaces only the block between the AUTO markers, using the wines
  you called Good or Loved.
- **Ask** sends the whole file — up to 12,000 characters, about 3,000 tokens. Longer files are
  cut at that point and the model is told the rest is missing.
- **Every request except Ask reads the short brief**, not the file: the ten taps, the twelve
  optional answers, the loved bottles, your three written lines, your "what links them" sentence and
  the `## Notes` sections of this file. Scans, shelves, list advice and a bottle's insight all use
  it, so a model is given taste rather than a page of settings.
- **The brief is not a file.** It is rendered from the stored profile each time a request is made,
  capped at 2,000 characters, and printed verbatim on the taste profile under *What the model reads*.
  There is nothing on disk to keep in sync and no way for it to drift from the answers.
- **The dish carries through a conversation.** A question that names food — or heat — is checked
  against it; a follow-up like "what about the second bottle" reuses the dish from the turn before,
  and the food-context answer is only the fallback. That is what makes the 14.0–14.5% alcohol
  allowance and the spice allowance for an off-dry wine real rather than theoretical.
- **The prompt is rendered, not written.** `PalateFile.renderModelPrompt` is the only path, and it
  returns the text together with the id of the field behind every line; a line with no field throws
  before the prompt can leave the app, so a hand-added sentence cannot survive an edit. Whether the
  gates are described as hard ("a bottle that fails one is not offered") or as preferences is
  generated from the gate severity, never written into the prose.
- A blind head cut was the 1.3 behaviour and it was wrong: a real file opens with scoring
  tables, so the first 2,600 characters were all machinery and none of the preferences. The
  assistant then invented verdicts its own rules forbade — the same bottle for a Caesar, a
  tikka and a bowl of macaroni — because the pairing table and the "never repeat" rule were
  below the cut.

## The quick start, and what it writes

A new install has no palate, so **Settings → Taste profile → Start the quick start** asks ten taps: what you pour, which
axis is doing the work, sweetness, budget, what you tell people about a bottle you loved, who it is
for, what to avoid, dish context, how far to stray, and repeats. Every question can be skipped —
skipping is recorded as *unknown*, never filled with a default. That is the 90%; everything after it
is detail on top.

Then three optional lines, the first typing in the flow: wines you loved and what you liked about
them, a wine you didn't enjoy and why, and anything you are curious to try. They ride in the taste
brief verbatim, capped at 200 characters each, and none of them becomes a rule. Two more optional
fields follow: *what links the wines you love*, in your own words, and a numeric alcohol ceiling you
type — the only way an ABV limit exists, because "avoid strong" stays a preference and a chip is not
a measurement.

After them comes a gate that says **that is the 90%**. Behind it are twelve optional questions, one
per screen, each skippable: the grapes and the places that have actually worked, what to skip, grip,
acid, body, cellaring, funk, sparkling, how you order at a restaurant, oak, and how far you will go
on price. They earn their place because a restaurant list prints almost none of the ten baseline
answers, and each one changes what a good recommendation looks like. An unanswered question is left
out of the brief rather than guessed, and answering them does not change the baseline percentage.

Between the seventh tap and the last three, the flow shows **three things to look for**: styles, not
bottles, because the app has no catalogue and naming a wine it cannot vouch for would be the same
invention the file exists to prevent. The last screen is the actual Markdown, so the file is read
before it becomes a contract rather than after.

Three things are deliberate:

- **There are four gates, in the order the rules were written.** `GateRegistry` holds G1 Alcohol,
  G2 Place, G3 Dry and G4 Style words; the ids and titles are fixed, and the profile table is
  rendered from the same list the engine evaluates, so a gate cannot be missing from the table while
  still being checked (that is how two of them were lost once). G1 is the one **hard** gate and
  exists only when a ceiling is typed: at or under it passes; between it and 14.5% passes only with
  a cool site or food, and the answer has to print the number; over 14.5% fails; no ABV on the
  record is *unknown*, never a pass. G2 rejects a broad appellation with no vineyard named above
  $10 and leaves an unlisted place unknown; G3 is the dry line, with a spice allowance that makes
  an off-dry Riesling conditional rather than failed; G4 rejects jammy, plush, baked, sweet oak,
  fruit bomb and "easy drinking" prose while keeping restrained vanilla and toast. "Avoid strong"
  and "avoid heavy oak" are still *preferences*: a chip is not a measurement, and the 13.5% figure
  it used to invent contradicted the 5★ Zinfandel in the same cellar. The 4.0 g/L sweetness
  threshold is labelled *Ours* — "none, please" is the answer, the number is the app's.
- **The thresholds are data, and the app checks them.** `PalateRules` compares a bottle record to the
  gates before any prompt is built: `Wine.alcohol == null` yields `UNKNOWN`, never `PASS`, and the
  cellar digest appends `app gate check: alcohol unknown …` so the model is told the fact is missing.
  Sugar and oak can only ever fail a check from a record, never pass one, because neither is printed
  on a label. The chat prompt says so explicitly.
- **The pairing table stays in the app, not in the file.** `PairingTable` still ships the eight dish
  classes and the taste profile shows the ones implied by the dish-context answer. The generated file
  deliberately carries no grape-to-dish rows: each row is a claim about wine, and the file has no
  source to cite for it. Instead it tells the model to work the dish out when asked — acid for fat,
  acid for tomato, salt, sweetness, heat, umami — and stores only the food context the user gave.

The file it writes is a receipt, not a summary: every value carries the words the user chose and a
confidence (`Yours`, `Ours`, `Guess`, `Unknown`), and a field with no source renders as *unknown*.
The label is computed from where the value came from, never typed beside it: a typed 14.0% is
*Yours*, the 4.0 g/L sweetness threshold and the oak line derived from a rejection are *Ours*. It
opens with the confidence vocabulary and the precedence rules, then the anchor line, the taste axes,
the gates with their sources, a settings table, the pairing policy, house rules, other drinkers, and
two honesty sections — **What this file doesn't know** and **Conflicts**. Around 6,700 characters with
all ten taps answered: inside the 12,000-character Ask budget, and section-ranked so a scan still
gets *What I enjoy* and *What I avoid* first. It is marked Provisional, and the unknowns section
lists every skipped question plus the missing alcohol ceiling, so the assistant asks instead of
inventing. The anchor line is the sentence you wrote about what links your bottles, kept as
*Yours*; only when that sentence is missing does it say "not set yet", and then it names the answer
that is missing rather than the generator's silence.

Retaking the quick start rewrites a file the quick start wrote. A file saved in the advanced editor is
kept, appended below the new one as *Notes from my previous file*.

The generated file is also rewritten from the stored answers when the app starts, so a release that
changes the shape of the file (a new gate table, an anchor line that survives) applies without
answering the questions again. A file saved by hand in the advanced editor is left alone.

## Editing the file by hand

**Settings → Advanced → Edit mytaste.md**, or **Taste profile → Edit mytaste.md (advanced)**, opens the raw
Markdown with import, export and *Rebuild from loved bottles*. Saving there marks the file as yours.
This sits behind Settings and is named as a file rather than as taste settings on purpose: the quick
start is the normal route, and this is the escape hatch for people who would rather type Markdown.

The taste profile — **Settings → Taste profile** — is the summary: how much of the baseline and of the interview is answered (with **Add
more detail** and **Retake**), **What the model reads** — the brief, verbatim, with its length — the
gates the app checks (with how many cellar bottles currently fail one or cannot be checked), your
answers and the file itself behind toggles, and the shipped pairing classes with a note that none of
them are written into the file.

## Where things live

| Data | Location |
| --- | --- |
| Cellar, locations | `files/cellar.json` |
| Label pictures | `files/label-photos/` |
| Chat transcript (text only, no photos) | `files/chat.json` |
| Cellar book (nights and tastings from bottles) | `files/drinks.json` |
| Saved places (Home, a friend's house) | `files/places.json` |
| Taste profile | `files/mytaste.md` |
| Quick-start answers | private `SharedPreferences` (`wineverdict_palate`) |
| Pending batch scans + their photos | `files/scan-queue.json`, `files/scan-queue/` |
| "Why it tastes this way" answers, cached by wine | `files/insights.json` |
| API keys, base URLs, model ids, pace, currency | private `SharedPreferences` (`wineverdict_settings`) |
| Captured photos | app cache, deleted by the system |

Uninstalling the app deletes all of it, which is what **Settings → Backup and import → Export
backup** is for: one JSON file with the cellar, the locations, `mytaste.md`, the cellar book, the
saved places and the label pictures. `cellar.json`, `drinks.json` and `places.json` are written whole
and swapped in, with the previous
version kept as `.bak` and read when the file cannot be; Android's own backup carries the files but
not `wineverdict_settings`, where the keys are.

## Documentation

- `CHANGELOG.md` — release notes, newest first.
- `docs/DESIGN.md` — why the scan, the verdict and the ranking work the way they do.
- `docs/INSIGHT_REDESIGN.md` — the plan for the advice pipeline.
- `AGENTS.md` — build, test and code-map notes for coding agents.

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
- **Adding a bottle by name** depends on the web results naming it: an obscure wine with no page, or
a search that returns shops selling something else, comes back with nothing added. The app says so
  rather than guessing — that is when the label scan or the editor is the right tool.
- The app was compiled and packaged in a Linux VM; the screens were later rendered on an Android 14
  emulator (Pixel 6 AVD `vinoteca-test`, light and dark), so the layout has been looked at. No real
  phone run is recorded here, and the emulator's camera is a synthetic scene, so camera capture and
  the photo picker have not been exercised on hardware.
- A shop shelf and a restaurant list are told apart by the model rather than by the app, and it is
  not perfectly steady: the same four-bottle shelf photo was read as a list on one of three tries.
  Both readings get the advice now — the list answer runs on the model's own read, not only on the
  phone's OCR, so a device without that module is no longer a device without answers.
- "Why it tastes this way" needs the advice model. Without a key the bottle page offers the request
  and shows what failed; nothing is invented in its place.
- The scan result's advice block needs a provider key to reach. For layout work, a debug build reads
  two files from the app's `files/` — `debug-scan.json` and `debug-advice.txt` — through the app's
  own `ScanParsing` and shows them as a finished list scan. Release builds never read them; the
  commands are in `docs/INSIGHT_REDESIGN.md` Step 7.
