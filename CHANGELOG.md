# Changelog

Newest first. Wine Verdict 1.0.0 restarted the version line when the app was renamed from
Vinoteca, so every 1.0.x entry is newer than the 1.7.x entries below it.

### 1.2.21: the page zooms, and a bottle keeps its label

**A long menu was opened at fit-to-screen, which is exactly the size that is unreadable.** The kept
page now opens full screen — the dialog's default inset is gone — and pinches between 1× and 6×,
with dragging that follows the picture once it is zoomed and stops at its own edge so the page
cannot be shoved off the screen.

**The label a bottle was added from is kept too.** A single-label scan now writes its photograph
beside the record and names it on the wine, and the bottle's page shows it under **The label you
scanned**, openable full size with the same zoom. A shelf or menu frame is one photograph of several
bottles, so it is not claimed as any one of their labels; a photo whose wine is deleted is pruned on
the next start, and a save that never knew about the picture cannot drop it.

### 1.2.20: the page itself is kept with the list

**The photograph was read and then thrown away.** A list is photographed at a table, transcribed
into wines, and the capture stayed in the cache until Android cleared it — so a kept list was the
app's reading of the page and nothing else. When a line was missed, a price read wrong or a section
that never made it into the wines, the evidence was already gone.

**Kept beside the wines.** The page is now stored with the list, at the size a menu is photographed
at rather than the 1400px the model is sent, because the point of keeping it is reading small print
again. The index shows a thumbnail per list, the reopened list opens with the page and taps to full
size. Lists kept before this take their photograph the next time the same page is read; deleting a
list deletes its picture, and the thirty-list limit takes the photographs of the lists it drops.

**Not in the backup.** A JSON export carries records, not photographs, so a restore brings the
wines back without their pages. Said here rather than discovered after a phone change.

### 1.2.19: one tap can be taken back

**A bottle left the cellar on a tap with no way back.** **Drank one** sits where a thumb already is,
which is the point of it, and a thumb that meant to open the bottle instead took one off the shelf
with nothing to say it had been a mistake. The count move is now confirmed with an **Undo** snackbar,
on the cellar's cards and on the bottle page alike.

**It arrives after the sheet, and it restores the count alone.** A snackbar behind a dialog is a
snackbar nobody sees, so the offer waits until the sheet is closed — which is also the moment a
mis-tap is noticed. Undo adds the bottle back and touches nothing else: a verdict or a line given in
the same breath is the user's, and taking back a count must not throw away an answer.

### 1.2.18: Drank one, on the bottle's own page too

**The action was only where the list is.** 1.2.14 put **Drank one** on the cellar's cards, but the
bottle page — the one opened from a card, and the only screen with the notes, the window and the
rules on it — still offered just the minus button. A bottle opened there had no way to be recorded
as drunk without opening its card in the list, which is backwards: the page is where it is read.

**One dialog, two places.** The action now sits under **Bottles** in “Where it lives”, disabled when
there is none left, and it is the same sheet the list opens: one bottle out on the tap, then the
four words and one line, with **Skip** leaving it recorded as drunk and nothing said. The shared
dialog moved out of the cellar screen so both callers ask the identical question.

### 1.2.17: the list is kept, and the reminder reopens it

**The list was the one thing the scan did not keep.** A restaurant's menu was read, the picks were
made, and an hour later the only trace was a notification that opened the cellar — with the wines
never recorded and no way back to the page they came from. A list is now kept when it is read: its
wines as the page printed them, the day, the answer's picks and its closing note.

**It has a place of its own.** Cellar carries a **Saved lists** card above the search, and each list
opens to its wines, each tapping to the four words and one line. The verdict is written where the
wine lives: on the cellar record when you have kept that wine, on the list's own copy when you
have not — so one wine never ends up with two answers that disagree. A list starts titled by its
date; naming it after the restaurant is one tap from the list index, which is also where it is
deleted. Thirty are kept, newest first.

**The reminder now opens that list.** The nudge carries the kept list's id into the activity, so a
tap lands on that evening's wines instead of the cellar. Keeping is idempotent for one day — the
same page read twice (the phone's pass, then the model's answer on top) is one record — so the
reminder always has a single list to point at.

**Saved wines are no longer buried.** They sorted to the bottom by design, which left them findable
only by scrolling past every bottle, so Cellar gains a **No bottles** filter: the wines kept off a
list and the bottles you finished, in one place.

