# Changelog

Newest first. Wine Verdict 1.0.0 restarted the version line when the app was renamed from
Vinoteca, so every 1.0.x entry is newer than the 1.7.x entries below it.

### 1.2.62: discovery through Google's own search, with the key the phone already has

**The map was the wrong source, and the key to a better one was already configured.** Place research
found venues by asking OpenStreetMap for things tagged *wine bar* inside a town's boundary. That
finds bars with wine in the name and misses the restaurant with a serious list under a chef's name —
and a map is stale about closures in its own way. Gemini's API can use Google Search as a tool
(`"tools":[{"google_search":{}}]` on the native `generateContent` call), which is the search a
person would do, answered with the pages it used.

`GeminiGrounding` now does discovery when a Gemini key is set: one grounded request for the settled
town, then every cited page is resolved past Google's own redirect to the venue's real host. The
research tail is unchanged, deliberately — each cited page is still fetched and has to name the
place, use a wine word, say nothing about being closed, and place it in the asked town before the
sommelier may mention it. The model proposes pages; the app still decides.

What it costs: grounding is billed per search query. On the cheap tier this app uses
(`gemini-2.5-flash-lite`) Google gives 500 grounded requests a day free, then $14–35 per 1,000; the
app's existing ten-requests-a-minute limit and twenty-minute place cache sit on top of that, so a
normal evening is a handful. No new key, no proxy, no mirror: if the key is absent or grounding
fails, the map answers as before.

A page that Google cites is not automatically a venue: redirects that land on Google's own hosts,
listings on Yelp, Tripadvisor, Instagram and YouTube, and the same site cited twice are all dropped
before anything is fetched. A response with prose but no citations is the model remembering, and is
treated as no answer — never as a list of places.

### 1.2.61: the question reaches the research, and the region is the phone's

**A real phone still answered from the cellar.** Asked *where should I go in Beaverton for good wine
tonight?*, the app replied *Beaverton is not on my record of wine menus…* — the research never ran.
The sentence names no venue noun and no outing word, so the classifier did not recognise it and the
question fell through to the general chat. That shape is now a place question: *where should I go /
where to eat* plus wine as the reason (*for good wine*, *with a wine list*, *to drink wine*). A cellar
question that only sounds similar — *what should I drink with dinner?*, *where should I store this
bottle?* — still stays in the cellar, and both are tests.

**The region reader was proved on Android, not in a harness.** A new instrumented test
(`./gradlew :app:connectedDebugAndroidTest`) runs `DeviceRegions` inside the emulator, with the app's
own `PlaceStore` and `PlaceFinder` and no injected region:

- a runtime with no fix and no Home reports **United States, source "your phone's region"**, and no
  state — said as such;
- a saved place called Home is reverse-geocoded to **Portland, Oregon, source "your Home place"**;
- a bare **"Portland" settled to Portland, Oregon** from that runtime value, through the same
  research the chat calls;
- a runtime with **no usable region asks which Portland**, and does not default to Oregon.

Running it found a real gap: the region step asked Photon only, and this emulator resolved Nominatim
but **could not resolve `photon.komoot.io` at all**, so the state silently fell back to the locale.
The phone's own geocoder is now the second answer for a coordinate (`PlaceFinder.townAt`), which is
also how a phone with no location permission but a saved Home can still settle Portland; the order is
tested.

**Every reply now names the town it used.** An Android run showed the explanation appearing only
when places were picked, so *nothing in Portland, Oregon* read like a silent default. The town and
its reason are appended once, by the reply itself, for every outcome.

### 1.2.60: which Portland, said out loud

The question that started this is asked without a state: *where should I go for dinner tonight in
Portland with good wine?* The map knows twelve Portlands and, until now, the app took whichever
came first. Now the phone settles it, and the reply says so.

- **A bare town name is settled by the phone's own region.** In order of how true it is: a fresh
  location fix, reverse-geocoded to town and state; the saved place called Home; the phone's locale
  region (country only, and said as such). Twelve Portlands, and the reply reads *I used Portland,
  Oregon — your phone's region is Oregon, United States.* A phone in Washington asking for
  Vancouver gets Vancouver, Washington, and the reply names it; the same question from British
  Columbia would get Vancouver, BC.
- **When the phone cannot settle it, the app asks.** No region, or a region none of the towns is in:
  *I found more than one Portland: Portland, Oregon; Portland, Maine; Portland, Texas… Your phone's
  region is California, and none of them is there, so I will not guess.* A town the map has never
  heard of says that, separately from a map that did not answer.
- **The reply quotes the page, venue by venue.** Each recommendation now carries a short verbatim
  window of the venue's own site under *Page:*, so a reader can check the recommendation against the
  source. Markup fragments and JSON-escaped quotes are stripped first; a live page had been quoted
  as `<header data-test=…>`.
- **A closure bug the audit found.** Passing a page's whole text as one string let the old bare
  *CLOSED* label rule read any page containing the word — *closed Sundays* included — as a shutdown
  and drop the venue. A bare label is now only a label: the whole of a short, separate title.
- **A refused map is the assistant's reply, produced by the production path.** The catch moved into
  the research call itself, so the chat and the live run cannot drift. A live run against dead hosts
  answered in 1.5 s: *I could not check anywhere in Portland just now… Nothing was guessed.*
- **Proved live, three ways.** The instrument now runs the exact question with an Oregon phone, the
  ambiguous Vancouver question with a Washington phone, and a forced failure followed by the same
  question again. Its transcripts record the device region, the map's twelve towns, the settled town,
  every page's length and closure verdict, the call counts, and the reply a user reads.

### 1.2.59: the place answer is proved, not promised

**The research path had never actually run.** 1.2.58 added the lookup, but its confirmation pass met
a search engine that throttles this host, so the composed reply had only ever been exercised against
test data. Asked the question that started it — *where should I go for dinner tonight in Portland
with good wine?* — the app now answers from pages it read during that question, and the release was
built with the transcript of that run.

- **The map, not a search engine.** The town's wine places come from OpenStreetMap: Nominatim first,
  asked for *wine bar*, *winery* and *wine* inside the town's own bounding box, with addresses,
  opening hours and websites. Overpass is the second opinion for the bars whose name says wine. Both
  are keyless, both answer for the town asked about, and neither hands a burst of questions to a
  search engine that can refuse the phone.
