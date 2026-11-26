# AGENTS.md

Notes for coding agents working on this repository.

## What this is

Wine Verdict: an Android wine-cellar notebook with an AI sommelier, in Kotlin + Jetpack Compose.
Scan a label to add a bottle, photograph a shop shelf for a buying recommendation, photograph a
restaurant wine list for an ordering recommendation, and chat about the cellar. No account and no
backend of ours: the cellar, notes and `mytaste.md` live in app-private storage, and the only
network calls go to the AI provider whose key the user stored on the device (Gemini or Groq).

## Build and test

JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). `local.properties` needs
`sdk.dir=/path/to/Android/sdk`.

```
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleSideload     # minified, debug-signed, for sharing
./gradlew :app:testDebugUnitTest    # JVM tests — run before saying a change works
./gradlew :app:testDebugUnitTest -Plive  # also the live Groq instruments; spends quota
./gradlew :app:installDebug         # needs a connected device
```

CI has no emulator and a dev machine often has none: JVM tests are the only automated check. Say
plainly what was not run on hardware instead of implying a UI flow was exercised.

The default test task excludes `Live*Test`: those are instruments against a real provider, they
assert nothing, and each call costs free-tier quota. They run with `-Plive` and need a Groq key at
`~/.freebuff/groq.txt` (or `-DgroqKeyFile=…`); without one they skip.

## Code map

```
app/src/main/java/com/wineverdict/app/
├─ MainActivity.kt        bottom nav + navigation graph
├─ AppGraph.kt            service locator, scan→editor handoff
├─ model/Models.kt        Wine, CellarData, drinking-window logic
├─ data/                  persistence, settings, mytaste.md, palate rules,
│                         quick start, backup/CSV import, scan queue,
│                         ListPicks (ranking), ListIdentity (card binding),
│                         ListService (unit/reason/strip rules), BuyAdvice
├─ ai/                    providers, HTTP client, rate limiter, prompts,
│                         tolerant parsing of model replies
├─ ui/                    Cellar, Detail, Editor, Scan, Chat, Taste,
│                         Quick start, Advanced editor, Settings
└─ util/ImageUtils.kt     downscale + EXIF rotation → base64 JPEG
```

## Rules

- Small, focused changes. One concern per commit, and the message explains *why*.
- Build and run `:app:testDebugUnitTest` before reporting done, and report the actual result —
  including checks that could not run.
- Never commit API keys or signing material. CI fails on credential-shaped strings and on
  `.env`/`.jks`/`.keystore` files; keys live only in the device's private storage.
- A behaviour change updates `README.md` and `CHANGELOG.md`. A release bumps `versionCode` and
  `versionName` together, `versionName` equals the tag, and `.github/workflows/release.yml`
  refuses a mismatch and reads release notes from `CHANGELOG.md`.
- `ListIdentity.buildCard` is the only recommendation-card constructor and the shortlist carries
  row ids: do not reintroduce positional indexing across the bottle list.
- Rules live in code, not in prompt prose. A prompt is a hint; a validator is the requirement.
- Long design rationale goes in `docs/DESIGN.md`. The current redesign plan is
  `docs/INSIGHT_REDESIGN.md` (one step per chat).
