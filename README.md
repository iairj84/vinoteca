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
  the list pours one, and a few tempting lines to skip — every line checked against the page before
  it is shown. List prices carry a markup, so there is no price-band verdict there.
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
- **The advice calls** — a wine list, a shelf, one bottle's insight — read the short brief instead
  of the file: the quick-start answers plus the loved bottles, so three wines are weighed against
  your taste rather than against a page of settings.
- A blind head cut was the 1.3 behaviour and it was wrong: a real file opens with scoring
  tables, so the first 2,600 characters were all machinery and none of the preferences. The
  assistant then invented verdicts its own rules forbade — the same bottle for a Caesar, a
  tikka and a bowl of macaroni — because the pairing table and the "never repeat" rule were
  below the cut.

## The quick start, and what it writes

A new install has no palate, so **Taste → Start the quick start** asks ten taps: what you pour, which
axis is doing the work, sweetness, budget, what you tell people about a bottle you loved, who it is
for, what to avoid, dish context, how far to stray, and repeats. Every question can be skipped —
skipping is recorded as *unknown*, never filled with a default.

Then three optional lines, the only typing in the flow: wines you loved and what you liked about
them, a wine you didn't enjoy and why, and anything you are curious to try. They ride in the taste
brief verbatim, capped at 200 characters each, and none of them becomes a rule. A numeric alcohol
ceiling is separate again: it exists only when you type one on the Taste screen, and "avoid strong"
stays a preference — a chip is not a measurement.

Between the seventh tap and the last three, the flow shows **three things to look for**: styles, not
bottles, because the app has no catalogue and naming a wine it cannot vouch for would be the same
invention the file exists to prevent. The last screen is the actual Markdown, so the file is read
before it becomes a contract rather than after.

Three things are deliberate:

- **Taps write axes and preferences, not rules.** The only checkable gates a tap can set are
  sweetness (from "none, please" or "a touch is fine") and oak. "Avoid strong" is a *preference*,
  not a ceiling: a chip is not a measurement, and the 13.5% figure it used to invent contradicted
  the 5★ Zinfandel in the same cellar. A numeric alcohol ceiling exists only when the user gives a
  number, and a gate that cannot be checked from a record is never counted as broken.
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
| "Why it tastes this way" answers, cached by wine | `files/insights.json` |
| API keys, base URLs, model ids, pace, currency | private `SharedPreferences` (`vinoteca_settings`) |
| Captured photos | app cache, deleted by the system |

Uninstalling the app deletes all of it, which is what **Settings → Backup and import → Export
backup** is for: one JSON file with the cellar, the locations and `mytaste.md`.

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
- The app was compiled and packaged in a Linux VM; the screens were later rendered on an Android 14
  emulator (Pixel 6 AVD `vinoteca-test`, light and dark), so the layout has been looked at. No real
  phone run is recorded here, and the emulator's camera is a synthetic scene, so camera capture and
  the photo picker have not been exercised on hardware.
- A shop shelf and a restaurant list are told apart by the model rather than by the app, and it is
  not perfectly steady: the same four-bottle shelf photo was read as a list on one of three tries,
  and a list the *model* read (rather than the phone's OCR) gets no advice at all — the ranking
  fallback stands in for it.
- "Why it tastes this way" needs the advice model. Without a key the bottle page offers the request
  and shows what failed; nothing is invented in its place.
- The scan result's advice block needs a provider key to reach. For layout work, a debug build reads
  two files from the app's `files/` — `debug-scan.json` and `debug-advice.txt` — through the app's
  own `ScanParsing` and shows them as a finished list scan. Release builds never read them; the
  commands are in `docs/INSIGHT_REDESIGN.md` Step 7.
