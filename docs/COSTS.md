# What a scan costs

Measured on 26 Nov 2026 against the paid Gemini key, not guessed. The point is to know what one
label, one wine list and one question cost, so a month of use can be held under a dollar.

## How it was measured

The app's real prompt strings were dumped from a temporary JVM test into `build/prompts/`, then
sent once each to `generativelanguage.googleapis.com` and the `usageMetadata` counts read back.
Photos were measured the same way, at the size the app uploads them.

- **Text**: 12,395 characters of prompt came back as 3,077 tokens, and 2,204 characters as 552 —
  so **about 4.0 characters per token** for this prompt style. The old `chars / 4` estimate holds.
- **Photos**: a 1080×1400 label and an 872×1600 wine list both billed **about 1,100 tokens**.
  Gemini counts 258 tokens per 768px tile, so a 1600×1200 photo is 6 tiles ≈ **1,550 tokens**.
  The app's 1600px cap is what keeps this in that range; a 4000px camera photo would bill ~4× more.

Prompt sizes as built by `Prompts.kt` (brief at 178 chars in the test; a real file's brief adds
~700 characters, about 170 tokens):

| Prompt | Chars | Tokens |
| --- | --- | --- |
| `Prompts.scan` (page read) | 12,395 | 3,077 |
| `Prompts.finePrint` | 1,148 | 287 |
| `Prompts.labelRecheck` | 1,473 | 368 |
| `Prompts.listAdvice` (one-line list) | 2,204 | 552 |
| `Prompts.bottleInsight` | 2,703 | 676 |
| `Prompts.brief` (model's taste file) | 178–860 | 45–215 |

## Prices, November 2026, per million tokens

| Model | In | Out | Note |
| --- | --- | --- | --- |
| `gemini-2.5-flash-lite` | $0.10 | $0.40 | deprecated; routes to 3.5 Flash-Lite on 1 Dec 2026 |
| `gemini-3.1-flash-lite` | $0.25 | $1.50 | **the app's default** |
| `gemini-3.5-flash-lite` | $0.30 | $2.50 | what 2.5 keys will bill from December |
| `gemini-2.5-flash` | $0.30 | $2.50 | deprecated, same redirect |

Thinking tokens bill as output. Cached input is $0.025/M on 3.1 Flash-Lite, batch is half price;
the app uses neither, because both need request shapes it doesn't send.

## Per operation, at `gemini-3.1-flash-lite`

Taken from a real scan of `label.png` on the emulator, whose trace shows the three calls:

```
on-device OCR      ML Kit    0 chars       0 ms   (failed on this emulator)
read the page      Gemini     283 chars  2963 ms
small print        Gemini     545 chars  3672 ms
structured read    Gemini   1,423 chars  4211 ms
```

| Operation | Prompt tokens | Output tokens | Cost |
| --- | --- | --- | --- |
| One label, no recheck | ~9,700 | ~560 | **$0.0033** |
| One label with the recheck | ~14,100 | ~1,900 | **$0.0064** |
| One wine list (26 wines, page read + advice) | ~5,900 | ~2,900 | **$0.0059** |
| One bottle insight | ~700 | ~1,400 (incl. thinking) | **$0.0023** |
| One chat question | ~4,000 | ~400 | **$0.0016** |

## What a dollar buys

| Month | Cost |
| --- | --- |
| 100 labels + 50 lists + 50 insights + 50 questions | **$0.83** |
| 300 labels, nothing else | **$0.99** |
| 150 labels + 100 lists | $1.09 — over |

So the dollar holds a normal month (a couple of scans a day, a list at a restaurant most weeks,
some chat) with room to spare, and it fails on batch-testing: a 200-photo shelf run of labels is
about $0.66 on its own.

## Levers, cheapest first

1. **Stay on 3.1 Flash-Lite.** 2.5 Flash-Lite is 2.5× cheaper today but is deprecated on
   1 December 2026 and will bill as 3.5 Flash-Lite ($0.30/$2.50), which is more expensive than
   3.1. Moving to 3.1 now avoids a silent 20% price rise in December.
2. **Requests per minute.** The 10/min default is a free-tier guard. A paid key allows far more;
   raising it in Settings is what makes batch testing fast.
3. **Photo size.** 1600px is the sweet spot: ~1,550 tokens and enough resolution for small print.
   Going to 1200px saves under $0.0001 a scan.
4. **Don't ask for the recheck** unless a read disagrees with itself; it nearly doubles a scan.
5. **Lists dominate.** One wine list costs as much as two labels because the advice call thinks
   through 26 wines. That is the right place to spend: one list replaces twenty label scans.

## The cap

`data/SpendMeter.kt` turns the table above into an actual budget. Every successful answer carries
`usage.prompt_tokens` and `usage.completion_tokens`; `AiClient` reads them and prices them at the
answering model's rate, so the number is what was billed, not what was estimated. Money is kept in
nano-dollars — $0.25 per million tokens is exactly 250 nano-dollars a token — in per-month keys, so
correctness does not drift as calls accumulate and a new month starts from zero on its own.

- **Default limit**: $1.00, editable in Settings → Spend this month. `0` means no limit.
- **80%**: the card turns amber and states what is left. Nothing else changes yet.
- **At the limit**: `AiClient.complete` refuses before building a request, so nothing is sent and
  nothing is billed. The message names the spend, the limit and the first of next month.
- **Free models**: tokens are counted, cost is zero — the free tier's usage stays visible without
  pretending it is money.
- **Unpriced**: a model outside the table is billed as Flash-Lite, the app's default class; a
  provider that returns no `usage` is not guessed at at all.

One thing the meter does not cover: Groq with a paid account. Its free tier is what the app
documents, and counting it as free keeps the total honest for the default configuration; a paid Groq
bill would be invisible here. Gemini is where the cap does its work.