### 1.2.16: an hour after the list, one reminder

**The wines were photographed and then never recorded.** A list is read at a table, the wines are
poured, and by the time the phone comes out again the evening is a name on a photo — the verdict
that would have made the scan worth taking was the one thing left undone. An hour after a list is
read, one notification now says **How was the wine?** and opens the app.

**One nudge, and only for a list.** It is WorkManager work, not a timer in the screen, so it arrives
with the app closed and the phone rebooted since; a second list replaces the first list's reminder
rather than stacking a second notification onto the same evening. The notification says nothing
about the wines — it is a reminder to answer, not a second copy of the answer. A bottle or a shelf
never schedules one, and nothing is posted until the user allows it: the permission is asked for at
the moment a list is read, which is the only moment it has a reason behind it.

### 1.2.15: a wine can be kept without being owned

**The only way to remember a wine off a list was to pretend to own it.** Adding a pick put one
bottle in the cellar, so a wine met at a restaurant arrived as stock — it counted in the plaque,
in the value, in “ready now” — and a bottle somebody else opened could not be recorded at all. The
list's picks, and every line of the full read, now carry **Save this one**.

**It is a page, not a bottle.** The wine is stored with no bottles, so nothing about stock or value
moves; the cellar shows it with “none left” below the bottles you have, and it opens like any other
wine, which is the point: the four words and the notes are the same controls, so a wine that was
never on the shelf at home can still be called Loved. Saving is insert-only — an identity already on
record is never rewritten, so saving a wine you own cannot touch your stock — and the line under the
pick then says whether it found a bottle or a wine kept without any.

**The plaque counts stock.** “Wines” used to be the number of records, so a kept wine with no bottles
would have lifted it while “Bottles” stayed put. It is now the wines with at least one bottle.

### 1.2.14: one tap takes a bottle out, and asks what it was like

**The cellar's commonest change was buried.** Drinking a bottle was three taps and a scroll — open
the wine, find “Where it lives”, press the minus — and the number was all that got recorded, so the
verdict and the line that make the next purchase worth anything were left for later and usually
lost. Every bottle with stock now carries **Drank one** on its card in the list.

**It counts first and asks second.** The bottle leaves the inventory the moment it is tapped, so the
count is right even if the phone is put down mid-dialog; the sheet then says what is left and asks
the four words and one line. **Skip** closes it and leaves the bottle recorded as drunk with nothing
said about it, which is the truth of that answer. Saving writes the word every filter, sort and the
taste brief already read — and only when it changed, so an untouched sheet cannot overwrite a
verdict or clear a heart — and appends the line as a dated tasting note, the same call the bottle
page uses.

### 1.2.13: the rating is four words instead of five stars

**A star count asked for a precision the app never used.** Every place that read a rating read it
as a bucket — 4 or more was loved, 1 or 2 was disliked, 3 was silence — so 1★ and 2★ were the same
answer under two names, 4★ and 5★ were the same answer again, and the heart in the bottle's top bar
said "loved" a second time in a different alphabet. The bottle page and the editor now offer **Not
for me / Fine / Good / Loved**, one tap each, large enough to hit with a thumb; the cellar's Loved
filter, its sort order, the taste brief and the `mytaste.md` loved block all read those words.

**Nothing on an existing record moved.** The words store as the old 1, 3, 4 and 5, so a bottle rated
before this release keeps its place and its meaning: 4 and 5 are still Good and Loved, 3 is Fine,
and a stored 2 — a level nobody can pick any more — reads as Not for me. An import that lands on 2
now stores 1 for the same reason: the app has four answers, so it holds four numbers.

**The heart had to go with it.** A favourite star in the top bar and a rating row on the page were
two controls for one answer. Picking a word now sets the favourite flag the rest of the app already
reads, so a loved bottle is still marked in the list and still sorts first — there is just one place
to say it. A bottle that was hearted but never rated keeps both until it is given a word.

### 1.2.12: the cellar reads as a ledger, and the layout holds when the text grows

