# Haq — Project Context

## What this is
Haq is a voice-first, offline-capable, multilingual entitlement 
navigator for marginalised Indian citizens. It tells citizens 
which government welfare schemes they qualify for, how much they 
are owed, what documents they need, and how to file a grievance 
if underpaid — with no internet connection required.

Tagline: Your rights. Your language. No middleman.

## Non-negotiable constraints
- Gemma inference, TTS, and RAG run fully on-device with no network.
- STT: en and hi work offline via AiAi. All other Indian languages
  require WiFi for STT only — everything else remains on-device.
- No Firebase. No remote database. No cloud inference of any kind.
- If a suggested solution requires internet for Gemma, TTS, or RAG, it is wrong.

## Tech stack
- Language: Kotlin + Jetpack Compose
- Model: Gemma 4 E2B via LiteRT-LM (on-device, offline)
- STT: Three-tier language-aware routing:
  - AiAi (`com.google.android.as`): en-IN (offline, pre-installed) and
    hi-IN (offline, downloads silently on first WiFi launch via `triggerModelDownload`)
  - Whisper-tiny ONNX (via ONNX Runtime Android): te, ml, kn, ta, bn, gu, mr, ne —
    fully offline, bypasses SpeechRecognizer entirely; uses AudioRecorder + VAD
  - GoogleTTSRecognitionService: or, as only (Odia/Assamese — not in Whisper's 99 languages;
    WiFi required for these two)
  - Onboarding always passes null language tag → AiAi auto-detect, fully offline
  - WiFi prompt shown in onboarding (`NoWifi` step) and main app (AlertDialog)
    only for or/as; all other languages are offline-capable
- TTS: Android TTS API (Google TTS engine preferred explicitly)
- Storage: SQLite via Room
- Vector search: sqlite-vec
- Embeddings: paraphrase-multilingual-MiniLM-L12-v2
  (pre-computed on laptop, shipped inside APK as asset)
- OCR: ML Kit on-device
- Identity: Android BiometricPrompt (face ID) + 
  voice-spoken 4-digit PIN fallback

## Target devices
Mid-range Android — Snapdragon 6/7 series, Android 10+
Minimum RAM: 4GB

## Languages supported
All 12 at launch: Hindi (hi), Telugu (te), Malayalam (ml), Kannada (kn),
Tamil (ta), Bengali (bn), Gujarati (gu), Marathi (mr), Odia (or),
Assamese (as), Nepali (ne), English (en).
Offline STT: en, hi. WiFi STT: all others.
Architecture must be language-agnostic — adding a new language 
should require no code changes, only asset additions.

## Database schema

### schemes
- id TEXT PRIMARY KEY
- name_en, name_hi, name_te, name_ta TEXT
- ministry TEXT
- category TEXT
- benefit_type TEXT
- benefit_amount_formula TEXT  -- computable string, not static number
- helpline TEXT
- portal_url TEXT
- rag_chunk TEXT              -- prose briefing note for Gemma, <150 words
- embedding BLOB             -- pre-computed vector, shipped with APK
- last_verified DATE
- source_url TEXT

### eligibility_rules
- id INTEGER PRIMARY KEY
- scheme_id TEXT FK → schemes.id
- field TEXT                 -- e.g. "caste_category", "income", "land_acres"
- operator TEXT              -- "eq", "in", "lte", "gte"
- value TEXT
- logic_group TEXT           -- "AND" / "OR" grouping

### documents_required
- id INTEGER PRIMARY KEY
- scheme_id TEXT FK → schemes.id
- name_en, name_hi, name_te, name_ta TEXT
- is_mandatory BOOLEAN
- difficulty_rank INTEGER    -- 1 (Aadhaar) to 5 (caste certificate)
- where_to_get TEXT

### user_profile
- id INTEGER PRIMARY KEY
- name TEXT
- state TEXT
- district TEXT
- caste_category TEXT        -- "SC", "ST", "OBC", "General"
- annual_income INTEGER
- land_acres REAL
- occupation TEXT
- family_size INTEGER
- has_bpl_card BOOLEAN
- has_aadhaar BOOLEAN
- preferred_language TEXT    -- "hi", "te", "ta", etc.
- created_at DATETIME

### user_interactions
- id INTEGER PRIMARY KEY
- user_id INTEGER FK → user_profile.id
- query_text TEXT
- query_language TEXT
- schemes_surfaced TEXT      -- comma-separated scheme IDs
- response_type TEXT         -- "eligibility", "documents", "grievance"
- timestamp DATETIME

### deadlines
- id INTEGER PRIMARY KEY
- scheme_id TEXT FK → schemes.id
- trigger_event TEXT         -- e.g. "crop_loss", "fixed_date"
- hours_from_event INTEGER   -- e.g. 72 for PMFBY
- fixed_date DATE
- recurrence TEXT
- alert_message_hi TEXT
- alert_message_te TEXT
- alert_message_ta TEXT