- **The address is the town check.** A Portland, Oregon question keeps only entries whose own
  address is in Oregon: the live run's own reply included a Vancouver, Washington winery and a
  Portland Wine Storage *company*, and both are now dropped before the model is asked.
- **The page is the evidence.** A venue is only numbered for the model after its own site loaded,
  named the place, used a wine word, said nothing about being closed, and agreed it is in the town.
  A lapsed domain that now serves something else — one Portland merchant's site answered with a
  crypto-casino page — is not evidence and disappears. Two records of the same winery are one
  recommendation: the second page is the same page.
- **Hours are read, and places with them come first.** The page budget is four, so the order
  decides what the model sees: somewhere to sit down that says when it is open is read before a
  shop that does not.
- **Busy is a reply, not a red card.** When neither map source answers, the assistant says it could
  not check anywhere just now, that nothing was guessed, and to ask again in a minute — in the
  conversation, not as an error banner. No venue is named from memory.
- **A town's answer is reused for twenty minutes** and the map is asked once, so a second question
  at the same table ("what about something cheaper?") costs no requests; Nominatim calls are spaced
  a second apart, which is its published policy.
- **Proved live.** The instrument in the app's own test set runs the production research path for
  the failed question end to end and keeps its transcript: the query, all 22 places with their
  addresses and hours, each page's length and closure verdict, the numbered evidence, the model's
  raw pick, and the reply a user reads — two places, each with a page fetched in that run, none
  marked closed, each evidenced in Portland, Oregon.

### 1.2.58: the sommelier looks places up, and checks them

**Three closed restaurants in a row.** Asked “Where should I go for dinner tonight in Portland with
good wine?”, the sommelier named three places that had been permanently closed, one of them for more
than two years. Nothing in the app could catch that: the answer was the model's memory of a city it
has never been to, no page was fetched, and nothing checked the names in the reply against anything.

A question about somewhere to go is now researched the way a bottle question already is.

- **The app does the looking.** A place question is recognised from its wording (a venue noun, or an
  outing plus a word like *where* or *recommend*; a question about opening a bottle stays with the
  cellar), and the town is taken out of the sentence. One keyless search finds names — business
  pages, the bars' own sites and the list a directory prints in its own description — and each name
  then gets its own quoted confirmation search.
- **A closure is code, not prose.** Any page that says *permanently closed*, *closed for good*,
  *closed its doors*, *no longer in business* or *closed in 2023* removes that place before the model
  ever sees it. Hours are not closures: *closed Sundays* and *closed on Mondays* leave a place in.
  A name that no result confirms is not a place and is dropped.
- **The model only chooses.** It gets numbered entries — name, host, what the search said, what the
  page said — and replies with which numbers and why. The reply is then written by the app from the
  fetched record, so the name, the source and the address of a place are never the model's words. A
  venue that is not in the evidence cannot appear on screen.
- **Two Portlands.** “Portland” is at least two cities with their own wine scenes, and a live run
  answered with Portland, Maine restaurants. When the phone is standing in the town named in the
  question, its own state is appended ("Portland, Oregon"); a town the user is not in is left as
  typed, because the app does not know which Lisbon was meant.
- **Busy is not empty.** The search engine answers a burst with an HTTP 202 challenge page, which the
  app used to read as "no results" — blaming the connection or the bottle for something the engine
  did. That is now recognised and reported as *the search is busy, ask again in a minute*, in every
  lookup that uses it. A place question asks five searches, so it meets that page.
- **When there is nothing to say, it says nothing.** No confirmed places, no evidence of an open
  door, or a single-word question about where: the answer asks for a town, or says the pages
  confirmed nothing, instead of naming somewhere from memory.

### 1.2.57: the cellar can be put in an order, and every style has its chip

**One order, and four styles with no way in.** The cellar was always read the same way — the bottles
with a heart, then what is actually on the shelf, then the nearest the end of its window, then the
producer — so a bottle bought that afternoon landed wherever its producer put it. The filter row
stopped at Sparkling, so a rosé, an orange wine, a Sauternes and a port had All or nothing between
them, though the record has always stored the style the model read.

- **Five orders, named under the chips.** The row says which order is in force and opens a menu:
  **House order** (what the cellar has always done), **Recently added**, **Producer A–Z**, **Newest
  vintage** and **Highest price**. A bottle with no price or no vintage goes to the bottom of those
  two rather than the top, because a missing price is not a cheap one.
- **Seven style chips** — red, white, rosé, sparkling, orange, dessert and fortified. They sit after
  the questions about what to do with the shelf, not in the middle of them: the strip scrolls, and
  four new chips in the middle would have pushed **No bottles** — the only route to a saved wine or
  a bottle that is all drunk — off the end of it.
- **A style chip is a record, not a decision**, so it still shows a bottle with none left: a rosé you
  finished is still a rosé you want to remember. **Drink up** and **To buy** still count stock only.
- The order survives rotation and a process kill the way the filter already did, and the sort is
  applied last, so it can never change which bottles a filter and a search kept.

### 1.2.56: the circle is drawn in yards

**The radius picker said metres.** Twenty-five metres, forty-five, three hundred — numbers nobody
chose, on a screen whose whole job is to ask how far from the pin still counts as here.

- **The circles are 25, 50, 100, 200 and 500 yards**, and a new place starts on the fifty. Every
  one of them reads as a round number, because a circle is drawn by eye and "49 yd" would be
  precision nobody asked for.
