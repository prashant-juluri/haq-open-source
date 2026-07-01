# Haq — Your Rights. Your Language. No Middleman.

### An offline-first, voice-first welfare entitlement navigator for marginalised Indian citizens, powered by Gemma 4 on-device.

---

## The Problem

India has 4,500+ active government welfare schemes — PM-KISAN, PMFBY, PMAY, NREGA, caste scholarships, widow pensions, and hundreds more. A farmer in rural Telangana who qualifies for ₹6,000 in crop insurance and ₹12,000 in direct benefit transfers almost certainly doesn't know it. The information exists. The entitlement exists. The access doesn't.

The barriers are compounding: schemes are announced in English, explained in bureaucratic Hindi, gated behind portals that assume literacy and reliable internet. The people who need these schemes the most are exactly the people least equipped to find them.

Haq removes every one of those barriers. It works in your language, it works offline, and it tells you exactly what you're owed and what to do next.

---

## What Haq Does

Speak to Haq in your language. It listens, understands your situation, and tells you:

- **Which schemes you qualify for** — matched against your profile (caste, income, occupation, state, land holdings)
- **How much you're owed** — the specific rupee amount, not a vague description
- **What documents you need** — ordered from easiest to hardest to obtain
- **What to do next** — the exact action step and helpline number

Every step runs on-device. No cloud. No account. No internet after first setup.

---

## Architecture

### The Stack

| Layer | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose |
| LLM | Gemma 4 E2B via LiteRT-LM (on-device) |
| STT — English/Hindi | Google AiAi (on-device, pre-installed) |
| STT — 8 Indian languages | Gemma 4 E2B built-in audio encoder (on-device, no separate download) |
| TTS | Android TTS API (Google engine, explicit) |
| Knowledge base | SQLite — 4,545 schemes, 780 laws, 862 offices |
| Vector search | sqlite-vec (cosine similarity, on-device) |
| Embeddings | paraphrase-multilingual-MiniLM-L12-v2 (pre-computed, shipped in APK) |
| Target device | Snapdragon 6/7 series, Android 10+, 4 GB RAM |

### Knowledge Base

Three SQLite databases ship inside the APK:

- **schemes.db** — 4,545 welfare schemes scraped from myscheme.gov.in, each with eligibility rules, benefit amounts, required documents, and a `rag_chunk` prose summary tuned for Gemma's context window
- **law.db** — 780 acts and legal provisions
- **offices.db** — 862 government offices with contact details

Schemes were scraped using a custom Playwright + aiohttp pipeline that captured the internal myscheme.gov.in API, extracted per-scheme eligibility rules into a normalised `eligibility_rules` table, and pre-computed MiniLM embeddings for every scheme.