**Claret used to be the chrome.** A maroon status bar and a maroon stats block put the brand colour
behind everything, so the page read as one solid app rather than as a cellar with a label on it. The
colour is now kept for what the app actually states — the cellar's numbers, the open tab, the primary
action, the scan's **Camera** — and everything else is warm paper on near-white cards held by a 1dp
hairline instead of a shadow, which turned eleven floating tiles back into one page. Names, headings
and numbers are Noto Serif at book weight, bold serif reading as a masthead where regular reads as a
bottle label, with the sans kept for facts. The launcher icon is the verdict poured into a glass: a
goblet whose bowl tapers to a point, so the silhouette is a V as well as a glass, with one brass
level of wine, and a monochrome layer so Android's themed icons keep the shape.

**Night mode was the first thing the pass got wrong.** A static light status bar over the dark app
left white icons on near-black, so the bar now follows the scheme in `values-night/themes.xml`, and
the cellar plaque moves to the deep claret container — the bright pink `primary` at that size read as
a warning rather than as a label.

**A review of the finished pass found four things worth fixing, and they are all in here.** The
plaque's four capital labels collided once the system font grew — at 1.3x `READY NOW` touched `PAID`
— so above 1.15x it becomes a small ledger, one stat per line, label on the left edge and number on
the right. The muted tier sat at 3.6:1 on paper, under AA for the `Paid USD 45.00` and disclaimer
lines that use it, so it is `#75655A` now (5.0:1). Amounts carry thousands separators, which is what
had been crowding the plaque (`USD 4,421.00`). And there is no third accent any more: brass is the
only second colour, the olive that carried "quiet" and "went well" text is gone, quiet lines are ink
and confirmations are claret.

**At large text the chrome gives way instead of clipping.** The bottom bar keeps its five icons and
drops the labels above 1.45x rather than breaking them mid-word (`Cella/r`), with each name moved into
the icon's description for TalkBack. The Ask header holds one line — it used to wrap and get sliced by
the fixed-height bar — and drops the count above 1.6x. The button pair on Taste and the badge row on
Bottle wrap instead of squeezing `Retake` into `Reta/ke` or pushing the rating badge off the edge.
Settings, Scan, Ask, Taste, Bottle and the editor were each walked at 1.3x and 2.0x.

### 1.2.11: the card says when the bottle is already yours

**A candidate you already own looked new.** The add-by-name search matches a bottle on its
normalised producer, name and vintage, and the tap then puts one more bottle on that record instead
of making a twin — but the card said nothing about it, so the only way to learn that the pick would
become the fourth bottle of a wine you already had was to add it and go and look.

**The card says what the tap does.** When a candidate's identity is already in the cellar, the card
says so under the title — `Already in your cellar — 3 bottles. Adding makes 4.` — and its last line
changes from *Tap to add this bottle* to *Tap to add one more bottle*. A record that is in the cellar
with none left reads *In your cellar, none left. Adding brings it back.* A candidate for another
vintage is another wine and gets no hint. The card and the add use the same identity, so the note
and the tap cannot disagree.

### 1.2.10: the search box searches the cellar again, and + asks for the bottle

**1.2.9 put the new question in the room where the old one lived.** The cellar's search box is how
you find a bottle you own — producer, grape, region, shelf — and 1.2.9 turned it into a web lookup,
so typing there offered to search the internet instead of narrowing the list, while the button that
adds a bottle still opened a blank form. The box filters the rows again, exactly as before: type
`stoller` and the cellar narrows to Stoller.

**+ is where a bottle that is not in the cellar yet starts.** It now asks for the bottle in words —
`2025 stoller rose` — runs one keyless search, reads only the titles and snippets, and lists up to
four bottlings those results actually name, with their colour, place, grapes and any ABV a snippet
printed. Pick one and it is added with those facts; the note, the ABV and the drinking window are
read from the pages afterwards, for that bottle only. **Scan the label** and **Type it in** sit in
the same dialog, and they are also offered when the search names nothing, so the two fallbacks are
where the choice is made rather than on a screen you have to go and find.

Everything 1.2.9 fixed about the lookup still holds: snippets before pages, a source the app did not
fetch is refused, an impossible vintage or ABV is never stored, the colour comes from the result, and
a bottle you already own gets one more bottle instead of a twin.

### 1.2.9: a bottle you can name before you own it