- **Everything that says a distance says it the same way**: the sentence under the picker ("A circle
  of about 50 yd around the spot"), the line beside each saved place in Settings, and the reach for
  naming a business from a fix — a hundred yards, which is what eighty metres was.
- **Nothing stored changed.** A radius is still one integer of metres, so places saved by 1.2.54 and
  1.2.55 read naturally (150 m is 165 yd) and the backup format did not move.

### 1.2.55: a half-typed place finds the one down the road

**Typing "Loyal L" on a phone that had never been asked found a town in Kentucky.** The search is
ranked by how near it is, which needs to know where *near* is — and the field only asked for the
phone's position when the location icon was tapped. On a fresh install, typing first meant searching
from nowhere: the four branches were there to be found, and the only thing on offer was an address.

- **The first thing typed in the box asks.** Once per night's sheet, at the moment there is
  something to search for, Android's location question appears — *While using the app*, *Only this
  time*, *Don't allow* — and the words already typed stay where they are while the list is asked
  again from the fix.
- **The icon still asks**, for a place that was never typed, and a refusal is not asked over and
  over: one question per sheet, then the box works as it always did.
- **Said honestly when there is no fix.** Permission granted, but the phone cannot say where it
  is: "No location fix, so this search is not nearby. Type the whole name." — rather than a search
  that quietly looks local and is not.

### 1.2.54: a night that knows where it happened

**A place was a line to retype every time, and "home" was typed home.** The book kept *where* as
free text and nothing else: no way to pick the restaurant you were sitting in, no way to say that
this pin is Home and have the next night say so by itself.

- **Typing finds the place.** The *Where* box offers nearby businesses by name and town as you type —
  *Loyal Legion — Beaverton*, *… — Portland*, *… — Vancouver*, each with its street beneath — so four
  branches are four rows rather than four identical ones. Measured on the emulator: half a name
  (`Loyal L`) returns the four, and a full one (`Loyal Legion`) returns them without needing a fix.
  The text goes to komoot's Photon, an OpenStreetMap search: no key, no account.
- **The location icon tags what is there.** One fix, then: a saved place's circle first, so *Home* is
  *Home* with no lookup at all; otherwise a named business within about eighty metres; otherwise
  nothing — a house answered with its street number is not a place anybody said, and it offers
  **Save this spot** instead.
- **Saved places.** A name, a pin and a circle, 45 m by default and adjustable per place. They are
  chips under the box for one tap, and **Settings → Saved places** is where one is renamed or its
  circle re-cut. They travel in the backup with the nights, because a circle drawn on this phone
  cannot be looked up again anywhere.
- **Three fallbacks, in order.** Photon for the names, the phone's own geocoder for an address when
  Photon is silent, and your own typing after both. Refuse the permission and the box is what it was.
- **What it costs.** Photon's terms promise no availability and throttle heavy use, so an empty
  answer, a timeout and a failure all mean the same thing here: no suggestions, and the address or
  your words behind them. The search bias sends a position rounded to about a kilometre; the tag
  sends the pin itself.

**Two things only the device showed.** The reverse lookup was being sent with the same rounded
position as the search bias, so asking about the pub's own pin answered with a house down the road —
it now sends full precision. And a fix from two days earlier was being used as "where I am"; a fix
older than ten minutes is now refused rather than guessed with, because a stale pin tags the wrong
place.

### 1.2.53: a grid a screen reader can walk, and a list for the next shop

**The calendar's days said the right thing but could not be reached.** Each day carried its
sentence — the date, what was written on it, *today*, *selected* — and TalkBack read it when a
finger landed on it, but the day was not a focus stop of its own: keyboard and switch traversal
stopped at the year arrows and left thirty days unread.

- **Every day is a focus stop.** The grid is walked in date order, and each day announces what it
  is and what it holds: *Friday 27 November 2026, 1 entry, today*, and *…, selected* once it is the
  day being shown. Opening a day says so again with *selected*.

**A bottle could be loved, finished or never opened, and there was no way to say "buy this again".**
A wine list had *Save this one*; the cellar itself had nothing for the next shop.

- **To buy.** A bottle's page carries a **To buy** chip that names its own state, and a **To buy**
  filter third in the cellar's row is what to take shopping. A flagged bottle says **To buy** on its
  row, so it is recognisable in *All* as well as in the one view that asks for it.
- **No verdict needed.** The flag is separate from the four verdicts: a bottle at none left and one
  never opened can both be wanted again, and a cellar written before the flag loads with nothing
  flagged.

### 1.2.52: a calendar you can hear, at any text size

**The calendar was a grid of bare numbers to a screen reader.** Every day announced itself as "27",
a dot under it meant nothing to TalkBack at all, and nothing said whether the day was the one being
shown — so the one view built for finding an evening was the one that could not describe it. At the
largest text size the square cells clipped the numbers they existed to show.

- **Every day is a sentence.** A day reads as *Thursday 27 November 2025, 2 entries* — the date, what
  was written on it, and *today* when it is — and the day being shown reports itself as selected.
  Marked-up days no longer depend on a dot only sighted users can see.
- **The controls say where they lead.** The month and year arrows are *Previous month, October
  2026* rather than "previous month", the title is a heading a screen reader can jump to, and the
  weekday letters are skipped instead of read as "S M T W T F S".
- **The grid grows with the text.** A day's height comes from the number in it, not from a square,
  so at the largest font size the dates stay whole and the row grows instead of clipping.

### 1.2.51: a calendar that reaches the years, and a night a year old

**The calendar walked a month at a time and nothing else.** A book that spans five years needed
sixty taps to reach its first evening, and there was no way home to this month except tapping the
same distance back. And the one evening the book knows best — the same date a year earlier — was
never mentioned.

- **Today, and a year at a time.** **Today** beside the month stepper comes back to the month the
  phone is in, and is off while it is already showing it so it is never a dead tap. Two double
  arrows move the calendar a year for each tap, and the title says which year the month landed in.
- **A year ago tonight.** When the calendar is pointing at a day whose date a year earlier carries
  entries, a card under it says so: the wines with the words they were given — up to three, then a
  count — under the date. Tapping it opens that night, so an anniversary is read, and corrected, in
  one step instead of a search.
- **The anniversary is the date, not 365 days.** February the twenty-ninth of a leap year goes back
  to February the twenty-eighth, which is the day it was, rather than to the first of March.
- **With nothing a year earlier, nothing is said.** The card is absent rather than empty, and the
  calendar is exactly as it was.

### 1.2.50: where the bottle was drunk

**A night in the book had a date and no place.** The line about it might say *the second bottle was
better*, and the bottle's own record knows its region, but the one fact that makes a night easy to
remember — the restaurant, the friend's table — had nowhere to go. "What did I drink at that place"
could not be answered from the record the app keeps.

- **Where, on any night.** **Add a night** and **Drank one** both ask for it, one optional line
  under the date, and **Correct this night** opens it filled in, so a place typed wrong is fixed
  with the rest of the entry. Nothing is guessed: a night with no place says nothing rather than
  "at home".
- **It belongs to the night, not the bottle.** The same wine opened twice can be drunk in two
  places, and where a bottle is stored is already the cellar's own shelf — so the place rides on the
  entry and moves with it when the night is corrected.
- **The row says it, the search finds it, Ask is told it.** The entry reads *At Osteria
  Francescana*; the book's search box matches the place with the accents folded like every other
  word; and the record Ask reads names it, so "what did I drink at Osteria" has an answer. A night
  with no place adds no words at all.

### 1.2.49: the bottles that want drinking

**The cellar knew a bottle was past its window and never said so.** Every row carried a badge and
**Ready now** counted the drinkable ones, but with two hundred bottles in the list the three that
were at their peak this month and the two that had gone over were just further down a scroll. A
window is only worth recording if something reads it.

- **A Drink up filter, second in the row.** It holds the bottles at their peak or past the window on
their record, past peak first — stock only, because a wine with none left is not a decision, and
**Ready now** is left out because a bottle you may open is not one you must. An empty result says so
and points at the one thing that puts a bottle here: a drinking window on the record.
- **An opt-in weekly nudge.** Settings → Weekly nudge posts one notification a week, on Monday
morning, naming up to three of those bottles and saying how many are past it and how many are at
their peak — for example *2 bottles to drink up: 2016 Barolo Riserva · 2019 Chianti — one past its
window, one at its peak.* A tap opens the cellar on the Drink up view. It is off until the switch is
turned on, it asks for the notification permission at the moment it is turned on, and it posts
nothing at all on a week when no bottle wants drinking, because a nudge that says nothing is the
kind people switch off.
- **The schedule is Monday, not whenever.** The first one lands on the next Monday at nine in the
phone's time zone and every seven days after that, and opening the app does not push it back — so
the reminder keeps a shape the week can be planned around.

### 1.2.48: a night that can be corrected

**A night in the cellar book could not be changed.** The date was picked days later and turned out
wrong, the verdict was tapped on the wrong word, the line was a typo — and the only way back was a
record that stayed wrong for ever, or a second entry beside it that made one evening look like two.

- **A tap on a night opens it for correction.** The date, the verdict, the taste words, the
  buy-again answer and the line come back as they were saved, and **Save** writes them in place. The
  entry keeps its id, the bottle it belongs to, and whether it came from **Drank one**, the minus
  button, your own hand or a bottle's older tasting, so the record still says how it got there.
- **The correction reaches the bottle it came from.** **Drank one** and a hand-written night are the
  two entries that write a verdict and a dated line onto the bottle, and the taste brief reads the
  bottle rather than the book. The verdict is written only when the correction actually changed it
  and the bottle still holds the word that night gave, so a later verdict of your own is never
  overwritten; the dated line is rewritten where it stands instead of a second copy appearing
  beneath it, and a line edited by hand on the bottle's page is left as it is.
- **Delete takes the entry out, and asks first.** The bottle keeps whatever it already knows — a
  rating and a note may have been given on the bottle's own page — and the confirm says so, because
  a deleted night cannot be put back from there.
- **A verdict typed onto a count adjustment makes it a night.** A bottle taken out with the minus
  button never had a word asked for it; one given now is yours, and the entry stops calling itself
  a corrected count.

### 1.2.47: the search answers the spelling you type

**`rose` found nothing in a cellar full of Rosé.** Both boxes compared the words exactly as they were
stored, so an accent the phone's keyboard did not offer, a full stop, or a hyphen was enough for a
bottle that was there not to be found — and the two boxes carried a copy each of the same comparison,
which is how they could drift apart.

- **Accents and punctuation are folded before the words are compared.** `rose` finds *Rosé*,
  `st estephe` finds *St. Estèphe*, `cote rotie` finds *Côte-Rôtie*, and `dyquem` finds *d'Yquem* —
  an apostrophe joins the word it elides rather than splitting it. A part of a word still matches, and
  every word still has to appear, so a second word narrows the list exactly as before.
- **One matcher, three boxes.** The cellar's search, the cellar book's and the book's bottle picker
  all call the same `Search` object, so a fix in the folding reaches every one of them.
- **Nothing stored moved.** Folding happens at the moment of the search, so the name keeps its
  accents on the card, on the bottle's page and in the backup.

### 1.2.46: Not now means this version, not every launch

**The update card came back every time the app opened.** **Not now** only cleared it from the screen
it was on — the answer lived in memory — so the next launch, a rotation, or a trip to another tab and
back put the same card on the cellar again, for as long as the release went uninstalled.

- **The answer is remembered by version.** Tapping **Not now** stores the release it was about, so the
  card stays away across launches, rotations and tab switches, and it returns when a newer release is
  published, because that is a different question.
- **Settings still reports it.** The Updates section answers an explicit **Check for updates** and
  can download and install from there, so a release dismissed on the cellar is never unreachable — it
  is only not repeated at the top of the list.

### 1.2.45: a backup you could rebuild a phone from

**The backup held the facts of a cellar and none of its labels.** A restore brought back every bottle
and no pictures, which is the half of the record a photograph carries. And nothing ever asked for a
backup at all: the export sat in Settings, so a phone lost in a restaurant took years of notes with
it. Under both was a smaller defect with a larger consequence — the cellar file was rewritten in
place, so a phone that died mid-save could come back to a file that decoded as empty, and the next
save made the emptiness permanent.

- **The label pictures are in the backup.** Each is kept as base64 in the same one JSON file, named
  by the record it belongs to; a restore puts the files back, and a merge gives a picture to a bottle
  it matched but left bare. The export says how many pictures went in, because that number explains
  why the file is a few megabytes. Page photographs of saved menus are still not included.
- **The cellar asks for a backup once a month.** When bottles are on the shelf and the last export is
  over thirty days old — or there has never been one — a card under the cellar's numbers says so,
  with **Export backup** writing the file from there and **Not now** buying the same month. Settings
  says when the last backup was made, and the export itself is now one shared call, so the two places
  it can be started from cannot write different files.
- **Android's own backup no longer carries the API keys.** The cellar, the book, `mytaste.md` and the
  label pictures stay eligible for Auto Backup and a phone-to-phone transfer; the settings
  preferences are excluded from both, so a key pasted on the phone never rides to a server because a
  backup was made.
- **A save is whole or it does not happen.** `cellar.json` and `drinks.json` are written to a
  temporary file, flushed to the disk and renamed into place, with the file they replace kept as
  `.bak`. A load reads the file, then that copy, then an unfinished write — so a crash costs one
  start's changes rather than the cellar — and a file nothing can read is kept aside as
  `.unreadable` instead of being replaced by an empty cellar.

### 1.2.44: the update card in plain words

**The card that offers a new release printed its notes as Markdown.** The notes are the changelog,
written for the release page, so the first thing the card said was `**Five small things…**`. The
card now takes the heading marks, dashes and asterisks off each line, so what it shows is prose.

### 1.2.43: the book you can search, a calendar, and a quieter cellar

**Five small things that made the app ask for taps it should not.** The cellar value was printed on
the label whether or not it was wanted; the Cellar tab repeated the Cellar book as a card beside the
tab that already opens it; an update was only found by opening Settings; the cellar book could be
read but not searched; and a bottle could only be found by remembering its name, never the evening.

- **The cellar value can be hidden.** Settings → Currency has **Show the cellar value**. Turning it
  off takes the PAID total off the cellar label and leaves every bottle's own price alone, so the
  number is there when it is wanted and not on the table when it is not.
- **The Cellar book card is gone from the Cellar tab.** The book has its own tab; the cellar is the
  shelf again, and the saved menus card stays because a list is not a tab.
- **An update is checked for at start-up.** One quiet GET when the app opens; when a newer release
  exists it is offered at the top of the cellar with its first lines of notes, **Update**, and
  **Not now**. Downloading is still a tap and Android's installer still asks its own question. A
  failed check is silent, because a phone with no signal still has a cellar to open, and Settings
  opens on the same answer instead of asking GitHub again.
- **The cellar book can be searched.** The box matches every word against the label, the colour, the
  region, the note and the taste words, so two words narrow the record rather than widen it. The
  counts above stay about the whole book and the record itself is never filtered — only what is shown.
- **The cellar book has a calendar.** A month at a time, with a mark and a dot on every night that
  carries an entry; tapping a day narrows the list to that evening, tapping it again lets go, and
  **Show all** clears it. Weeks start on the locale's first day.

### 1.2.42: the book catches up with your bottles

**A verdict given before the book existed had nowhere to show.** The record only starts when a bottle
is opened through the app, so bottles rated and noted in older versions — and the nights they were
drunk on — were invisible in the Journal, which read as entries having been lost. An upgrade from
1.2.40 was tested with the published APKs: the entry survived and appeared in 1.2.41 untouched, so
the update is not dropping anything.

- **From your bottles.** When the cellar holds bottles with a verdict or a dated tasting note and no
  entry in the book, the Journal offers to bring them across in one tap. Each is dated by its newest
  note, or by when the bottle last changed when it has no note, and is marked **from the bottle**
  rather than as a night the app saw. A bottle the book already has an entry for is left alone, so
  nothing is duplicated, and a second tap cannot write the same tasting twice.
- **The counts keep the two apart.** The seven- and thirty-day lines count nights, not tastings, and
  the page says how many entries came from bottles. Ask's digest says the same, so a tasting is never
  reported as a bottle that left the shelf.
- **The Cellar book card and the backup wording say entries**, because the record now holds both.

### 1.2.41: a cellar book you can write in

**The drink log could only be read.** Every bottle that left the shelf was recorded, but a night the
app did not see — a restaurant bottle, or one from before it was installed — had nowhere to go, and
the one record people would keep was the one file the backup left behind. The Taste tab was also
doing two jobs: the profile is something you set up and then rarely open, while what you drank is
something you come back to.

- **The Taste tab is now the Journal.** It opens the **Cellar book**: the nights newest first, the
  verdict and note given at the time, and the survey words a hand-written entry carries. The taste
  profile moved into Settings with a back arrow; the quick start, the brief and mytaste.md are
  unchanged and one tap from there. The tab says **Journal** because "Cellar book" does not fit a
  five-column bar beside "Cellar" at normal text size; the page itself is named the cellar book.
- **Add writes a night by hand.** Pick a bottle already in the cellar, or name one you do not own.
  The date is editable, because the entry is often made days later, and a remembered night claims
  no clock time.
- **The three-question survey is the part the model can use.** The four-word verdict, up to six
  taste words and "would you buy it again?" are written onto the bottle — the verdict only when one
  was given, never clearing one that was already there, and the words added to the record's own
  rather than replacing published notes. A wine you do not own becomes a record with no bottles, so
  it appears under **No bottles** and reaches the taste brief through the same loved-and-disliked
  channel as any rated bottle. The drink digest tells Ask a hand-written line from one recorded on
  the night.
- **The cellar book is in the backup.** Exports carry every night, a restore merges by id or
  replaces with the cellar, and a version 1 file still opens with an empty book. A backup of only
  nights is no longer rejected as empty.

### 1.2.40: page pictures are marked, and a check that reconsiders them

**The junk already in the cellar could only be cleared one bottle at a time**, and nothing told the
app which pictures it had guessed at: a page's photograph and a label the user photographed were
both kept under the record's id.

- **A picture the finder pulled from a page is named `web-<id>.jpg`**; a label photographed here
  keeps `<id>.jpg`. The mark is the only thing that says a photograph was the app's guess rather
  than the user's record of the bottle.
- **Settings → Bottle pictures gains Check**, which measures every kept picture with the same gate
  the finder applies before keeping one — no network for the pictures that pass, a search only
  where one fails. A picture that is not the bottle is looked for again; a page's picture with
  nothing better to put there is taken off, because a bare tile with a camera is honest; a
  photograph the user took is only ever **replaced** by one that does look like the bottle, never
  deleted on the gate's word alone.
- **Pictures kept before this build carry no mark**, so they are treated as the user's: the check
  still measures them and re-finds them, but it will not empty the bottle when nothing better turns
  up. Existing junk is replaced where the pages have a bottle to offer, and left otherwise.
- Replacing a picture now deletes the file it replaced, so a bottle holds one picture rather than a
  trail of them.

### 1.2.39: a wordmark is not a label

**1.2.38 stopped the banner and the cork, but a review site's "International VINE REPORTER" card and
a producer's full-bleed theme photo of the vineyard rows still got through.** Both are plain-field,
bottle-shaped images, so a test that only asked for shape and a plain border had nothing to say
about them.

- **A third question is asked of the pixels**: the subject has to span the height of the frame (a
  bottle standing on a field, or a label filling the frame), and a full-bleed landscape picture is
  never kept — that is a hero or a newsletter banner, not a bottle.
- **A texture test**: a picture whose neighbours differ sharply across the whole frame — a woven
  label close-up, vineyard rows, a corduroy pattern — is dropped rather than kept as a label.
- **Measured on real pictures**: the ten good ones 1.2.38 kept had subjects covering 0.80–1.0 of the
  frame height and busyness of 0.03–0.22. The Ridge theme photo filled a landscape frame edge to
  edge (rejected), the Prospice newsletter strips were full-bleed landscape 320×231 (rejected), and
  the real Ridge bottle image on that page measures 0.24 wide by 0.97 tall — the next candidate now
  taken instead. The wordmark card itself was not measured against its own page; it is covered by a
  synthetic test of a text band in a white card.
- **Fewer pictures, but no wrong ones.** A picture that fails sends the search to the next candidate
  on the page; the ranking and the three queries are unchanged, so a boutique producer's page can
  still yield nothing, which is the honest answer for a closed winery.

### 1.2.38: pictures that look like the bottle, and a filter that stays

**The picture finder kept the shop's own furniture.** A page's `og:image` was trusted blindly, and
a shop's social card is often its banner: one cellar came back with a 976×291 "Uncork Life" strip
as a bottle's photograph, a candlelit lifestyle pour for a Pinot Noir, and a producer's cork. Only
about one bottle in ten got a picture at all.

- **The page's own product data now comes first**: schema.org `Product.image`, then `itemprop="image"`,
  then an `<img>` that names the wine in its address or its alt text (including lazy `data-src` and
  `srcset`), and only then the social cards. A picture whose address or alt says logo, banner, sprite,
  cork, capsule, candle, glass, gift or vineyard is never a candidate.
- **Every picture has to pass a gate before it is kept**: at least 150 px on the short side, a shape
  between 0.45 and 1.9 (a bottle is tall or square, a banner is not), and a border that is one colour
  rather than a scene. The 976×291 strip, the 450×1308 logo strip and the candlelit room all fail it.
- **Three searches, five pages**: the exact bottling, then "wine label", then the wine without its
  vintage — an older bottle often has no page for its year, and the label is what gets recognised.
  PDFs and sites that answer a phone with a wall are skipped instead of spending a request.
- **A bottle added by search falls back too**: if the page that named it publishes nothing usable,
  the pages that name the wine are asked for its label.
- **Measured, not assumed**: on ten wines from shops and producers (Thesis, Stoller, Margaux, Keller,
  Produttori, Aubert, Penfolds, Duckhorn, Fontodi, Wittmann) the new pipeline kept a picture for nine,
  and eight of those nine were checked by eye and were the right bottle or its label — two were flat
  label fronts. The old pipeline kept the banner, the cork and the candle. Not measured on a real
  cellar of fifty, where the pages are the ones the user actually owns.
- **The cellar filter is sticky.** Opening a bottle and coming back rebuilt the list with no filter;
  the filter now lives in saved screen state and survives the trip (and a rotation).

### 1.2.37: a picture for the bottles already here, and a way to change the wrong one

**1.2.36 gave a picture to a wine added from a search, and left the bottles already in the cellar
bare.** And once a picture was there, the page could add the first one and nothing else: a label
scanned by mistake, or a shop's stock shot of the wrong bottling, had to stay.

- **Settings → Bottle pictures** walks the bottles that have none, asks the pages that name each one
  (producer and name, at most three pages) for the photograph they declare, and keeps the first
  that answers. No model, so no quota and no API key; one bottle at a time with a pause between
  them, stoppable, and a run that is stopped carries on from where it had reached.
- **The page has to name the wine.** Producer and name are matched against the page before its
  picture is taken, folding case, accents and punctuation — a photograph of the neighbouring
  bottling is worse than no photograph.
- **A page that publishes none is an answer**, not a failure: the bottle stays bare and the
  thumbnail keeps offering the camera, which is the honest record of that shop.
- **Replace or remove the picture from the picture itself.** Opening the label now carries
  **Replace** (camera or gallery, overwriting the file) and **Remove** (the file and the field
  deleted together, so no orphan is left). The thumbnail then goes back to offering a camera.
- **Nothing is replaced automatically.** The job only touches bottles with no picture, so a label
  you scanned or a photograph you chose is never written over.

### 1.2.36: the bottle's own picture, on its page

**A bottle had a photograph only if it had been scanned as a label.** Everything else — typed in,
found by name, imported, ordered off a menu — reached its page with nothing to check a vintage or a
cuvée against, and the picture a scan did keep sat in a card half way down the page rather than
beside the bottle itself.

- **A thumbnail beside the title**, and a tap opens it full screen with the pinch and drag the kept
  menu pages already use. The card that used to hold the label is gone: the picture is part of the
  bottle now, not a section under it.
- **A bottle with no picture offers to take one** — camera or gallery, down-scaled to the size a
  scanned label is kept at, written through the same call the scan uses and named on the record by
  its own write, so a lookup finishing at the same moment cannot undo it.
- **Added by search, the bottle arrives with the page's own picture.** The page that named it
  declares a photograph of the wine (`og:image`, then the Twitter card); that one image is fetched,
  kept at label size and attached after the bottle is saved, so a slow shop page never holds up the
  add. A relative or protocol-relative path is resolved against the page, an `http` picture is asked
  for over `https`, and an image over four megabytes or not an image at all is refused.
- **A page that declares nothing leaves the bottle without a picture** rather than inventing one —
  the thumbnail then offers to add one, which is the honest answer to a shop that publishes none.
- **Nothing is replaced.** A label you scanned, or a picture you added yourself, is never overwritten
  by a search result; the picture is only taken when the record has none.
- Said plainly: the files are app-private, so the JSON backup still carries records, not pictures.

### 1.2.35: a proposal card names every bottle, not the first twelve

**A move of 23 bottles listed twelve of them and said "and 11 more".** The card is the thing being
approved, so the bottles it left out were the ones being approved unseen — and a big move, the case
where that matters most, was exactly the case that hit the cap. Twelve was there so a hundred-bottle
query would not fill the screen; a tall card is just a tall card, and the transcript already scrolls.

- **Every match is listed**, on the change card as well as the move card: the values first, then each
  bottle those values will be written to.
- **The count and the list can no longer disagree**: "Move 23" sits under twenty-three lines, so the
  bottles in the list are the bottles the button moves.

### 1.2.34: Ask can change a bottle's fields, not only its place

**The chat could propose a move and nothing else.** A shelf, a price, a note, a count and a vintage
are the fields a cellar is actually corrected in, and all of them still had to be typed bottle by
bottle on the detail page.

- **`UPDATE` is the second proposal, on the same query.** Grape, type, producer, name, region,
  country, appellation, a vintage range and the current place select the bottles exactly as a move
  line does; the new values ride on top: slot, price, note, quantity, vintage. One matcher serves both
  proposals, so a bottle cannot be selected by one and missed by the other.
- **The card says what changes and what it was.** "Price → USD 48.00 (was USD 55.00)", "Bottles → 3",
  "Slot cleared (was Top shelf)", "Vintage → 2019", "Add to my note: …". An old value is printed only
  when every matched bottle agrees on it; otherwise the new value stands alone rather than listing
  four different olds. The bottles themselves are listed under the values.
- **Nothing is written until Apply.** Confirming edits the whole set in one write, and a null field is
  never touched — every key the model left out stays exactly as it is. A note is appended as a dated
  line, the shape a tasting note has always had, so the chat can add to what you wrote but cannot erase
  it. A negative price, a negative count or a vintage that is not a year refuses the whole proposal
  rather than dropping that one value and writing the rest.
- **A quantity is a correction, not a drink.** Setting the count does not add an entry to the drink
  log: the minus button takes a bottle out, an edit says the record was wrong.

### 1.2.33: a move can say where the bottles are now

**"Move all Prospice bottles not with a location to garage fridge" offered 39 bottles.** The move
query could name a grape, a producer, a region and the place to move to, and nothing about where a
bottle lives now — so the model sent what it could («Prospice») and dropped the rest of the sentence
without saying so. The card was honest about the wider set, which is how the gap showed.

- **`fromLocation` is a key of the query.** A place name means only bottles currently there; the
  words *unassigned*, *none*, *nowhere* and *no location* mean the bottles with no location at all —
  including bottles whose location was deleted, which the app already shows as Unassigned. A place
  name the cellar does not have matches nothing, so a typo fails loudly instead of widening to the
  shelf.
- **The model is given the record, not just the target.** The prompt now lists every field a bottle
  record holds and says plainly that those are the facts the app can match on, that a digest line
  prints only the fields that are set, and that a line with no place after it is an unplaced bottle.
- **A clause that cannot be expressed is refused, not widened.** The rule is now explicit: say which
  part cannot be filtered on and send no move line, rather than sending a broader match than the one
  asked for. The card also names the narrowing it applied — "Only bottles with no location" — so an
  ignored half-sentence is visible before anything is moved.

### 1.2.32: Ask can move bottles, once you confirm

**Ask answered "I have updated the location for the following bottles" and nothing had moved.** The
chat is prose in and prose out — it has no write path at all — but its opening line called it a
sommelier "who also keeps track of my inventory", and no rule said otherwise. So a request to move
every Cabernet Sauvignon to the Bar Fridge came back as a tidy list of bottles and a confirmation of
an edit that never happened: the bottle page still said Unassigned.

- **The app does the matching, not the model.** A move request now arrives as a query — grape, type,
  producer, region, country, appellation, a vintage range, a place — and the app resolves it against
  every bottle in the cellar. That is the point of a query: the prompt only carries a digest of the
  shelf (55 bottles on the thin budget, 140 on the wide one), so a list of names the model wrote would
  have been short without saying so.
- **The card shows what will move.** The matched bottles each with where they live now, the target
  location and the count. A place the cellar does not have yet is offered for creation on the same card
  rather than refused or quietly invented. Bottles already there, and bottles with no stock, are not in
  the list to move.
- **Nothing moves until you tap Move.** Confirming writes through the same repository call the bottle
  page's location picker uses, as one write for the whole set; Cancel discards the offer. The marker
  line is stripped from the answer before it is stored, so the JSON never reaches the screen or the
  next question's history.
- **The chat is told it has no write access.** It may propose a move, and must say the app will show
  the bottles and move them on confirmation; it must not claim to have moved, added, renamed, deleted
  or drunk a bottle.

### 1.2.31: the reminder stops once the evening is recorded

**The nudge fired an hour after the page was read, whatever had happened since.** Rate every wine on
the list, mark the bottle you ordered, and it still arrived asking how the wines were — the app's only
notification, at the moment it had least to say.

- **Recording the evening settles it.** The first wine you order or judge on that list cancels the
  pending nudge, and takes the notification out of the tray if it has already landed.
- **It knows which list it is about.** Only one nudge exists at a time and it belongs to the newest
  list, so a pick on a menu from last month cannot silence tonight's reminder.
- **Deleting a menu cancels its own reminder too** — a notification to reopen a page that no longer
  exists is worse than none.

### 1.2.30: Ask can see the menus

**The chat was given the cellar and the drink log, and nothing about restaurants.** Eleven releases
have built saved wine menus — the place, the day, the wines, what was ordered, what was refused — and
Ask could not see any of it, so "what did I order at that Italian place" had no answer and the one
thing a menu makes certain, that a wine was turned down there, was invisible.

Each kept menu now reaches the prompt as one line: **the place, the date, the number of wines read,
the wines you picked with their verdict word, and the wines you called Not for me there.** Verdicts
are read by identity, so a bottle judged before the menu was kept still counts, and a refused wine
is never listed as picked — the chat is told what was ordered and, separately, what was refused. The
budget is honoured: six menus when the room is wide, three when it is thin.

With that in context the rules are explicit: answer restaurant questions from the menus and the drink
log only, never invent a place or a page, never suggest a wine marked not for me there, and on a menu
you have eaten at before prefer the wines you picked and loved.

### 1.2.29: a wine you said no to is not a suggestion

**"Not for me" sat in the same grey as "Fine".** On a menu it was one badge among several on a
card whose whole purpose is to tempt, and the advice could happily recommend the same bottle a
second time — the one answer the app holds for certain, argued with by a menu.

- **The flag interrupts.** A wine on record as Not for me gets a filled block instead of a badge: the
  word in the error colour, and the sentence that dates it — "You called this Not for me yesterday."
  Fine and Good remain ordinary badges, because a second glass can change an opinion and only a
  refusal is a refusal.
- **It is dropped from the advice's picks**, before anything is rendered or kept, so the top pick
  becomes the next wine the answer wanted rather than one you have refused. The missing pick is
  named rather than silently vanished: "Left out of the answer: X — you have called this Not for me."
- **Dated properly.** The verdict's own edit time is used when the bottle carries the word, and the
  night's entry when the word was given in a glass, so the flag is never a bare opinion.

### 1.2.28: a menu now says which wines you have had

**Scanning a menu could not tell you that you already knew three of its wines.** The reader matched
nothing against the cellar unless you pressed a button, so the one question a printed menu can never
answer — have I had this, and what did I think — was left to memory.

Every wine on the page is now matched by identity, producer, cuvée and vintage folded for case and
accents, and each card carries the answer before the advice: **In your cellar · 2 bottles**, **You had
this yesterday** (from the drink log, with the day said the way the question would say it), **On
record, no bottles** for a wine you finished or kept, and the verdict word when one was ever given —
the bottle's own word first, then the last night's. A wine the app knows nothing about says nothing,
because "not in your cellar" under every line on a menu is noise. The advice picks carry the same
line, so the wines it recommends are the ones you can see you have already loved.

### 1.2.27: ordering a bottle you already own no longer duplicates it

**"Noted for the glass" put a second row of the same bottle in the cellar.** The order button wrote
the menu's copy of the wine in with the menu's own id, so a wine already on record — added from a
label scan, an import or an earlier menu — arrived beside itself: two rows, the same producer, cuvée
and vintage, and a bottle count that now disagreed with itself.

Identity decides it now, the same match the CSV import and the cellars already use: producer, cuvée
and vintage. A wine already on record is left exactly as it stands, stock included, because ordering
a bottle at a table is not buying another one; a wine not on record is added as before. The snackbar
says which happened — "Already in your cellar — noted for the glass" — and the pick is still recorded
on the menu, which is a fact about the evening rather than about the cellar.

Duplicates created by the old behaviour are left alone: merging two rows is a decision about which
count and which notes are right, and that is the user's to make, not an upgrade's.

### 1.2.26: the fallback reader knows the place too

**A page only the line reader could follow was still saved under its date.** 1.2.25 taught the
structured read and the phone's own pass to return the restaurant's name, but the line-format reader
— the one that answers when the structured reply fails, which on a long menu is regularly — was
still told to skip the letterhead. It now returns it on its own line, `VENUE: Osteria Francescana`,
with `VENUE: -` when the page names no place and never a name guessed from the wines. So all three
readers agree, and a menu keeps its name whichever one happened to read it.

### 1.2.25: the restaurant's name, and picks that survive an upgrade

**The page's letterhead was being thrown away.** The reader was told to skip the restaurant's name
with the section headings, so every menu was titled by its date and had to be named by hand. The
scan now returns the place in "venue" — copied as printed, empty when the page names nowhere, never
guessed from the wines — and the phone's own offline reader takes the line above the first section
heading as the same answer. A kept menu takes it as its name, "Wine menu · 26 Nov 2026" remains the
fallback for a page that names nowhere, and a menu already kept under a date picks up the name the
next time the same page is read.

**"1 picked that night" was missing on menus recorded before 1.2.24.** The count only knew what the
new pick record held, which is empty for anything ordered or judged under an earlier build. It now
reads three signals — wines marked as picked, wines judged on the menu itself, and wines that exist
in the cellar under the menu's own id, which is what order and keep created — and a verdict given
from a reopened menu is written as a pick from now on. The answer's three suggestions are still not
one of the signals.

### 1.2.24: what *you* picked, and a better name for the menus

**"3 picked that night" counted the answer's suggestions, not the evening.** The three bottles the
advice names are what it would order, and a card that counted them claimed three glasses for a
table that had one. A menu now records what you actually picked — the wine you order off the ranked
cards, and any wine on the menu you later give a verdict to, since an answer about a wine is the
strongest sign there is that it was poured — and the card counts only those. Menus kept before this
have no recorded pick and simply show no line rather than an untrue one. On a reopened menu, **Picked**
is now yours and the advice's place is its own **#1** badge, so the two claims cannot be confused.

**Saved lists is now Saved wine menus** — on the Cellar card, on the screen's title, in the empty
state and in the line the scan screen prints after a menu is kept. "List" was doing double duty for
the shopping list of bottles and the restaurant's menu, and only one of them is what this is.

### 1.2.23: the minus button is a drink too, and the record can be read

**A bottle taken out with the minus button left no trace.** Drank one wrote the night; the plain
minus beside the bottle count did not, so "what did I drink last week" could miss a bottle that was
opened the other way. Both now write to the log.

**They are not the same claim, so they are not written the same way.** Drank one carries the verdict
and note from the sheet; the minus button records a count adjustment with no verdict, because none
was asked and inventing one would put words in the user's mouth. The history says so in as many
words ("count adjusted"), and the chat prompt tells the model not to attribute a verdict to those
lines.

**And the record has a page of its own.** Cellar gains a **Drinks** card above the search, opening
the history: nights newest first with today/yesterday/date headers, each bottle with its type and
region, its verdict or the count marker, its note and the time. A summary line counts the last 7
days, the last 30 and everything on record. Asking the model is now one way to read the log rather
than the only way.

### 1.2.22: what you drank is on record

**Ask could not say what you drank because nothing recorded it.** The screenshot answer was honest:
"I do not have a record of what you opened tonight." **Drank one** only took a bottle off the shelf,
so a question about yesterday, or last week, had nothing to answer from.

**Every drink is now written down.** The tap that removes the bottle records the night — the wine,
the day, and the verdict and line given in the sheet; **Skip** records it too, because a drink with
no verdict is still a drink. **Undo** removes the entry with the count, since an undone tap never
happened. The record lives in `drinks.json` beside the cellar.

**And Ask reads it.** The chat prompt now carries a drinks section with each recent night named the
way the question will name it — "today", "yesterday", "N days ago" worked out in code rather than
left to the model reading a timestamp — plus how many bottles in the last 7 and 30 days and which of
them were loved or not for them. The rules say plainly that a line there is a bottle that really
left the cellar, that it is never an excuse to say there is no record, and that a day with no line
is said to have none rather than filled in. The bottle's page also says when it was last drunk.

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