**law.db** was built by scraping [indiacode.nic.in](https://indiacode.nic.in) — the Government of India's official Central Acts repository. Playwright paginated the browse-by-short-title listing (100 acts per page), visited each act's item page, read the `citation_pdf_url` meta tag to locate the English PDF, downloaded it via aiohttp, extracted plain text with pdfminer.six, and split it into sections using the standard Indian Acts heading pattern (`"3. Definitions.—"`). Each section became a `rag_chunk` of up to 120 words formatted as `"Section 3 of The Minimum Wages Act, 1948 — Definitions: …"`. indiacode.nic.in runs Akamai bot detection; this required using the system Chromium binary (not the bundled Playwright one) and injecting anti-detection JS to pass the TLS fingerprint and `navigator.webdriver` checks.

**offices.db** was built by scraping every State Legal Services Authority (SLSA) website hosted at `{state}.nalsa.gov.in` — the National Legal Services Authority's network of state portals. Each portal publishes a district-level DLSA (District Legal Services Authority) table with contact numbers. The scraper tried a set of candidate URL paths per state (`/district-legal-services-authority/`, `/dlsa/`, `/contact-us/`, etc.), sniffed the table structure to identify district and phone columns by header keywords, and extracted a contact record per district. Each record becomes a `rag_chunk` that explains what a DLSA does and includes the helpline number — so Gemma can tell a user exactly who to call for free legal aid in their district.

### Retrieval Pipeline

1. User speaks a query → STT transcribes → MiniLM embeds on-device
2. `sqlite-vec` cosine search returns top 8 candidate schemes
3. Eligibility rules SQL filters candidates against stored user profile (caste, income, land, state, occupation)
4. Top 3–4 matching `rag_chunk` entries are prepended to the Gemma prompt
5. Gemma 4 generates a structured response: WHAT / WHY / AMOUNT / DOCUMENTS / ACTION / HELPLINE

This keeps the Gemma context lean — never more than ~600 tokens of scheme context — while ensuring the answer is grounded in real data rather than hallucinated.

### STT Architecture — Three-Tier Routing

```
Language selected
      │
      ├─ en-IN / hi-IN ──► AiAi (com.google.android.as) — fully offline, pre-installed
      │
      ├─ te/ml/kn/ta/bn/gu/mr/ne ──► Gemma 4 E2B audio encoder — fully offline, no separate download
      │
      └─ or/as ──► GoogleTTSRecognitionService — network required
```

For te/ml/kn/ta/bn/gu/mr/ne, audio routes directly through the same Gemma 4 E2B model already loaded for inference — raw `AudioRecord` → 16 kHz PCM → WAV container → `Content.AudioBytes` → Gemma audio encoder → streamed transcript tokens. This eliminates the separate Whisper model download and delivers significantly better transcription quality on Indic languages, particularly Dravidian scripts.

---

## How Gemma 4 Is Used

Gemma 4 E2B (2.4 GB, `.litertlm` format) runs via LiteRT-LM on the device CPU. It performs three distinct roles:

**1. Structured onboarding extraction**
During onboarding, the user speaks their profile in their own language. Gemma extracts structured fields — occupation, caste category, annual income, land holdings, family size — from free-form speech. The prompt forces English output for occupation so that keyword matching against the scheme database works consistently regardless of input language.

**2. Eligibility reasoning**
Given the top filtered schemes and the user's profile, Gemma explains *why* the user qualifies. This is not a lookup — it's natural-language reasoning grounded in the RAG context. A farmer who qualifies for PMFBY because of crop loss gets an explanation in their language, not a form letter.

**3. Multilingual response generation**
The entire response — scheme name, amounts, documents, action steps, helpline — is generated in the user's preferred language. Gemma handles 12 Indian languages natively. No separate translation API is used.

The InferenceEngine interface abstracts all LiteRT calls so the ViewModel and UI layers have no direct model dependency. Post-hackathon, this swaps to ML Kit GenAI Prompt API / AICore without touching any other code.

A deliberate design choice: Gemma is used for text-in / text-out RAG rather than its native voice input mode. Injecting structured scheme context — eligibility rules, benefit amounts, document lists — into a text prompt is straightforward and deterministic. Doing the same via voice prompt injection would require encoding structured data as spoken language, making it far harder to control what the model sees and to iterate on retrieval quality. Text prompting keeps the RAG context explicit and auditable.

Gemma 4 E2B also serves as the STT engine for 8 Indian languages via its built-in audio encoder. A separate conversation (isolated KV cache, transcription-only system prompt) handles audio → transcript, which then feeds into the standard RAG + inference pipeline. This means the 2.4 GB Gemma model handles both transcription and response generation — no second model required.

---

## Challenges and How We Solved Them

### 1. Indic language STT quality

**Problem:** Whisper-small int8 produced unreliable transcriptions for Dravidian languages — Telugu would collapse into hallucination loops ("నినిని"), and transcription latency was ~4 seconds for a 10-second chunk on Snapdragon 6/7 with no hardware fp16 acceleration.

**Solution:** Route te/ml/kn/ta/bn/gu/mr/ne directly through Gemma 4 E2B's built-in audio encoder via `Content.AudioBytes`. Audio is recorded at 16 kHz mono PCM, wrapped in a WAV container, and sent on a dedicated conversation (isolated KV cache, transcription-only system prompt) so it doesn't pollute the welfare assistant's context. Transcription quality is substantially better across all tested Indic languages, and eliminating Whisper removes the ~250 MB one-time model download entirely.

### 2. Android SQLite has no FTS5

**Problem:** FTS5 with `bm25()` relevance ranking — the standard Android full-text search approach — is not compiled into the system SQLite on Android. FTS4 is available but has no ranking function.

**Solution:** Scored SQL `LIKE` queries across structured columns with hand-tuned weights: state match +5, Central scheme +2, caste category +3, occupation keyword +2, query keyword +1. State weight deliberately exceeds caste weight, otherwise generic Central OBC schemes outrank state-specific ones that are often worth more.

### 4. Voice synthesis reliability across OEM skins

**Problem:** Samsung and Xiaomi devices load ~14 TTS stub voices immediately after `onInit`, with the full voice list arriving asynchronously. Stub voices (names ending in `-language`) pass `checkLanguageSupport()` but produce `ERROR_OUTPUT (-4)` on `speak()`.

**Solution:** `reinitialiseAndWait()` polls `tts.voices.size` after `onInit`, requiring 3 consecutive stable readings above 100 before proceeding. `testSpeak()` runs at near-silent volume (0.01f — not 0f, which bypasses synthesis on some engines) to trigger background voice downloads before the user reaches the first spoken onboarding question.

---

## Languages Supported

| Language | STT | TTS |
|---|---|---|
| English | AiAi offline | Google TTS |
| Hindi | AiAi offline (downloads silently on first WiFi launch) | Google TTS |
| Telugu | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Malayalam | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Kannada | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Tamil | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Bengali | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Gujarati | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Marathi | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Nepali | Gemma 4 E2B audio encoder (offline, no separate download) | Google TTS |
| Odia | Network STT | Google TTS |
| Assamese | Network STT | Google TTS |

---

## Known Limitations

We'd rather be honest about these than have a judge discover them.

**Telugu onboarding is non-deterministic** — Onboarding uses AiAi in auto-detect mode (null language tag). On our test device, this silently picks up the Telugu offline speech pack that Google Play Services had installed in the background. On a device where Telugu has never been set as a system language, this pack may not be present and onboarding STT will fail. Main-app Telugu STT (Gemma audio encoder) is unaffected — this is an onboarding-only issue.

**Odia and Assamese always require network** — These two languages are not in Gemma 4 E2B's documented audio language set and have no AiAi offline pack. STT for or/as routes to Google's network recogniser. Everything else (Gemma inference, TTS, RAG) still runs offline.

**First-launch download burden** — Gemma 4 E2B is 2.4 GB and requires WiFi. On a 10 Mbps connection that is roughly 30 minutes of download before the app is fully operational. This is a hard constraint of on-device model delivery. Unlike before, no second model download is needed — Gemma handles both STT and response generation.

---

## Privacy

No data leaves the device. Ever. There is no backend, no analytics, no crash reporting, no account system. The user's profile, queries, and entitlement data are stored locally in Room/SQLite. The only network calls are one-time model downloads (Gemma: ~2.4 GB, Whisper: ~250 MB) over WiFi.

---

## Building and Running

### Prerequisites
- Android Studio Hedgehog or later
- Android device running Android 10+ with 4 GB+ RAM (Snapdragon 6/7 recommended)
- ~3 GB free storage on device

### First launch
1. Clone the repo and open in Android Studio
2. Build and install from `main`
3. On first launch, the app prompts for WiFi to download Gemma 4 E2B (~2.4 GB)
4. Select English or Hindi — these work offline immediately via AiAi
5. To use Telugu/Malayalam/Kannada/Tamil/Bengali/Gujarati/Marathi/Nepali — select the language; STT works immediately via Gemma's built-in audio encoder, no additional download required

---

## Why These Choices Are Right

**Gemma 4 E2B over a smaller model:** E2B is the minimum size that handles code-switching (users mixing Hindi and English mid-sentence), extracts structured fields reliably from noisy speech transcripts, and generates grammatically correct responses in Dravidian scripts. Smaller models collapse on Tamil and Telugu morphology.

**Gemma 4 E2B audio encoder over Whisper:** Gemma 4's built-in audio encoder delivers substantially better transcription quality on Indic languages than Whisper-small int8, requires no separate model download, and runs within the same model already loaded for inference. The single 2.4 GB download replaces what was previously a 2.4 GB + 250 MB two-model setup.

**On-device over cloud:** The target user has a prepaid SIM with 1–2 GB/month data. A cloud round-trip per query is not a product for them — it's a tax. Every design decision flows from this constraint.

**SQLite over a vector database:** sqlite-vec runs inside the existing SQLite process. No separate daemon, no JNI surface, no memory overhead. Pre-computed embeddings shipped in the APK means zero embedding latency at query time.

---

*Haq is built for the people who built India and were never given the tools to claim what they're owed.*