**The search box now finds a bottle, not only a row.** Adding a bottle meant scanning it or typing
nineteen fields, and then leaving the screen for Settings to ask for the rest — with the phone in
one hand and the wine in the other that is a form, and without the bottle it is a form filled from
memory. The cellar's search box is now also the way in: type `2025 stoller rose` and **Find it on
the web** asks one keyless search, then one extraction over the result titles and snippets, and
returns up to four bottlings to choose between. Pick one and it is added with the colour, place and
grapes the results named; the note, the ABV and the drinking window are read from the pages
*afterwards*, for that bottle only.

**Snippets first, pages once.** Opening pages is the expensive half, so nothing is opened until you
have chosen: the candidates are built from the same search results the model is shown, a source the
app did not fetch is refused, and a vintage or an ABV outside the range a wine can actually have is
never stored. A result about a different vintage is a different entry, not a detail of the one you
wanted. When the results name no such wine the answer is an empty list and the app says so — it does
not invent a bottle.

**Two things this fixes on the way.** The colour comes from the result instead of the default, so a
rosé added by name is not stored as a red; and a bottle the cellar already holds gets one more
bottle on the existing record rather than a twin. Scanning the label and typing every field in both
stay as they were.

### 1.2.8: an imported bottle stops being called "Unknown"

**The word came from the spreadsheet, not the wine.** A CSV's Designation column is never empty —
where the export's app had no vineyard it wrote `Unknown` — and the importer appended every
non-blank designation to the wine name. So a cellar imported that way read "Prospice Cabernet
Sauvignon Seven Hills Vineyard Unknown" and "Ridge Geyserville Unknown", and the title, being the
stored name, showed the placeholder as if it named the bottle. In this cellar it was 31 of 50
bottles, every one of them imported, and none of the scanned ones.

**Placeholder is not a value.** `Unknown`, `N/A`, `None`, `null`, `-`, `?` and their punctuation
variants now read as blank in the importer: a filler designation is not appended, a filler country,
region or appellation is stored as blank so the next lookup can fill it, and filler is dropped from
the grape list. A name already stored with the word on its end is trimmed when the cellar loads
(and when a backup is restored), so the existing cards lose it without being imported again. A
real designation ("Papera Vineyard") still joins the name, and the word inside a real name
("Unknown Wine Co.") is left alone. The same filler list now feeds the details parser, so a page
that answers "Unknown" is a miss in one place instead of two.

### 1.2.7: the details pass fills every fact a verdict reads

**One pass, every fact.** "Fill in the details" was written when notes and the ABV were the only
things a lookup answered, so it left the drinking window, the place and the grapes exactly as they
were — and the window is a fact the buy advice, the Hold / Drink now / At peak / Past peak badge and
the sort order all turn on. The preferable pick could say "no window" about a bottle whose page had
printed one. The extraction now asks for all of it in the same request: the note, the flavour words,
the ABV, the drinking window, the country, the region, the appellation and the grapes. No extra
lookups — the search and the page reads were already paid for.

**Published windows only, and a stamp per fact.** A window is written only when a page prints one
("2027-2038", "best from 2029"), never worked out from the vintage or the grape, and never over
years already on the record. Each fact now carries its own stamp, so the app knows a bottle's window
was asked for and the page had none, instead of leaving it looking unread; **Look again** re-asks
those bottles, because a page published since can turn an unknown into a window. The card now counts
the window, the place and the grapes beside the notes and the ABV.

### 1.2.6: a note with a line break no longer takes the Taste tab down

**The crash 1.2.5's report was built for.** "This screen hit an error: IllegalStateException: prompt
has 29 lines but 24 field ids" — a note field is a multi-line box, so a note with line breaks in it
arrived as one field covering several lines, and the prompt's line count stopped matching its field
count. The renderer now flattens whitespace inside a field to a space, so a paragraph becomes one
sentence on one line, and a stray carriage return from a hand-edited file is trimmed. The guard stays
where it was; the data can no longer reach it. Reported by the app itself from the phone, which is
the whole reason 1.2.5 wrote crashes down.

### 1.2.5: one pass for the details, a quieter Settings, and a crash that reports itself

**One button, one pass.** The cellar had two jobs and two buttons — notes in one, ABV in the other —
so a bottle missing both went through the queue twice, at about six seconds a bottle. One lookup
already answers both questions, so there is now a single **Fill in the details** action: it walks
each incomplete bottle once, asks the targeted ABV question only when the notes pages carried no
figure, and runs three bottles at a time. Wall time for a fifty-bottle cellar went from minutes to
about a minute. A lookup that does not answer still writes nothing, so the bottle is asked again
instead of being recorded as having no ABV; **Look again at N bottles** re-asks the ones whose pages
printed nothing, because a page published since can turn an unknown into a number.