## Retrieval architecture
1. Embed user query using MiniLM (on-device ONNX)
2. Cosine similarity search against scheme embeddings (sqlite-vec)
3. Return top 8 candidates
4. Filter candidates against user_profile via eligibility_rules SQL
5. Pass top 3-4 matching schemes as RAG context to Gemma
6. Never pass all schemes to Gemma

## Knowledge base
Three SQLite databases shipped in assets:
- schemes.db — 4,545 government welfare schemes from myscheme.gov.in
- law.db — 780 acts and legal provisions
- offices.db — 862 government offices with contact details
All three are preloaded by HaqViewModel on app start.

## Response structure
Every Gemma response must follow this structure:
1. WHAT — identify the scheme and the problem
2. WHY — explain why the user qualifies
3. AMOUNT — state the specific rupee amount owed
4. DOCUMENTS — list in order of difficulty (easiest first)
5. ACTION — next step the user must take
6. HELPLINE — phone number to call

## Inference abstraction
Wrap all model calls behind an InferenceEngine interface.
The hackathon implementation is LiteRTEngine using the bundled
.litertlm file. Post-hackathon this swaps to ML Kit GenAI
Prompt API / AICore without touching any other code.
Never call LiteRT APIs directly from ViewModel or UI layer.

## Current build stage
WEEK 4 IN PROGRESS — Whisper ONNX offline STT for 8 Indian languages:
- AiAi on-device STT: en-IN (pre-installed), hi-IN (downloads on first WiFi launch)
- Whisper-tiny ONNX: te, ml, kn, ta, bn, gu, mr, ne — fully offline, no SpeechRecognizer
- GoogleTTSRecognitionService: network path for or, as only (not in Whisper's 99 languages)
- WiFi-required prompt: NoWifi step in onboarding + AlertDialog in main app (or/as only)
- Onboarding: tap-to-speak complete, language-aware TTS voice selection
- PreparingVoices gates on Gemma model ready + TTS voice testSpeak passing
- RAG pipeline working end to end with all three knowledge bases

## Whisper ONNX model delivery
Files are downloaded lazily on first use of a Whisper language (te/ml/kn/ta/bn/gu/mr/ne).
Download is triggered automatically during onboarding PreparingVoices and gated — the user
cannot advance to Introduction until both Whisper files and TTS voices are ready.

If models are missing in the main app (fresh install, data cleared), tapping the mic
triggers an inline download and shows progress in the response text area. User taps mic
again once complete.

Source: onnx-community/whisper-tiny on HuggingFace (official ONNX community export)
  https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/encoder_model.onnx
  https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/decoder_model_merged.onnx
  https://huggingface.co/onnx-community/whisper-tiny/resolve/main/tokenizer.json

Sizes: encoder_model.onnx ~31 MB, decoder_model_merged.onnx ~113 MB, tokenizer.json <1 MB
Total: ~145 MB, downloaded once, stored in filesDir/whisper/, survives app updates

## Known pending items
- PreparingVoices does not detect WiFi re-enable while polling — user must
  kill and relaunch if they enable WiFi while stuck on that screen (or/as only)
- KV cache tensor names are discovered dynamically — log Decoder inputs/outputs
  on first launch to verify name conventions match the onnx-community/whisper-tiny export

## Development Principles

- **minSdk 29+ only.** Never add compatibility shims or version checks for API levels below 29.
- **No OEM-specific code.** Never write code that targets Samsung, Xiaomi, OnePlus, or any other OEM's custom APIs or intents. All OEMs must be treated identically.
- **Documented APIs only.** Never use reflection, internal Android APIs, or undocumented system intents. If an API is not in the Android SDK javadoc, it is off-limits.
- **SpeechRecognizer must run on the main Looper.** Always use `Handler(Looper.getMainLooper()).post {}` to create and call `startListening()`. `withContext(Dispatchers.Main)` is insufficient — SpeechRecognizer's internal ServiceConnection binds to the thread Looper, not the Kotlin dispatcher. `destroy()` must also be posted to the main Looper from any callback thread.
- **STT service routing is language-aware.** `preferredRecognitionService(context, bcp47)` selects AiAi only when `bcp47 == null` (onboarding auto-detect) or `bcp47 in AIAI_CAPABLE` (`{"en-IN", "hi-IN"}`). All other languages fall through to `googlequicksearchbox` (P2) or `GoogleTTSRecognitionService` (P3) with `EXTRA_PREFER_OFFLINE = false`. Never set `EXTRA_PREFER_OFFLINE = true` for AiAi — it returns `ERROR_RECOGNIZER_BUSY (12)` immediately.
- **AiAi is on-device by design; do not set `EXTRA_PREFER_OFFLINE`.** AiAi (`com.google.android.as`) handles en-IN offline natively. hi-IN is in its `supportedOnDeviceLanguages` list and is downloaded via `triggerModelDownload()` during `PreparingVoices`. AiAi's downloaded models live in AiAi's own app storage — they survive Haq reinstalls.
- **WiFi check before STT for network-required languages.** `STTManager.requiresNetwork(langCode)` returns true for any language outside `AIAI_CAPABLE`. `STTManager.isNetworkAvailable(context)` uses `NetworkCapabilities.NET_CAPABILITY_INTERNET` (API 29+). `OnboardingViewModel.selectLanguage()` checks both and routes to `OnboardingStep.NoWifi` if offline. `HaqViewModel.onMicButtonPressed()` checks both and sets `noWifiForMic = true` if offline, which surfaces an `AlertDialog`.
- **Prefer Google TTS and STT engines explicitly.** On devices with OEM TTS/STT engines (Samsung, Xiaomi, etc.), always prefer Google TTS (`com.google.android.tts`) and Google STT (`com.google.android.googlequicksearchbox`) by initialising with explicit engine/component names. Fall back to system default only if Google engines are not installed.
- **Voice selection uses `findBestVoice()` priority:** P1 non-OEM offline non-stub exact locale, P2 non-OEM online non-stub exact locale, P3 any offline non-stub exact locale (Samsung fallback), P4 any non-stub exact locale, P5 any non-stub language-only, P6 stub/null last resort. **Stubs (names ending in `-language`) are excluded at every tier P1–P5.** Never call `setLanguage()` in `speak()` — always use `tts.voice = findBestVoice(...)`.
- **`LanguageSelect` is the first visible step — shown immediately on launch.** After the user picks a language, `selectLanguage()` checks connectivity (WiFi prompt if needed), then sets `_step = PreparingVoices` and calls `startSingleLanguageReadinessPolling(languageCode)`. That polling loop gates on BOTH `GemmaManager.isModelReady()` AND `TTSManager.testSpeak(languageCode)`.
- **`speak()` ERROR_OUTPUT (-4) recovery in onboarding: call `reinitialiseAndWait()` then `clearCachedVoice(lang)`.** After a -4 error the voice has disappeared from the engine's list. `speakOnboarding()` saves `stepOnEntry`, on -4 reinits once, then retries up to 4 times total.
- **`speakOnboarding()` must only change `_step` to `PreparingVoices` when called from `Introduction`, never from question steps.** If `speakOnboarding` changes step during a question, `ConversationOnboardingScreen`'s `LaunchedEffect(micActivationEvent)` re-fires and routes the transcript to the wrong handler.
- **`checkLanguageSupport()` is unreliable — always verify with `testSpeak()` during `PreparingVoices`.** Stub voices (names ending in `-language`) pass `checkLanguageSupport()` but produce `ERROR_OUTPUT (-4)`. `verifySingleVoiceSpeakable()` in `OnboardingViewModel` runs `testSpeak()` and retries up to 6 cycles (~30s) before the escape hatch fires.
- **Never call `ACTION_INSTALL_TTS_DATA`.** Google TTS downloads voice data silently when `speak()` is attempted. Completion is signalled by `ACTION_TTS_DATA_INSTALLED`; `MainActivity` receives it and calls `OnboardingViewModel.onTtsDataInstalled()`.
- **`InstallingVoicePacks` is an edge case only**, shown if `checkLanguageSupport()` still returns false after `PreparingVoices` completes. The user taps Continue to proceed regardless.
- **`testSpeak()` uses near-silent volume (0.01f), not 0f.** At 0f some engines bypass synthesis and return `ERROR_OUTPUT` without triggering the background voice download. At 0.01f the engine runs the full synthesis path.
- **`GemmaManager.isModelReady(context)` accepts an optional context.** Call as `isModelReady(getApplication())` so the model-file check works before `GemmaManager.init()` is called.
- **xnnpack cache cleared on every engine creation.** `LiteRTEngine.init` deletes all `*.xnnpack_cache` files from `context.cacheDir` before constructing `Engine(config)`. This prevents `DYNAMIC_UPDATE_SLICE` crashes from stale cache.
- **`reinitialiseAndWait()` polls `tts?.voices?.size` after `onInit` fires**, waiting for the count to stabilise above 100. Samsung loads only ~14 stubs immediately after `onInit`; the full list arrives asynchronously. Poll every 300 ms, require 3 consecutive stable checks above 100, time out at 10 seconds.

## Do not suggest
- Any cloud-based model inference
- Firebase or any remote database
- Python (app is Kotlin only)
- Separate translation APIs (Gemma handles multilingual natively)
- Loading the model from external storage or downloading on first run
- The .task format — the correct format is .litertlm
- Whisper ONNX for the current build — it is stashed on feat/whisper-offline-stt

## Model
- Model: Gemma 4 E2B via LiteRT-LM (on-device, offline)
- Model file: `gemma-4-E2B-it.litertlm` stored in `context.filesDir` at runtime
- Format: .litertlm (LiteRT-LM framework, not legacy .task format)
- The model is NOT bundled in the APK (2.4 GB is too large)

## Model delivery
- On first launch, `ModelDownloadManager` checks if the model exists in `filesDir`
- If missing, it requires WiFi and downloads from HuggingFace with progress reporting
- Download URL: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`
- Uses an atomic temp-file rename to avoid partial writes
- INTERNET permission exists only for this model download and network STT
- After download completes, all inference runs fully offline
- Do NOT suggest bundling the model in assets or requiring adb push
