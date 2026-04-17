# Haq — Project Context

## What this is
Haq is a voice-first, offline-capable, multilingual entitlement 
navigator for marginalised Indian citizens. It tells citizens 
which government welfare schemes they qualify for, how much they 
are owed, what documents they need, and how to file a grievance 
if underpaid — with no internet connection required.

Tagline: Your rights. Your language. No middleman.

## Non-negotiable constraints
- Everything runs on-device. No network calls for core functionality.
- The app must work perfectly in airplane mode at all times.
- No Firebase. No remote database. No cloud inference of any kind.
- If a suggested solution requires internet for core features, it is wrong.

## Tech stack
- Language: Kotlin + Jetpack Compose
- Model: Gemma 4 E4B via LiteRT (on-device, offline)
- STT: Audio recorded as WAV via AudioRecorder, passed directly to Gemma 4 E2B via InputData.Audio — no separate STT model required
- TTS: Android TTS API
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
Hindi (hi), Telugu (te), Malayalam (ml), Kannada (kn), English (en) at launch.
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
- preferred_language TEXT    -- "hi", "te", "ta"
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
6. Never pass all 40 schemes to Gemma

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
WEEK 2 COMPLETE — Full voice pipeline working end to end:
- SpeechRecognizer (online) → transcript
- Gemma 4 E2B → streaming text response to UI
- TTS speaks sentence by sentence as tokens arrive
- App returns to READY state after each response
- VAD tuned: 2s minimum, 2.5s silence cutoff

WEEK 3 IN PROGRESS
- Onboarding: tap-to-speak complete, voice pack install after onboarding
- STT: clean audio session, single recordAndTranscribeWithLanguage() path
- Pending: knowledge base integration from colleague

## Known pending items
- Hindi TTS voice data needs installing on device
  (Settings → TTS → Google TTS → Install voice data → Hindi)
- Language hardcoded to "hi" — will come from user profile
- EXTRA_PREFER_OFFLINE = false (online recognizer) —
  flip to true once offline Hindi model downloaded on device

## Development Principles

- **minSdk 29+ only.** Never add compatibility shims or version checks for API levels below 29.
- **No OEM-specific code.** Never write code that targets Samsung, Xiaomi, OnePlus, or any other OEM's custom APIs or intents. All OEMs must be treated identically.
- **Documented APIs only.** Never use reflection, internal Android APIs, or undocumented system intents. If an API is not in the Android SDK javadoc, it is off-limits.
- **SpeechRecognizer must run on the main Looper.** Always use `Handler(Looper.getMainLooper()).post {}` to create and call `startListening()`. `withContext(Dispatchers.Main)` is insufficient — SpeechRecognizer's internal ServiceConnection binds to the thread Looper, not the Kotlin dispatcher.
- **Prefer Google TTS and STT engines explicitly.** On devices with OEM TTS/STT engines (Samsung, Xiaomi, etc.), always prefer Google TTS (`com.google.android.tts`) and Google STT (`com.google.android.googlequicksearchbox`) by initialising with explicit engine/component names. Fall back to system default only if Google engines are not installed. This is not OEM-specific code — it is engine preference using documented Android APIs available since API 14.
- **Voice selection uses `findBestVoice()` priority:** P1 Google voice exact locale, P2 non-OEM exact locale, P3 any exact locale, P4 Google language only, P5 any language, P6 null (last resort device locale). Never call `setLanguage()` in `speak()` — always use `tts.voice = findBestVoice(...)`.
- **Voice pack installation targets Google TTS explicitly** via `TTSManager.installVoiceData()`. The check and install happen at language selection time (`selectLanguage()`), not at onboarding completion. After install and reinitialisation, proceed to Introduction — never block the user.

## Do not suggest
- Any cloud-based model inference
- Firebase or any remote database
- Python (app is Kotlin only)
- Solutions requiring internet for core functionality
- Separate translation APIs (Gemma handles multilingual natively)
- Loading the model from external storage or downloading on first run
- The .task format — the correct format is .litertlm

## Model
- Model: Gemma 4 E2B via LiteRT-LM (on-device, offline)
- Model file: `gemma-4-E2B-it.litertlm` stored in `context.filesDir` at runtime
- Format: .litertlm (LiteRT-LM framework, not legacy .task format)
- The model is NOT bundled in the APK (2.4 GB is too large)

## Model delivery
- On first launch, `ModelDownloadManager` checks if the model exists in `filesDir`
- If missing, it requires WiFi and downloads from S3 with progress reporting
- Download URL: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`
- Uses an atomic temp-file rename to avoid partial writes
- INTERNET permission is the ONE exception to the no-network rule; it exists only for this download
- After download completes, all inference runs fully offline
- Do NOT suggest bundling the model in assets or requiring adb push