**Settings reads like a person's app.** It opened with spend, provider order, two API-key cards,
base URLs, three model lists and a rate limit — the machinery, before anything the user came for.
All of that now sits behind one **AI provider** card that says which service is answering and offers
"Change the AI provider"; the keys, models, base URLs and spend only appear when that is opened. The
cellar's own tools come first.

**A force close leaves an explanation.** "Wine Verdict keeps stopping" was all a crash on a phone
could say, and the screens it happened on reproduced fine elsewhere. The app now wraps the process's
uncaught-exception handler, writes the stack trace to a file on the phone, and then dies exactly as
before — and Settings → About shows it under **Last crash** with Copy and Forget it. Nothing leaves
the device; the report is read only when that row is looked at. It is the difference between a bug
report and a guess.

**The Taste tab cannot take the app down.** Whatever the profile or a bottle record contains, that
screen now renders what it can and shows the message from anything it could not read, instead of
dying on the way in. A force close there was indistinguishable from a broken tab and said nothing
about which fact caused it.

### 1.2.4: a bottle you own is drunk, not bought

**The hard gate knows where it is standing.** A hard gate is a limit on the shelf: a 14.9% bottle at
a shop or on a wine list is one to leave, and that has not changed. But the same bottle in the
cellar was already bought and paid for, and refusing to recommend it protected nothing — it just
meant the app would not tell you to open a bottle sitting in your own rack. The gates now evaluate
in one of two scopes: **buying** (the shelf, the wine list, the buy-advice card, the taste-page
counts) where a hard gate still stops a bottle, and **owned** (the bottle page and the cellar digest
Ask reads) where a hard gate it crosses becomes a conditional pass that says so — "over the 14.5
ceiling, above your 14.0% — you already own this bottle, so drink it; it is not one to buy again".
The rest of the profile still has to fit: a jammy, over-alcohol bottle the user owns is still
refused, because a hard gate is only forgiven when it is the only thing wrong.

### 1.2.3: the dish that carries, and the number the gates were missing

**A follow-up keeps the dinner.** Ask read the dish from the current question only, so "what about
the second bottle" after a question about chicken tikka fell back to the stored food habit — or to
no dish at all, which turned a 14.2% bottle from a conditional pass back into a failure between two
messages. The dish now carries across the conversation: the question wins, then the most recent
earlier question that named a dish, and the food answer is only the fallback.

**The gates get a number to work on.** Most of a scanned cellar has no ABV — 48 of 50 bottles here —
and the alcohol gate reads unknown on a bottle without one, which is neither a pass nor a failure.
Settings → **Alcohol on the labels** now works through the bottles that have none: it asks the same
published-pages lookup the notes job uses, and when that prose carries no figure it asks the ABV
question directly against shop listings, which nearly always print it. On that cellar it went from 2
bottles out of 50 to 39. Only a one-decimal figure a page prints is stored — nothing is estimated, an
out-of-range number is treated as a miss, and a miss is stamped so a second run does not pay for the
same question. A figure already on the record always wins, and the notes that arrive with a lookup
are kept with their pages. A lookup that does not answer is not an answer either: a search that
cannot be reached now throws instead of reading as "no ABV", so the bottle stays unstamped and the
next tap asks it again. Two smaller fixes: an extraction whose prose was unusable no longer
throws away an ABV it had read from the same page, and the ABV job stamps itself separately from the
notes check so neither blocks the other.

**The generated file refreshes itself.** `mytaste.md` is a projection of the stored answers and Ask
sends that file, so an upgrade that changes what it renders needs it rewritten on disk; it is now
rebuilt at startup from the answers already stored. Nothing has to be answered again, and a file
saved by hand in the advanced editor is left untouched.

### 1.2.2: four gates, a rendered prompt, and the dish that reaches them

**Two gates had gone missing, and the numbering kept the scars.** The generated profile ran G1
sweetness and G3 alcohol, nothing between them — G2 Place and G4 Style words had been dropped while
the ids stayed as they were in the hand-built file they replaced. G2 is the rule that catches a big
appellation with an invented brand and no vineyard named above $10, so nothing rejected a bottle for
having a vague place, and G4 was the only rule that rejected jammy, plush, baked, sweet-oak and
"easy drinking" prose. Both are back in `GateRegistry`, along with G1 Alcohol and G3 Dry, evaluated
in the authored order in one place. The profile table is rendered from that same registry, so a gate
cannot be missing from the table while still being checked, and a test asserts exactly four rows.

**The alcohol gate has its middle tier back.** The flattened rule made a 14.2% cool-site Syrah with
dinner fail a rule the profile called unbreakable, which is his stated reason for drinking Syrah.
Outcomes are now four values, not a boolean: at or under the typed ceiling passes; between it and
14.5% is CONDITIONAL with a cool site or food — a pass that prints its basis ("14.2% — above your
14.0%, allowed for the cool site") and is never promoted silently; over 14.5% fails; an unknown ABV
is UNKNOWN, is reported, and loses to a bottle whose number is on record. The sweetness line carries
the same shape in the other direction: the 4.0 g/L threshold renders as *Ours*, and an off-dry wine
with a spicy dish is conditional rather than failed.

**The prompt was a second copy of the profile, and the prompt won.** "Preferences, not rules" sat
right after the alcohol line, so a model told the ceiling was a preference could recommend a 14.6%
bottle and call it a departure while the engine applied a hard gate. `PalateFile.renderModelPrompt`
is now the only path: it returns the text plus the field id behind every line, and
`assertEveryLineRendered` throws on any line with nothing behind it, so a hand-written sentence
cannot be added to the prompt. The gate sentence is generated from severity — "Gates: hard. A bottle
that fails one is not offered." whenever a hard gate is configured. The restaurant price range is
prefixed `derived:` and labelled *Ours* instead of being read as something he said, and the line
about wanting to know where a wine comes from — which no answer supports — is gone. Anything the
prompt carries from the optional interview now appears in the receipt too, from the same stored
answers.

**A sentence he wrote is no longer replaced with "not set yet".** The anchor line printed "nothing
here has read them into one sentence" while the sentence sat in the input. A written anchor is a
*Yours* field and survives regeneration; only its absence says "not set yet", and then it names the
missing answer.

**The allowances now fire outside the tests.** The 14.0–14.5% tier and the spice allowance for an
off-dry wine were written and tested, but every caller passed no dish, so a 14.2% bottle with dinner
still failed a rule that was authored to allow it. Ask now reads the dish from the question itself
and otherwise from the standing food-context answer, and that dish is what the cellar digest, the
shop verdict and the wine-list ranking check against. On the emulator, a 14.2% Syrah beside
chicken tikka came back recommended with the number stated ("14.2%, which is above your 14.0%
limit, but it is allowed for this dish"); the same bottle with "what should I open tonight" and a
food answer of "wine on its own" came back as failing the gate; and with a takeout food answer it
came back as allowed for the food.

**Confidence is computed, not typed.** `14.0% or under` stays *Yours*; the 4.0 g/L threshold is
*Ours*; turning "I don't want heavy oak" into "restrained oak, vanilla in the background" is an
inference, so that line is *Ours* too. And a bottle's reference note now renders only with its
source and the date it was retrieved — an unattributed note keeps its flavour claims out and shows
producer, region, grape, vintage and rating only.

### 1.2.1: notes take spaces

**Notes take spaces.** The three answers only you can give — what you loved, what you did not, what
you are curious about — could not contain a space. The profile is rebuilt on every keystroke, and it
trimmed the text on the way in, so a space was erased the moment it was typed and "2019 Ultreia"
came out as "2019Ultreia". The links sentence had the same trim. Both now keep exactly what was
typed, and `PalateFile` trims on the way out, so the file and the brief read the same as before.

### 1.2.0: the month says what it cost, and the cap holds

**A spend cap, counted from real usage.** A paid key bills by the token, and the app had no idea what
it had spent: the only place to find out was the provider's console, after the fact. Every answer
now has its `usage` counts priced at the answering model's published rate and added to a
month-to-date total, shown first in Settings against a monthly limit that starts at $1.00 and is
editable in the same card. At 80% the card turns amber and says how much is left; at the limit the
request is refused before anything is sent, with the date it resets and where to raise it. A limit
of 0 removes the cap, free models add tokens to the count but nothing to the total, and a provider
that reports no usage is left uncounted rather than estimated. The month rolls over on its own and
last month's total is kept.

**What a scan costs, in numbers.** The Gemini card in Settings described only what the free tier
allowed, which read as though a paid key changed nothing but the bill. It now says both: the paid
tier allows far more requests a minute (raise the setting for batch testing), a label scan costs
about a third of a cent and a wine list about half, so a dollar covers roughly 300 labels a month.
`docs/COSTS.md` records where those numbers come from — the app's real prompt strings dumped and
tokenised against the API, not estimated: about 4.0 characters per token, ~1,550 tokens for a 1600px
photo, ~9,700 prompt and ~560 output tokens for a three-call label read. It also records why the app
stays on 3.1 Flash-Lite rather than the cheaper 2.5 Flash-Lite, which is deprecated on 1 December
2026 and would then bill as the more expensive 3.5.

**One taste flow, twelve optional questions, and a brief you can read.** The Taste tab collected taste
in six cards — answers, a typed ceiling, a links box, gates, pairing classes, the whole file — while
the quick start collected some of the same facts in a second place, and the short brief a model
actually reads could only be seen by reading the source. Everything now lives in one flow: ten taps,
the three things to look for, three written lines, your one sentence about what the loved bottles
share, an optional ABV ceiling, a gate that says plainly the ten taps are the 90%, then twelve
optional questions — grapes, places, what to skip, grip, acid, body, cellaring, funk, sparkling, how
you order at a restaurant, oak, and how far you will go on price. Each is one tap, skippable, and an
unanswered one is left out rather than guessed. The Taste tab is now progress, **What the model
reads** (the brief verbatim, with its length), the gates, and your answers and the file behind
toggles.

Two things that were being dropped now reach the brief. The `## Notes` sections of a hand-written or
imported `mytaste.md` — the one part of that file no question collects — are appended, capped, so a
file that used to be ignored entirely is no longer lost; and the interview's answers render one line
each. The cap moved from 1,500 to 2,000 characters because a fully answered profile was running past
it and the cut fell on the closing lines rather than on anything redundant.

**Finishing the quick start no longer throws away the seeded notes.** The file is regenerated from the
profile, which is right for the answers — but it also replaced the seeded *Notes for the assistant*
("prefer bottles I already own", "say so plainly when a bottle is past its window") with nothing, and
the brief sends those lines, so completing the flow silently changed what every request read. A
rewrite now carries that section over from the file it replaces, still under its 900-character cap.
A file whose notes were deleted stays deleted, and a new file that already has the section does not
get a second copy. The review screen's preview goes through the same carry-over, so "This is your
mytaste.md" now shows the file that actually gets saved rather than one without them.

The carry-over covers **every** `Notes…` section, not just the seeded one. A hand-edited or imported
file keeps its own text under `## Notes from my previous file (kept)`, and that was the next thing a
quick start would have thrown away. Those sections also drop any `# title` line they were pasted
with, and the brief no longer stacks its own `## Notes` heading above a section that already has
one.

**A legible list gets advice even when the phone cannot read it.** The advice was reached only
through the on-device OCR pass, so a device without the ML Kit text module — an emulator, or any
phone whose Play services OCR is missing — read a wine list perfectly with the model and then showed
it ranked and unexplained. The advice now runs on the model's own read too, with the same single
retry for a 503, the same 60-second cap and the same deadline extension. Only the advice call is
spent there: a list the model read already carries its own order, so the per-wine judgement fallback
stays with the phone path, which has nothing else to fall back to.

**A failed scan row names who was asked.** The panel said `no provider` under a 429 although the
request had gone to Gemini: the client reported a provider only when one answered. It now reports each
request as it goes out, so a failure reads `advice · Gemini · gemini-2.5-flash · 0 chars · failed` —
what tells a quota apart from a bad key. Every scan row that can fail says the same: the page read and
its smaller-photo retry, the structured read, the small print and the per-wine ranking. A failed
structured read used to leave no row at all, and a page read that ran out of time left none either;
both now show as failed with the provider and model they asked.

**A skip row that names several lines is all of them.** A model answers `SKIP | 11, 25, 26 | …` on one
line; the parser glued those digits into 112526, found no such line on the page, and dropped the whole
row — three skipped wines vanished from the answer because of how their numbers were written. Each
number on a skip tag is now a skip of its own, and a number that really is off the page costs only
itself.

### 1.1.0: the list explains itself

This is the insight redesign. A restaurant wine list and a shop shelf are answered with two or three
bottles and the reason for each, instead of a buy-or-leave verdict under every line; a bottle you
confirm explains *why* it tastes the way it does, once, and keeps the answer; and the verdict that
remains is only facts the app can check — a limit broken, a window passed, a bottle already at home.
The advice calls lead with Groq, retry a 503 once, and a shelf's answer covers the whole batch from
one tap.

**A bottle explains itself.** Confirming a scanned label now asks, once, *why* the bottle tastes
the way it does: a headline, three to six cause-and-effect reasons — printed plainly when the model
is confident, hedged with "Typically" when it is reasoning from the region, and dropped when it is
guessing — a tasting profile, a drinking window, food pairings and a line for you. The answer is
kept on the bottle and cached by normalized producer/name/vintage, so the same wine scanned twice is
answered once, and a bottle met again on a shelf takes the explanation it already has. If that one
request fails — Gemini answers about one attempt in three with a 503 — the bottle page now says so
and offers to ask again, instead of looking like a bottle nobody had asked about. Reference notes
from a web lookup now say "From the web" with the pages they came from.

**A shelf is answered, not scored.** A shop shelf now gets the same treatment as a wine list: one
advice request over every bottle the photo printed, up to three bottles worth taking — each with the
price from the record, why it earns the spot and, when a price was read, a good/fair/poor value call
— and a short list of tempting skips with reasons. The buy-or-leave pill on every card is gone with
it. What survives of the per-card verdict is facts only: a limit the bottle breaks, a window that has
passed, a bottle already in your cellar; a record that speaks to none of them gets no chip, so the
`?  nothing in the record speaks either way` line is gone from shelf and list cards alike. The scan
queue asks for the same answer from **What to buy from this shelf** — once across the whole batch,
not once per frame.

**One 503 no longer costs the advice.** Gemini answers the advice request with a 503 ("model
experiencing high demand") about one attempt in three, and the scan read that as a spent budget and
dropped straight to the per-wine ranking. The advice call is now retried once — only for a failure
that waiting can fix (a retryable error or a rate limit), and only while the scan has budget left —
with both attempts listed in the scan details panel. A second failure falls back exactly as before.

### 1.0.12: a list is answered, not scored

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

**A request the provider calls too large gets one smaller attempt before it fails.** If a chat body
still exceeds a provider's minute — the window is sized for the thinnest configured provider now,
but a conversation can outgrow the estimate, or a key can be added after a long chat — the app used
to give up and show the rate-limit message. It now retries once with the question and the newest
photo and no history, which is what actually makes the body large. One retry, then it reports the
failure as before.

**A long chat answers when Groq takes over, instead of failing.** The history window was sized from
the provider leading the chat, so with both keys configured a long conversation sent 8,000 tokens
of history to Gemini — and when Gemini was rate limited and Groq answered, Groq refused the whole
request (HTTP 413, measured) because its free minute is 8,000 tokens in total. Both numbers are now
sized from the thinnest provider configured: with a Groq key present the chat sends the 2,000-token
window and the 55-bottle digest, on Gemini too. That is less context on the lead, paid deliberately
so the fallback can actually answer.

**The chat carries a window measured in tokens, not in messages.** Every turn resends the recent
conversation, and that window used to be "the last twelve messages" — a count, which says nothing
about size. Twelve long answers, plus the cellar digest and a 2,500-token reply, do not fit Groq's
8,000-token free minute, and Groq is still the fallback. The window now closes on a token budget,
newest first: the oldest turn goes, the previous answer stays (the repetition rule reads it), and
the question you just typed always goes whole. A wide budget (Gemini, 250,000 tokens a minute)
keeps up to 8,000 tokens of history; a thin one keeps 2,000. The digest size now follows the
provider that will actually answer the chat rather than whichever provider leads scans.

**The chat no longer shares an order tuned for one-shot reasoning.** Groq's free tier is 8,000
tokens a minute, and a chat turn resends the brief, the cellar and every earlier message at 2,500
tokens of headroom — two or three turns exhaust the minute, and a chat is exactly where you keep
going. So the chat now leads with Gemini (250,000 tokens a minute) and falls back to Groq, while the
advice call keeps Groq in front: one large request, then you read it. A photo in the chat still goes
to a model that can see it.

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

