package com.haq.app

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
import com.haq.app.data.LawRepository
import com.haq.app.data.OfficeRepository
import com.haq.app.data.SchemeRepository
import com.haq.app.data.SessionManager
import com.haq.app.data.UserProfile
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
import com.haq.app.inference.GemmaManager
import com.haq.app.inference.ModelDownloadManager
import com.haq.app.stt.STTManager
import com.haq.app.tts.TTSManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HaqViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager(application)

    // ── Exposed state ─────────────────────────────────────────────────────────

    val downloadState: StateFlow<DownloadState> = downloadManager.downloadState

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _appState = MutableStateFlow(AppState.LOADING)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    // null = still checking, true = needs onboarding, false = ready to use
    private val _needsOnboarding = MutableStateFlow<Boolean?>(null)
    val needsOnboarding: StateFlow<Boolean?> = _needsOnboarding.asStateFlow()

    private val _activeProfileName = MutableStateFlow("")
    val activeProfileName: StateFlow<String> = _activeProfileName.asStateFlow()

    private val _activeLanguage = MutableStateFlow("en")
    val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()

    private val _noWifiForMic = MutableStateFlow(false)
    val noWifiForMic: StateFlow<Boolean> = _noWifiForMic.asStateFlow()

    fun dismissNoWifi() { _noWifiForMic.value = false }

    // Set when TTS voice is unavailable for the active language (e.g. offline, voice not installed).
    // Cleared at the start of each new query so the banner disappears on the next turn.
    private val _ttsUnavailable = MutableStateFlow(false)
    val ttsUnavailable: StateFlow<Boolean> = _ttsUnavailable.asStateFlow()

    fun getAllProfiles(): Flow<List<UserProfile>> = ProfileManager.getAllProfiles()

    // Active profile — updated on load, onboarding complete, and profile switch.
    // Used to prepend context to the user query and to select STT/TTS language.
    private var activeProfile: UserProfile? = null

    // Tracks the active generation job so it can be cancelled on profile switch or + tap.
    private var currentQueryJob: Job? = null

    // Secondary guard — LiteRT-LM does not support concurrent generateResponse() calls.
    private var isGenerating = false

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        startDownload()
        checkOnboardingState()

        // Pre-copy both asset DBs to filesDir while Gemma is still loading
        // so the first query has no copy latency (~325 ms on first install).
        viewModelScope.launch {
            SchemeRepository.preload(getApplication())
            LawRepository.preload(getApplication())
            OfficeRepository.preload(getApplication())
        }

        viewModelScope.launch {
            downloadState.collect { dl ->
                if (dl is DownloadState.Complete) {
                    GemmaManager.init(getApplication())
                    observeEngineState()
                }
            }
        }
    }

    private var engineObserving = false

    private fun observeEngineState() {
        if (engineObserving) return
        engineObserving = true
        viewModelScope.launch {
            GemmaManager.state.collect { state ->
                _engineState.value = state
                _appState.value = when (state) {
                    is EngineState.Loading -> AppState.LOADING
                    is EngineState.Ready   -> AppState.READY
                    is EngineState.Error   -> AppState.ERROR
                }
            }
        }
    }

    private fun checkOnboardingState() {
        viewModelScope.launch {
            val profileId = SessionManager.getActiveProfileId(getApplication())
            if (profileId == -1) {
                _needsOnboarding.value = true
                return@launch
            }
            val profile = ProfileManager.getActiveProfile(profileId)
            if (profile == null || !profile.isOnboarded) {
                _needsOnboarding.value = true
            } else {
                _activeProfileName.value = profile.name
                _activeLanguage.value = profile.preferredLanguage
                _needsOnboarding.value = false
            }
        }
    }

    fun setOnboardingComplete() {
        _needsOnboarding.value = false
        // reloadActiveProfile() is triggered by LaunchedEffect(onboardingStep) in HaqScreen
        // once OnboardingStep.Complete fires — no need to call it here.
    }

    fun switchProfile(profileId: Int) {
        SessionManager.setActiveProfileId(getApplication(), profileId)
        viewModelScope.launch {
            ProfileManager.updateLastActive(profileId)
        }
        reloadActiveProfile(getApplication())
    }

    fun reloadActiveProfile(context: Context) {
        Log.d("Haq/VM", "reloadActiveProfile() invoked")
        viewModelScope.launch {
            val profileId = SessionManager.getActiveProfileId(context)
            if (profileId == -1) {
                Log.w("Haq/VM", "reloadActiveProfile: no active profile ID found")
                return@launch
            }
            val profile = ProfileManager.getActiveProfile(profileId) ?: run {
                Log.w("Haq/VM", "reloadActiveProfile: profile $profileId not found in DB")
                return@launch
            }
            activeProfile = profile
            _activeProfileName.value = profile.name
            _activeLanguage.value = profile.preferredLanguage
            // Restore last conversation, or show zero-state tagline in the profile's language
            if (profile.lastResponse.isNotBlank()) {
                _responseText.value = profile.lastResponse
                Log.d("Haq/VM", "Restored last response for ${profile.name}")
            } else {
                _responseText.value = when (profile.preferredLanguage) {
                    "hi" -> "आपके अधिकार। आपकी भाषा। कोई बिचौलिया नहीं।\n\nमाइक दबाएं और अपना सवाल पूछें।"
                    "te" -> "మీ హక్కులు. మీ భాష. దళారీ అవసరం లేదు.\n\nమైక్ నొక్కి మీ ప్రశ్న అడగండి."
                    "ml" -> "നിങ്ങളുടെ അവകാശങ്ങൾ. നിങ്ങളുടെ ഭാഷ. ഇടനിലക്കാർ വേണ്ട.\n\nമൈക്ക് അമർത്തി ചോദ്യം ചോദിക്കൂ."
                    "kn" -> "ನಿಮ್ಮ ಹಕ್ಕುಗಳು. ನಿಮ್ಮ ಭಾಷೆ. ದಲ್ಲಾಳಿ ಬೇಡ.\n\nಮೈಕ್ ಒತ್ತಿ ನಿಮ್ಮ ಪ್ರಶ್ನೆ ಕೇಳಿ."
                    "ta" -> "உங்கள் உரிமைகள். உங்கள் மொழி. தரகர் தேவையில்லை.\n\nமைக் அழுத்தி உங்கள் கேள்வியை கேளுங்கள்."
                    "bn" -> "আপনার অধিকার। আপনার ভাষা। কোনো দালাল নয়।\n\nমাইক চাপুন এবং আপনার প্রশ্ন করুন।"
                    "gu" -> "તમારા અધિકારો. તમારી ભાષા. કોઈ દલાલ નહીં.\n\nમાઇક દબાવો અને તમારો પ્રશ્ન પૂછો."
                    "mr" -> "तुमचे हक्क. तुमची भाषा. कोणता दलाल नाही.\n\nमायक दाबा आणि तुमचा प्रश्न विचारा."
                    "or" -> "ଆପଣଙ୍କ ଅଧିକାର। ଆପଣଙ୍କ ଭାଷା। କୌଣସି ଦଲ୍ଲାଲ ନାହିଁ।\n\nମାଇକ୍ ଦବାନ୍ତୁ ଏବଂ ଆପଣଙ୍କ ପ୍ରଶ୍ନ ପଚାରନ୍ତୁ।"
                    "as" -> "আপোনাৰ অধিকাৰ। আপোনাৰ ভাষা। কোনো দালাল নাই।\n\nমাইক টিপক আৰু আপোনাৰ প্ৰশ্ন সুধক।"
                    "ne" -> "तपाईंका अधिकारहरू। तपाईंको भाषा। कुनै दलाल छैन।\n\nमाइक थिच्नुस् र तपाईंको प्रश्न सोध्नुस्।"
                    "en" -> "Your rights. Your language. No middleman.\n\nPress the mic and ask your question."
                    else -> "आपके अधिकार। आपकी भाषा। कोई बिचौलिया नहीं।\n\nमाइक दबाएं और अपना सवाल पूछें।"
                }
            }
            Log.d("Haq/VM", "Profile reloaded: " +
                "id=$profileId " +
                "name=${profile.name} " +
                "lang=${profile.preferredLanguage} " +
                "state=${profile.state} " +
                "caste=${profile.casteCategory} " +
                "occupation=${profile.occupation}")
        }
    }

    fun startNewProfile() {
        _needsOnboarding.value = true
    }

    /** Cancels any in-flight Gemma query. Safe to call at any time. */
    fun cancelCurrentQuery() {
        currentQueryJob?.cancel()
        currentQueryJob = null
        isGenerating = false
        _appState.value = AppState.READY
        // _responseText is intentionally NOT cleared here so a paused partial response
        // stays visible. submitQuery() clears it at the start of each new query, and
        // reloadActiveProfile() repopulates it on profile switch.
        Log.d("Haq/VM", "Current query cancelled")
    }

    /** Stops TTS, cancels Gemma, and returns the app to READY state. */
    fun resetToIdle() {
        TTSManager.stop()
        cancelCurrentQuery()
        Log.d("Haq/VM", "ViewModel reset to idle")
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun retryDownload() {
        downloadManager.retryDownload()
    }

    fun retryEngine() {
        engineObserving = false
        GemmaManager.reinit(getApplication())
        observeEngineState()
    }

    /**
     * Stops any in-progress TTS, listens via the on-device SpeechRecognizer,
     * pipes the transcript into Gemma, then speaks the full response aloud
     * in the detected language.
     */
    fun onMicButtonPressed() {
        if (_engineState.value !is EngineState.Ready) return
        // Tapping while Gemma is streaming saves the partial response and returns to READY.
        if (_appState.value == AppState.THINKING) {
            resetToIdle()
            return
        }
        if (_appState.value != AppState.READY) return

        viewModelScope.launch {
            TTSManager.stop()
            _ttsUnavailable.value = false   // clear any previous "text only" banner
            delay(300) // allow TTS to release audio focus
            val langTag = _activeLanguage.value
            if (STTManager.requiresNetwork(langTag) && !STTManager.isNetworkAvailable(getApplication())) {
                Log.d("Haq/VM", "Mic: lang=$langTag needs network but offline — showing NoWifi prompt")
                _noWifiForMic.value = true
                return@launch
            }

            _appState.value = AppState.LISTENING
            try {
                Log.d("Haq/VM", "Mic pressed: activeLanguage=$langTag profile=${activeProfile?.name}")
                val transcript = STTManager.recordAndTranscribe(
                    context = getApplication(),
                    languageTag = langTag,
                    onVadComplete = {
                        _appState.value = AppState.THINKING
                        _responseText.value = ""
                    },
                    inferenceEngine = GemmaManager,
                )
                Log.d("Haq/STT", "Transcript: \"$transcript\"")
                if (transcript.isNotBlank()) {
                    submitQuery(transcript)
                } else {
                    _appState.value = AppState.READY
                }
            } catch (e: Exception) {
                Log.e("Haq/STT", "Recognition error: ${e.message}")
                _appState.value = AppState.READY
            }
        }
    }

    private fun submitQuery(prompt: String) {
        currentQueryJob = viewModelScope.launch {
            if (_engineState.value !is EngineState.Ready) {
                Log.e("Haq/Gemma", "Engine not ready (state=${_engineState.value}), skipping query")
                _appState.value = AppState.READY
                return@launch
            }

            if (isGenerating) {
                Log.w("Haq/Gemma", "Already generating, ignoring query")
                return@launch
            }

            // Guard against calling generateResponse() before the model file is fully
            // written to disk. A partial/empty file causes Status Code 13.
            if (!GemmaManager.isModelReady()) {
                Log.w("Haq/VM", "Model not ready — query blocked")
                _responseText.value = when (_activeLanguage.value) {
                    "hi" -> "मॉडल लोड हो रहा है। कृपया प्रतीक्षा करें।"
                    "te" -> "మోడల్ లోడ్ అవుతోంది. దయచేసి వేచి ఉండండి."
                    "ml" -> "മോഡൽ ലോഡ് ആകുന്നു. ദയവായി കാത്തിരിക്കൂ."
                    "kn" -> "ಮಾದರಿ ಲೋಡ್ ಆಗುತ್ತಿದೆ. ದಯವಿಟ್ಟು ನಿರೀಕ್ಷಿಸಿ."
                    "ta" -> "மாடல் ஏற்றப்படுகிறது. காத்திருங்கள்."
                    "bn" -> "মডেল লোড হচ্ছে। অপেক্ষা করুন।"
                    "gu" -> "મોડેલ લોડ થઈ રહ્યું છે. રાહ જુઓ."
                    "mr" -> "मॉडेल लोड होत आहे. कृपया थांबा."
                    "or" -> "ମଡେଲ ଲୋଡ ହେଉଛି। ଦୟାକରି ଅପେକ୍ଷା କରନ୍ତୁ।"
                    "as" -> "মডেল লোড হৈ আছে। অনুগ্ৰহ কৰি অপেক্ষা কৰক।"
                    "ne" -> "मोडेल लोड हुँदैछ। कृपया प्रतीक्षा गर्नुस्।"
                    else -> "Model is loading. Please wait."
                }
                _appState.value = AppState.READY
                return@launch
            }

            isGenerating = true
            _appState.value = AppState.THINKING
            _responseText.value = ""

            // Start a foreground service so Android does not kill the process
            // if the user switches apps mid-response. The service does no work —
            // it is purely a process-priority anchor. Stopped in finally below.
            getApplication<android.app.Application>().startForegroundService(
                android.content.Intent(getApplication(), HaqResponseService::class.java)
            )

            // Always instruct Gemma to respond in the profile's language.
            // Profile context (name, state, category, occupation) is prepended to the
            // user query — NOT the system prompt. Embedding it in the system prompt
            // causes LiteRT-LM Status Code 13.
            val languageName = when (_activeLanguage.value) {
                "hi" -> "Hindi"
                "te" -> "Telugu"
                "ml" -> "Malayalam"
                "kn" -> "Kannada"
                "ta" -> "Tamil"
                "bn" -> "Bengali"
                "gu" -> "Gujarati"
                "mr" -> "Marathi"
                "or" -> "Odia"
                "as" -> "Assamese"
                "ne" -> "Nepali"
                "en" -> "English"
                else -> "Hindi"
            }

            // ── RAG: fetch relevant scheme context from schemes.db ────────────
            // Uses FTS5 BM25 search keyed on profile fields (occupation, state,
            // caste) — reliable English terms even when the user query is in
            // another language. Falls back silently to empty if the DB is missing.
            val ragChunks = activeProfile?.takeIf { it.isOnboarded }?.let { p ->
                SchemeRepository.search(
                    context   = getApplication(),
                    query     = prompt,
                    state     = p.state,
                    casteCategory = p.casteCategory,
                    occupation    = p.occupation,
                )
            } ?: emptyList()

            // ── RAG: fetch relevant law sections from law.db ─────────────────
            // Only queried when the user's question contains legal keywords so
            // everyday scheme queries don't pay the law DB scan cost.
            val lawChunks = if (hasLegalKeywords(prompt)) {
                LawRepository.search(context = getApplication(), query = prompt)
            } else emptyList()

            // ── RAG: fetch relevant government offices from offices.db ────────
            // Queried on any query where we can identify relevant office types.
            // Returns up to MAX_RESULTS office RAG chunks for the user's district
            // (falls back to state if district is blank or has no match).
            val officeChunks = activeProfile?.takeIf { it.isOnboarded }?.let { p ->
                val types = officeTypesForQuery(prompt)
                if (types.isEmpty()) emptyList()
                else OfficeRepository.search(
                    context     = getApplication(),
                    state       = p.state,
                    district    = p.district,
                    officeTypes = types,
                )
            } ?: emptyList()

            val ragContext = buildString {
                if (ragChunks.isNotEmpty()) {
                    append("\nRelevant government schemes:\n")
                    append(ragChunks.joinToString("\n---\n"))
                    append("\n")
                }
                if (lawChunks.isNotEmpty()) {
                    append("\nRelevant law:\n")
                    append(lawChunks.joinToString("\n---\n"))
                    append("\n")
                }
                if (officeChunks.isNotEmpty()) {
                    append("\nNearby government offices:\n")
                    append(officeChunks.joinToString("\n---\n"))
                    append("\n")
                }
            }

            Log.d("Haq/RAG", "${ragChunks.size} scheme + ${lawChunks.size} law + ${officeChunks.size} office chunks injected (${ragContext.length} chars)")

            // Build two queries: one with last-exchange history, one without.
            // The fallback is used if the first attempt fails before emitting
            // any tokens — the signature of a KV cache overflow during prefill.
            val baseQuery = buildString {
                append("IMPORTANT: Respond ONLY in $languageName. ")
                append("Do not use any other language. ")
                activeProfile?.takeIf { it.isOnboarded }?.let { p ->
                    append("User: ${p.name}, ")
                    append("State: ${p.state}, ")
                    if (p.district.isNotBlank()) append("District: ${p.district}, ")
                    append("Category: ${p.casteCategory}, ")
                    append("Occupation: ${p.occupation}. ")
                }
                if (ragContext.isNotEmpty()) append(ragContext)
                append("Question: $prompt")
            }

            val hasHistory = activeProfile?.lastQuery?.isNotBlank() == true &&
                activeProfile?.lastResponse?.isNotBlank() == true

            val contextualQuery = if (hasHistory) {
                val p = activeProfile!!
                // Include ragContext in fixedText so historyCharBudget() deducts
                // its token cost and doesn't let history overflow the KV cache.
                val districtPart = if (p.district.isNotBlank()) "District: ${p.district}, " else ""
                val fixedText = buildString {
                    append("IMPORTANT: Respond ONLY in $languageName. ")
                    append("Do not use any other language. ")
                    append("User: ${p.name}, State: ${p.state}, ${districtPart}")
                    append("Category: ${p.casteCategory}, Occupation: ${p.occupation}. ")
                    if (ragContext.isNotEmpty()) append(ragContext)
                    append("Previous question: ${p.lastQuery} Previous answer:  ")
                    append("Question: $prompt")
                }
                val charBudget = historyCharBudget(fixedText)
                val prevResponse = if (p.lastResponse.length > charBudget)
                    "…" + p.lastResponse.takeLast(charBudget)
                else
                    p.lastResponse
                Log.d("Haq/Gemma", "History: budget=${charBudget}chars " +
                    "actual=${prevResponse.length}chars")
                buildString {
                    append("IMPORTANT: Respond ONLY in $languageName. ")
                    append("Do not use any other language. ")
                    append("User: ${p.name}, State: ${p.state}, ${districtPart}")
                    append("Category: ${p.casteCategory}, Occupation: ${p.occupation}. ")
                    if (ragContext.isNotEmpty()) append(ragContext)
                    append("Previous question: ${p.lastQuery} ")
                    append("Previous answer: $prevResponse ")
                    append("Question: $prompt")
                }
            } else {
                baseQuery
            }

            Log.d("Haq/Gemma", "submitQuery() lang=$languageName " +
                "profile=${activeProfile?.name} hasHistory=$hasHistory " +
                "queryLength=${contextualQuery.length}")

            val fullResponse = StringBuilder()
            val sentenceBuffer = StringBuilder()
            val langCode = _activeLanguage.value

            try {
                streamResponse(contextualQuery, langCode, fullResponse, sentenceBuffer)
            } catch (e: CancellationException) {
                Log.d("Haq/VM", "Query cancelled after ${fullResponse.length} chars")
                // Do not rethrow — save whatever was collected before cancellation
            } catch (e: Exception) {
                // If no tokens were received the failure happened during prefill —
                // most likely a KV cache overflow from the history injection.
                // Retry once with the history-free baseQuery before giving up.
                if (hasHistory && fullResponse.isEmpty()) {
                    Log.w("Haq/Gemma", "Query with history failed (${e.message}), " +
                        "retrying without history")
                    sentenceBuffer.clear()
                    try {
                        streamResponse(baseQuery, langCode, fullResponse, sentenceBuffer)
                    } catch (e2: CancellationException) {
                        Log.d("Haq/VM", "Retry cancelled after ${fullResponse.length} chars")
                    } catch (e2: Exception) {
                        Log.e("Haq/Gemma", "Retry also failed", e2)
                        _responseText.value = retryErrorMessage(langCode)
                    }
                } else {
                    Log.e("Haq/Gemma", "submitQuery error", e)
                    if (fullResponse.isEmpty()) _responseText.value = retryErrorMessage(langCode)
                }
            } finally {
                // Use NonCancellable so the save always completes even if cancelled
                if (fullResponse.isNotEmpty()) {
                    activeProfile?.let { p ->
                        val responseStr = fullResponse.toString()
                        withContext(NonCancellable) {
                            ProfileManager.saveLastConversation(
                                profileId = p.id,
                                query = prompt,
                                response = responseStr,
                            )
                            Log.d("Haq/VM", "Saved ${fullResponse.length} chars for profile ${p.id}")
                        }
                        // Keep the in-memory profile in sync so the NEXT query
                        // immediately sees hasHistory=true without a DB round-trip.
                        activeProfile = p.copy(lastQuery = prompt, lastResponse = responseStr)
                    }
                }
                isGenerating = false
                currentQueryJob = null
                _appState.value = AppState.READY
                // Release the process-priority anchor now that generation is done.
                withContext(NonCancellable) {
                    getApplication<android.app.Application>().stopService(
                        android.content.Intent(getApplication(), HaqResponseService::class.java)
                    )
                }
            }
        }
    }

    /**
     * Streams tokens from Gemma into [fullResponse] and [sentenceBuffer],
     * updating [_responseText] and firing TTS sentence-by-sentence.
     * Throws on engine error; the caller handles CancellationException and
     * Exception separately so it can decide whether to retry.
     */
    private suspend fun streamResponse(
        query: String,
        langCode: String,
        fullResponse: StringBuilder,
        sentenceBuffer: StringBuilder,
    ) {
        // Once TTS fails for any sentence we stop trying for the rest of this response.
        // This avoids repeated slow failure attempts when the voice is offline/not installed.
        var ttsErrorSeen = false

        fun speak(text: String) {
            if (ttsErrorSeen) return
            TTSManager.speak(
                text = text,
                languageCode = langCode,
                queueMode = TextToSpeech.QUEUE_ADD,
                onOutputError = {
                    if (!ttsErrorSeen) {
                        ttsErrorSeen = true
                        _ttsUnavailable.value = true
                        Log.w("Haq/VM", "TTS unavailable for $langCode — text-only mode for this response")
                    }
                },
            )
        }

        GemmaManager.generateResponse(query).collect { token ->
            fullResponse.append(token)
            sentenceBuffer.append(token)
            _responseText.value = fullResponse.toString()

            val buf = sentenceBuffer.toString()
            val boundaryIdx = buf.indexOfFirst {
                it == '.' || it == '?' || it == '!' || it == '।'
            }
            if (boundaryIdx >= 0) {
                val sentence = buf
                    .substring(0, boundaryIdx + 1)
                    .replace("**", "")
                    .replace("*", "")
                    .replace("#", "")
                    .trim()
                if (sentence.length >= 15) speak(sentence)
                sentenceBuffer.clear()
                sentenceBuffer.append(buf.substring(boundaryIdx + 1))
            }
        }
        // Speak any text remaining after the stream ends (no trailing punctuation)
        val remaining = sentenceBuffer.toString()
            .replace("**", "")
            .replace("*", "")
            .replace("#", "")
            .trim()
        if (remaining.length >= 5) speak(remaining)
    }

    /** Language-aware message shown when both the primary and retry queries fail. */
    private fun retryErrorMessage(langCode: String): String = when (langCode) {
        "hi" -> "कुछ गड़बड़ हो गई। कृपया दोबारा कोशिश करें।"
        "te" -> "ఏదో తప్పు జరిగింది. దయచేసి మళ్ళీ ప్రయత్నించండి."
        "ml" -> "എന്തോ പിശക് സംഭവിച്ചു. വീണ്ടും ശ്രമിക്കൂ."
        "kn" -> "ಏನೋ ತಪ್ಪಾಯಿತು. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        "ta" -> "ஏதோ தவறு நடந்தது. மீண்டும் முயற்சிக்கவும்."
        "bn" -> "কিছু একটা ভুল হয়েছে। আবার চেষ্টা করুন।"
        "gu" -> "કંઈક ખોટું થયું. કૃપા કરી ફરી પ્રયાસ કરો."
        "mr" -> "काहीतरी चूक झाली. कृपया पुन्हा प्रयत्न करा."
        "or" -> "କିଛି ଭୁଲ ହୋଇଗଲା। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
        "as" -> "কিবা ভুল হৈছে। অনুগ্ৰহ কৰি পুনৰ চেষ্টা কৰক।"
        "ne" -> "केही गलत भयो। कृपया फेरि प्रयास गर्नुस्।"
        else -> "Something went wrong. Please try again."
    }

    /**
     * Estimates the token count of [text] using a conservative, script-aware
     * heuristic. Errs on the side of over-counting (fewer chars allowed = safer).
     *
     *   ASCII / Latin      ≈ 4 chars/token  → 0.25 tokens/char
     *   Latin extended     ≈ 2.5 chars/token → 0.40 tokens/char
     *   Indic scripts      ≈ 1.5 chars/token → 0.67 tokens/char  ← worst case used
     *   Everything else    ≈ 1.5 chars/token → 0.67 tokens/char
     *
     * Indic Unicode blocks covered: Devanagari (hi/mr/ne), Bengali (bn/as),
     * Gujarati (gu), Oriya (or), Tamil (ta), Telugu (te), Kannada (kn),
     * Malayalam (ml) — all fall in U+0900–U+0D7F.
     */
    private fun estimateTokens(text: String): Int {
        var count = 0.0
        for (ch in text) {
            count += when (ch.code) {
                in 0x0000..0x007F -> 0.25   // ASCII
                in 0x0080..0x024F -> 0.40   // Latin extended
                in 0x0900..0x0D7F -> 0.67   // Indic scripts (~1.5 chars/token)
                else              -> 0.67   // conservative fallback
            }
        }
        return kotlin.math.ceil(count).toInt()
    }

    /**
     * Returns the number of characters of previous-response history that can
     * safely be injected into the prompt without overflowing the KV cache.
     *
     * [fixedText] is everything in the user turn EXCEPT the history itself
     * (language instruction + profile metadata + previous question label +
     * new question). Tokens are estimated conservatively so the actual
     * history chars are always an underestimate of what would fit.
     */
    private fun historyCharBudget(fixedText: String): Int {
        val fixedTokens = SYSTEM_PROMPT_TOKENS + estimateTokens(fixedText)
        val remaining = KV_CACHE_TOKENS - RESPONSE_RESERVE_TOKENS - fixedTokens
        // Convert tokens → chars at the same conservative 1.5 chars/token ratio.
        return maxOf(0, (remaining * 1.5).toInt())
    }

    private fun startDownload() {
        downloadManager.startDownload()
    }

    override fun onCleared() {
        super.onCleared()
        STTManager.shutdown()
        TTSManager.shutdown()
        GemmaManager.shutdown()
    }

    /**
     * Returns true when [query] contains words that suggest the user is asking
     * about legal rights, acts, or statutory provisions — so [LawRepository]
     * is worth consulting.  Checked against English keywords only; non-English
     * queries with no ASCII content fall through to scheme-only RAG.
     */
    private fun hasLegalKeywords(query: String): Boolean {
        val lower = query.lowercase()
        val matched = LEGAL_KEYWORDS.firstOrNull { lower.contains(it) }
        Log.d("Haq/RAG", "hasLegalKeywords=${ matched != null } matched='$matched'")
        return matched != null
    }

    /**
     * Returns office types to query from offices.db based on the query content.
     * Multiple types may be returned; OfficeRepository searches with OR across all.
     * Returns empty list only when the query has no recognisable scheme/rights topic.
     */
    private fun officeTypesForQuery(query: String): List<String> {
        val lower = query.lowercase()
        val types = mutableListOf<String>()

        // DLSA — free legal aid, always relevant for legal/rights queries
        if (hasLegalKeywords(query)) types.add("DLSA")

        // LABOUR — employment, wages, bonded labour
        val labourKw = setOf(
            "wage", "wages", "salary", "employer", "employee", "labour", "labor",
            "worker", "dismiss", "fired", "overtime", "esi", "provident",
            "वेतन", "मजदूरी", "श्रमिक",
            "వేతనం", "కూలి", "కార్మికుడు",
            "വേതനം", "കൂലി", "തൊഴിലാളി",
            "ವೇತನ", "ಕೂಲಿ", "ಕಾರ್ಮಿಕ",
            "ஊதியம்", "கூலி", "தொழிலாளர்",
            "বেতন", "মজুরি", "শ্রমিক",              // Bengali
            "વેતન", "મજૂરી", "કામદાર",              // Gujarati
            "वेतन", "मजुरी", "कामगार",              // Marathi
            "ବେତନ", "ମଜୁରି", "ଶ୍ରମିକ",             // Odia
            "দৰমহা", "মজুৰি", "শ্ৰমিক",             // Assamese
            "तलब", "ज्याला", "श्रमिक",              // Nepali
        )
        if (labourKw.any { lower.contains(it) }) types.add("LABOUR")

        // MGNREGA — job card, NREGA work demand, wage payment delays
        val nregaKw = setOf(
            "mnrega", "mgnrega", "nrega", "job card", "100 days", "hundred days",
            "मनरेगा", "नरेगा", "जॉब कार्ड",
            "మనరేగా", "నరేగా", "జాబ్ కార్డ్",
            "മനരേഗ", "നരേഗ", "ജോബ് കാർഡ്",
            "ಮನರೇಗ", "ನರೇಗ", "ಜಾಬ್ ಕಾರ್ಡ್",
            "மனரேகா", "நரேகா", "வேலை அட்டை",
            "মনরেগা", "নরেগা", "জব কার্ড",          // Bengali
            "મનરેગા", "નરેગા", "જોબ કાર્ડ",         // Gujarati
            "मनरेगा", "नरेगा", "जॉब कार्ड",         // Marathi
            "ମନରେଗା", "ନରେଗା", "ଜବ କାର୍ଡ",        // Odia
            "মনৰেগা", "নৰেগা", "জব কাৰ্ড",          // Assamese
            "मनरेगा", "नरेगा", "जब कार्ड",          // Nepali
        )
        if (nregaKw.any { lower.contains(it) }) types.add("MGNREGA")

        // AGRI — crop insurance, PM-KISAN, Kisan Credit Card, agriculture
        val agriKw = setOf(
            "crop", "farmer", "agriculture", "kisan", "pmfby", "pm-kisan", "pm kisan",
            "kcc", "kisan credit", "fasal bima", "crop loss",
            "किसान", "फसल", "कृषि", "फसल बीमा", "पीएम किसान",
            "రైతు", "పంట", "వ్యవసాయం", "పంట బీమా",
            "കർഷകൻ", "വിള", "കൃഷി", "വിള ഇൻഷുറൻസ്",
            "ರೈತ", "ಬೆಳೆ", "ಕೃಷಿ", "ಬೆಳೆ ವಿಮೆ",
            "விவசாயி", "பயிர்", "விவசாயம்", "பயிர் காப்பீடு",
            "কৃষক", "ফসল", "কৃষি", "ফসল বীমা",     // Bengali
            "ખેડૂત", "પાક", "ખેતી", "પાક વીમો",    // Gujarati
            "शेतकरी", "पीक", "शेती", "पीक विमा",   // Marathi
            "ଚାଷୀ", "ଫସଲ", "କୃଷି", "ଫସଲ ବୀମା",   // Odia
            "কৃষক", "শস্য", "কৃষি", "শস্য বীমা",   // Assamese
            "किसान", "बाली", "कृषि", "बाली बीमा",  // Nepali
        )
        if (agriKw.any { lower.contains(it) }) types.add("AGRI")

        // DCDRC — consumer complaints, ration denial, insurance rejection
        val consumerKw = setOf(
            "consumer", "complaint", "ration", "ration card", "pds", "fair price",
            "insurance claim", "claim rejected", "overcharged", "cheated", "fraud",
            "refund", "deficiency", "consumer forum", "consumer court",
            "उपभोक्ता", "शिकायत", "राशन", "राशन कार्ड", "बीमा दावा",
            "వినియోగదారు", "ఫిర్యాదు", "రేషన్", "బీమా క్లెయిమ్",
            "ഉപഭോക്താ", "പരാതി", "റേഷൻ", "ഇൻഷുറൻസ് ക്ലെയിം",
            "ಗ್ರಾಹಕ", "ದೂರು", "ರೇಷನ್", "ವಿಮಾ ಹಕ್ಕು",
            "நுகர்வோர்", "புகார்", "ரேஷன்", "காப்பீட்டு கோரிக்கை",
            "ভোক্তা", "অভিযোগ", "রেশন", "বীমা দাবি",    // Bengali
            "ગ્રાહક", "ફરિયાદ", "રેશন", "વીમા દાવો",    // Gujarati
            "ग्राहक", "तक्रार", "रेशन", "विमा दावा",    // Marathi
            "ଉପଭୋକ୍ତା", "ଅଭିଯୋଗ", "ରେସନ",            // Odia
            "ভোক্তা", "অভিযোগ", "ৰেচন",               // Assamese
            "उपभोक्ता", "गुनासो", "राशन",              // Nepali
        )
        if (consumerKw.any { lower.contains(it) }) types.add("DCDRC")

        // FCS — ration card issues, PDS dealer complaints (district supply officer)
        val fcsKw = setOf(
            "ration card", "ration shop", "fair price shop", "pds", "food security",
            "nfsa", "subsidised grain", "wheat rice quota",
            "राशन कार्ड", "उचित मूल्य", "खाद्य सुरक्षा",
            "రేషన్ కార్డ్", "న్యాయమైన ధర దుకాణం",
            "റേഷൻ കാർഡ്", "ന്യായവില",
            "ರೇಷನ್ ಕಾರ್ಡ್", "ನ್ಯಾಯಬೆಲೆ ಅಂಗಡಿ",
            "ரேஷன் அட்டை", "நியாய விலை கடை",
            "রেশন কার্ড", "ন্যায্য মূল্যের দোকান", "খাদ্য নিরাপত্তা",  // Bengali
            "રેશન કાર્ડ", "સસ્તા અનાજ", "ખાદ્ય સુરક્ષા",              // Gujarati
            "रेशन कार्ड", "स्वस्त धान्य", "अन्न सुरक्षा",               // Marathi
            "ରେସନ କାର୍ଡ", "ଖାଦ୍ୟ ସୁରକ୍ଷା",                           // Odia
            "ৰেচন কাৰ্ড", "খাদ্য সুৰক্ষা",                             // Assamese
            "राशन कार्ड", "खाद्य सुरक्षा",                              // Nepali
        )
        if (fcsKw.any { lower.contains(it) }) types.add("FCS")

        // SW — SC/ST welfare, pensions, scholarships, disability
        val swKw = setOf(
            "pension", "scholarship", "dalit", "social welfare",
            "widow", "disability", "handicap", "backward class",
            "ignoaps", "igndps", "ignwps", "nfbs", "nsap",
            "पेंशन", "छात्रवृत्ति", "विकलांग", "विधवा",
            "పెన్షన్", "స్కాలర్‌షిప్", "వికలాంగ",
            "പെൻഷൻ", "സ്കോളർഷിപ്പ്", "ഭിന്നശേഷി", "വിധവ",
            "ಪಿಂಚಣಿ", "ವಿಕಲಚೇತನ", "ವಿಧವೆ",
            "ஓய்வூதியம்", "உதவித்தொகை", "மாற்றுத்திறனாளி",
            "পেনশন", "বৃত্তি", "প্রতিবন্ধী", "বিধবা",     // Bengali
            "પેન્શન", "શિષ્યવૃત્તિ", "વિકલાંગ", "વિધવા",  // Gujarati
            "पेन्शन", "शिष्यवृत्ती", "अपंग", "विधवा",      // Marathi
            "ପେନ୍‌ସନ", "ଛାତ୍ରବୃତ୍ତି", "ବିକଳାଙ୍ଗ", "ବିଧବା", // Odia
            "পেঞ্চন", "বৃত্তি", "বিকলাঙ্গ", "বিধৱা",      // Assamese
            "पेन्सन", "छात्रवृत्ति", "अपाङ्ग", "विधवा",   // Nepali
        )
        if (swKw.any { lower.contains(it) }) types.add("SW")

        // TRIBAL — forest rights, tribal land, ST welfare, PVTG
        val tribalKw = setOf(
            "tribal", "forest rights", "fra", "forest land", "patta", "van adhikar",
            "pvtg", "adivasi", "scheduled tribe",
            "आदिवासी", "वन अधिकार", "वन भूमि", "पट्टा",
            "ఆదివాసి", "అడవి హక్కులు", "అటవీ భూమి",
            "ആദിവാസി", "വനാവകാശം", "വനഭൂമി",
            "ಆದಿವಾಸಿ", "ಅರಣ್ಯ ಹಕ್ಕು",
            "ஆதிவாசி", "வனஉரிமை",
            "আদিবাসী", "বনাধিকার", "বন জমি",          // Bengali
            "આદિવાસી", "વન અધિકાર", "વન જમીન",       // Gujarati
            "आदिवासी", "वन हक्क", "वन जमीन",          // Marathi
            "ଆଦିବାସୀ", "ବନ ଅଧିକାର", "ବନ ଜମି",       // Odia
            "আদিবাসী", "বন অধিকাৰ", "বন ভূমি",       // Assamese
            "आदिवासी", "वन अधिकार", "वन जमिन",        // Nepali
        )
        if (tribalKw.any { lower.contains(it) }) types.add("TRIBAL")

        // CMO — health, hospital, treatment, medicines, NHM
        val cmoKw = setOf(
            "hospital", "health", "doctor", "medicine", "treatment", "ambulance",
            "nhm", "ayushman", "pmjay", "health card", "free treatment",
            "अस्पताल", "स्वास्थ्य", "दवाई", "इलाज",
            "ఆసుపత్రి", "ఆరోగ్యం", "మందులు", "చికిత్స",
            "ആശുപത്രി", "ആരോഗ്യം", "മരുന്ന്", "ചികിത്സ",
            "ಆಸ್ಪತ್ರೆ", "ಆರೋಗ್ಯ", "ಔಷಧ", "ಚಿಕಿತ್ಸೆ",
            "மருத்துவமனை", "சுகாதாரம்", "மருந்து", "சிகிச்சை",
            "হাসপাতাল", "স্বাস্থ্য", "ওষুধ", "চিকিৎসা",   // Bengali
            "હૉસ્પિટલ", "સ્વાસ્થ્ય", "દવા", "ઇલાજ",       // Gujarati
            "रुग्णालय", "आरोग्य", "औषध", "उपचार",           // Marathi
            "ଡାକ୍ତରଖାନା", "ସ୍ୱାସ୍ଥ୍ୟ", "ଔଷଧ", "ଚିକିତ୍ସା", // Odia
            "চিকিৎসালয়", "স্বাস্থ্য", "দৰব", "চিকিৎসা",   // Assamese
            "अस्पताल", "स्वास्थ्य", "औषधि", "उपचार",        // Nepali
        )
        if (cmoKw.any { lower.contains(it) }) types.add("CMO")

        // PMJAY — Ayushman Bharat health card, cashless treatment
        val pmjayKw = setOf(
            "ayushman", "pmjay", "jan arogya", "health card", "golden card",
            "5 lakh", "cashless", "empanelled hospital",
            "आयुष्मान", "जन आरोग्य", "हेल्थ कार्ड",
            "ఆయుష్మాన్", "జన్ ఆరోగ్య",
            "ആയുഷ്മാൻ", "ജൻ ആരോഗ്യ",
            "ಆಯುಷ್ಮಾನ್", "ಜನ ಆರೋಗ್ಯ",
            "ஆயுஷ்மான்", "ஜன் ஆரோக்கியா",
            "আয়ুষ্মান", "জন আরোগ্য", "স্বাস্থ্য কার্ড",  // Bengali
            "આયુષ્માન", "જન આરોગ્ય", "હેલ્થ કાર્ડ",       // Gujarati
            "आयुष्मान", "जन आरोग्य", "आरोग्य कार्ड",       // Marathi
            "ଆୟୁଷ୍ମାନ", "ଜନ ଆରୋଗ୍ୟ",                      // Odia
            "আয়ুষ্মান", "জন আৰোগ্য",                       // Assamese
            "आयुष्मान", "जन आरोग्य",                         // Nepali
        )
        if (pmjayKw.any { lower.contains(it) }) types.add("PMJAY")

        // WCD — anganwadi, maternity benefit, child nutrition, PMMVY, ICDS
        val wcdKw = setOf(
            "anganwadi", "icds", "pmmvy", "maternity benefit", "child nutrition",
            "beti bachao", "beti padhao", "domestic violence", "women protection",
            "आंगनवाड़ी", "मातृत्व", "बेटी बचाओ",
            "అంగన్‌వాడి", "మాతృత్వం", "బేటి బచావో",
            "അങ്കൺവാടി", "മാതൃത്വ ആനുകൂല്യം",
            "ಅಂಗನವಾಡಿ", "ಮಾತೃತ್ವ ಸೌಲಭ್ಯ",
            "அங்கன்வாடி", "மகப்பேறு நலன்",
            "অঙ্গনওয়াড়ি", "মাতৃত্ব সুবিধা", "শিশু পুষ্টি",  // Bengali
            "આંગણવાડી", "માતૃત્વ લાભ", "બેટી બચાઓ",           // Gujarati
            "अंगणवाडी", "मातृत्व लाभ", "बेटी बचाओ",            // Marathi
            "ଅଙ୍ଗନୱାଡ଼ି", "ମାତୃତ୍ୱ ଲାଭ",                     // Odia
            "অংগনৱাড়ি", "মাতৃত্ব সুবিধা",                      // Assamese
            "आँगनवाडी", "मातृत्व लाभ",                          // Nepali
        )
        if (wcdKw.any { lower.contains(it) }) types.add("WCD")

        // DEO — school, education, RTE admission, scholarship, mid-day meal
        val deoKw = setOf(
            "school", "education", "rte", "mid-day meal", "midday meal",
            "admission denied", "out of school", "dropout", "free education",
            "स्कूल", "शिक्षा", "आरटीई", "मध्याह्न भोजन",
            "స్కూల్", "విద్య", "ఆర్‌టీఈ", "మధ్యాహ్న భోజనం",
            "സ്കൂൾ", "വിദ്യാഭ്യാസം", "ആർടിഇ", "ഉച്ചഭക്ഷണം",
            "ಶಾಲೆ", "ಶಿಕ್ಷಣ", "ಆರ್‌ಟಿಇ", "ಮಧ್ಯಾಹ್ನ ಊಟ",
            "பள்ளி", "கல்வி", "ஆர்டிஈ", "மதிய உணவு",
            "স্কুল", "শিক্ষা", "মিড-ডে মিল",       // Bengali
            "શાળા", "શિક્ષણ", "મધ્યાહ્ન ભોજન",    // Gujarati
            "शाळा", "शिक्षण", "मध्यान्ह भोजन",     // Marathi
            "ବିଦ୍ୟାଳୟ", "ଶିକ୍ଷା", "ମଧ୍ୟାହ୍ନ ଭୋଜନ", // Odia
            "বিদ্যালয়", "শিক্ষা", "মধ্যাহ্ন আহাৰ", // Assamese
            "विद्यालय", "शिक्षा", "मध्यान्ह भोजन",  // Nepali
        )
        if (deoKw.any { lower.contains(it) }) types.add("DEO")

        // SP — police, FIR, safety, missing person, trafficking
        val spKw = setOf(
            "police", "fir", "complaint police", "missing", "kidnap", "trafficking",
            "harassment", "threat", "assault", "domestic violence police",
            "पुलिस", "एफआईआर", "लापता", "उत्पीड़न",
            "పోలీసు", "ఎఫ్‌ఐఆర్", "గల్లంతు",
            "പോലീസ്", "എഫ്ഐആർ", "കാണാതായ",
            "ಪೊಲೀಸ್", "ಎಫ್‌ಐಆರ್", "ನಾಪತ್ತೆ",
            "போலீஸ்", "எஃப்ஐஆர்", "காணாமல்",
            "পুলিশ", "এফআইআর", "নিখোঁজ", "হয়রানি",   // Bengali
            "પોલીસ", "એફઆઇઆર", "ગુમ", "ઉત્પીડન",     // Gujarati
            "पोलीस", "एफआयआर", "बेपत्ता", "त्रास",    // Marathi
            "ପୋଲିସ", "ଏଫଆଇଆର", "ନିଖୋଜ",             // Odia
            "আৰক্ষী", "এফআইআৰ", "নিখোঁজ",            // Assamese
            "प्रहरी", "एफआईआर", "हराएको",             // Nepali
        )
        if (spKw.any { lower.contains(it) }) types.add("SP")

        // COLLECTOR — grievances, certificates, land records
        val collectorKw = setOf(
            "collector", "district magistrate", "grievance", "caste certificate",
            "income certificate", "domicile", "land record", "revenue", "tehsildar",
            "जाति प्रमाण", "आय प्रमाण", "भूमि",
            "కులం సర్టిఫికేట్", "భూమి రికార్డు",
            "ജാതി സർട്ടിഫിക്കറ്റ്",
            "ಜಾತಿ ಪ್ರಮಾಣಪತ್ರ",
            "சாதி சான்றிதழ்",
            "জাতি সনদ", "আয় সনদ", "জমি",              // Bengali
            "જ્ઞાતિ પ્રમાણ", "આવક પ્રમાણ", "જમીન",    // Gujarati
            "जाती प्रमाणपत्र", "उत्पन्न प्रमाणपत्र", "जमीन", // Marathi
            "ଜାତି ପ୍ରମାଣ", "ଜମି",                      // Odia
            "জাতি প্ৰমাণপত্ৰ", "জমি",                   // Assamese
            "जाति प्रमाणपत्र", "जग्गा",                 // Nepali
        )
        if (collectorKw.any { lower.contains(it) }) types.add("COLLECTOR")

        // PMKISAN — PM-KISAN nodal officer
        val pmkisanKw = setOf(
            "pm-kisan", "pm kisan", "pmkisan", "kisan instalment", "kisan installment",
            "kisan payment", "kisan samman",
            "पीएम किसान", "किसान सम्मान", "किसान क़िस्त",
            "పీఎం కిసాన్", "కిసాన్ సమ్మాన్",
            "കർഷക സമ്മാൻ", "പിഎം കിസാൻ",             // Malayalam
            "ಪಿಎಂ ಕಿಸಾನ್", "ಕಿಸಾನ್ ಸಮ್ಮಾನ",         // Kannada
            "பிஎம் கிசான்", "கிசான் சம்மான்",         // Tamil
            "পিএম কিসান", "কিসান সম্মান",             // Bengali
            "પીએમ કિસાન", "કિસાન સન્માન",            // Gujarati
            "पीएम किसान", "किसान सन्मान",             // Marathi
            "ପିଏମ କିସାନ", "କିସାନ ସମ୍ମାନ",           // Odia
            "পিএম কিষাণ", "কিষাণ সম্মান",            // Assamese
            "पीएम किसान", "किसान सम्मान",             // Nepali
        )
        if (pmkisanKw.any { lower.contains(it) }) types.add("PMKISAN")

        // INSOMBU — insurance ombudsman for escalated insurance disputes
        val ombuKw = setOf(
            "insurance ombudsman", "irdai", "claim settlement", "insurance dispute",
            "rejected claim",
            "बीमा लोकपाल", "బీమా లోక్‌పాల్",
            "ഇൻഷുറൻസ് ഓംബുഡ്സ്മാൻ", "ഇർഡായ്",     // Malayalam
            "ವಿಮಾ ಲೋಕಪಾಲ", "ಐಆರ್‌ಡಿಎಐ",           // Kannada
            "காப்பீட்டு லோக்பால்",                   // Tamil
            "বীমা লোকপাল",                           // Bengali
            "વીમા લોકપાલ",                           // Gujarati
            "विमा लोकपाल",                           // Marathi
            "ବୀମା ଲୋକପାଳ",                          // Odia
            "বীমা লোকপাল",                           // Assamese
            "बीमा लोकपाल",                           // Nepali
        )
        if (ombuKw.any { lower.contains(it) }) types.add("INSOMBU")

        // EPFO — PF, provident fund, pension, EDLI
        val epfoKw = setOf(
            "provident fund", "pf ", " epf", "epfo", "uan", "pf withdrawal",
            "pf transfer", "eps pension", "edli",
            "भविष्य निधि", "पीएफ", "ईपीएफओ",
            "ప్రావిడెంట్ ఫండ్", "పీఎఫ్",
            "പ്രൊവിഡന്റ് ഫണ്ട്", "പിഎഫ്",
            "ಭವಿಷ್ಯ ನಿಧಿ", "ಪಿಎಫ್",
            "வருங்கால வைப்பு நிதி", "பிஎஃப்",
            "ভবিষ্যৎ তহবিল", "পিএফ",               // Bengali
            "ભવિષ્ય નિધિ", "પીએફ",                 // Gujarati
            "भविष्य निर्वाह निधी", "पीएफ",          // Marathi
            "ଭବିଷ୍ୟ ନିଧି", "ପିଏଫ",                // Odia
            "ভৱিষ্যৎ নিধি", "পিএফ",               // Assamese
            "भविष्य कोष", "पिएफ",                   // Nepali
        )
        if (epfoKw.any { lower.contains(it) }) types.add("EPFO")

        // UIDAI — Aadhaar enrolment, update, correction
        val uidaiKw = setOf(
            "aadhaar", "aadhar", "uid", "biometric", "aadhaar update", "aadhaar correction",
            "आधार", "यूआईडी",
            "ఆధార్", "యూఐడీ",
            "ആധാർ", "യുഐഡി",
            "ಆಧಾರ್", "ಯುಐಡಿ",
            "ஆதார்", "யுஐடி",
            "আধার", "ইউআইডি",        // Bengali
            "આધાર", "યૂઆઇડી",        // Gujarati
            "आधार", "यूआयडी",         // Marathi
            "ଆଧାର",                   // Odia
            "আধাৰ",                   // Assamese
            "आधार",                    // Nepali
        )
        if (uidaiKw.any { lower.contains(it) }) types.add("UIDAI")

        // PASSPORT — passport, tatkal, travel document
        val passportKw = setOf(
            "passport", "tatkal", "travel document", "psk", "passport seva",
            "पासपोर्त", "पासपोर्ट",
            "పాస్‌పోర్ట్",
            "പാസ്‌പോർട്ട്",
            "ಪಾಸ್‌ಪೋರ್ಟ್",
            "பாஸ்போர்ட்",
            "পাসপোর্ট",               // Bengali
            "પાસપોર્ટ",               // Gujarati
            "पासपोर्ट",                // Marathi
            "ପାସପୋର୍ଟ",              // Odia
            "পাছপোৰ্ট",               // Assamese
            "राहदानी",                 // Nepali
        )
        if (passportKw.any { lower.contains(it) }) types.add("PASSPORT")

        // NABARD — agricultural credit, SHG, microfinance, rural loan
        val nabardKw = setOf(
            "nabard", "shg", "self help group", "microfinance", "rural credit",
            "kisan credit card", "ridf",
            "नाबार्ड", "स्वयं सहायता समूह",
            "నాబార్డ్", "స్వయం సహాయక సంఘం",
            "നബാർഡ്", "സ്വയം സഹായ സംഘം",
            "ನಬಾರ್ಡ್", "ಸ್ವಸಹಾಯ ಸಂಘ",
            "நபார்டு", "சுய உதவிக் குழு",
            "নাবার্ড", "স্বনির্ভর গোষ্ঠী", "ক্ষুদ্রঋণ",  // Bengali
            "નાબાર્ડ", "સ્વ-સહાય જૂથ",                  // Gujarati
            "नाबार्ड", "बचत गट", "सूक्ष्म वित्त",          // Marathi
            "ନାବାର୍ଡ", "ସ୍ୱ-ସାହାୟ ଗୋଷ୍ଠୀ",             // Odia
            "নাবাৰ্ড", "স্বসহায় গোট",                    // Assamese
            "नाबार्ड", "स्वसहायता समूह",                  // Nepali
        )
        if (nabardKw.any { lower.contains(it) }) types.add("NABARD")

        // SIC/CIC — RTI, right to information, information commission
        val rtiKw = setOf(
            "rti", "right to information", "information commission", "pio",
            "public information officer", "second appeal",
            "आरटीआई", "सूचना का अधिकार", "सूचना आयोग",
            "ఆర్‌టీఐ", "సమాచార హక్కు",
            "ആർടിഐ", "വിവരാവകാശം",
            "ಆರ್‌ಟಿಐ", "ಮಾಹಿತಿ ಹಕ್ಕು",
            "ஆர்டிஐ", "தகவல் உரிமை",
            "আরটিআই", "তথ্য অধিকার",           // Bengali
            "આરટીઆઇ", "માહિતી અધિકાર",        // Gujarati
            "आरटीआय", "माहिती अधिकार",         // Marathi
            "ଆରଟିଆଇ", "ତଥ୍ୟ ଅଧିକାର",         // Odia
            "আৰটিআই", "তথ্য অধিকাৰ",          // Assamese
            "आरटीआई", "सूचनाको हक",            // Nepali
        )
        if (rtiKw.any { lower.contains(it) }) types.add("CIC")

        // RTO — vehicle registration, driving licence, permit, road tax
        val rtoKw = setOf(
            "rto", "vehicle registration", "driving licence", "driving license",
            "rc book", "fitness certificate", "road tax", "motor vehicle", "permit",
            "वाहन पंजीकरण", "ड्राइविंग लाइसेंस", "परमिट",
            "వాహన నమోదు", "డ్రైవింగ్ లైసెన్స్",
            "വാഹന രജിസ്ട്രേഷൻ", "ഡ്രൈവിംഗ് ലൈസൻസ്",
            "ವಾಹನ ನೋಂದಣಿ", "ಚಾಲನಾ ಪರವಾನಗಿ",
            "வாகன பதிவு", "ஓட்டுநர் உரிமம்",
            "যানবাহন নিবন্ধন", "ড্রাইভিং লাইসেন্স",     // Bengali
            "વાહન નોંધણી", "ડ્રાઇવિંગ લાઇસન્સ",         // Gujarati
            "वाहन नोंदणी", "वाहन परवाना",                  // Marathi
            "ଯାନ ପଞ୍ଜୀକରଣ", "ଡ୍ରାଇଭିଂ ଲାଇସେନ୍ସ",      // Odia
            "যান পঞ্জীয়ন", "ড্ৰাইভিং লাইচেন্স",         // Assamese
            "सवारी दर्ता", "सवारी चालक अनुमतिपत्र",       // Nepali
        )
        if (rtoKw.any { lower.contains(it) }) types.add("RTO")

        // NCSC — SC/ST commission for atrocity/discrimination
        val ncscKw = setOf(
            "atrocity", "discrimination", "ncsc", "sc/st commission", "untouchability",
            "अत्याचार", "भेदभाव",
            "అఘాయిత్యం", "వివక్ష",
            "അക്രമം", "വിവേചനം",              // Malayalam
            "ದೌರ್ಜನ್ಯ", "ತಾರತಮ್ಯ",          // Kannada
            "கொடுமை", "பாரபட்சம்",            // Tamil
            "অত্যাচার", "বৈষম্য",             // Bengali
            "અત્યાચાર", "ભેદભાવ",            // Gujarati
            "अत्याचार", "भेदभाव",             // Marathi
            "ଅତ୍ୟାଚାର", "ଭେଦଭାବ",           // Odia
            "অত্যাচাৰ", "বৈষম্য",             // Assamese
            "अत्याचार", "भेदभाव",             // Nepali
        )
        if (ncscKw.any { lower.contains(it) }) types.add("NCSC")

        // NCBC — OBC reservation, backward class discrimination
        val ncbcKw = setOf(
            "ncbc", "obc", "other backward class", "backward class reservation",
            "obc certificate", "obc list",
            "ओबीसी", "अन्य पिछड़ा",
            "ఓబీసీ", "వెనుకబడిన తరగతి",
            "ഒബിസി", "പിന്നോക്ക വർഗം",
            "ಒಬಿಸಿ", "ಹಿಂದುಳಿದ ವರ್ಗ",
            "ஓபிசி", "பிற்படுத்தப்பட்ட",
            "ওবিসি", "অন্যান্য অনগ্রসর শ্রেণী",   // Bengali
            "ઓબીસી", "અન્ય પછાત વર્ગ",           // Gujarati
            "ओबीसी", "इतर मागासवर्गीय",           // Marathi
            "ଓବିସି", "ଅନ୍ୟ ପଛୁଆ ଶ୍ରେଣୀ",       // Odia
            "ওবিচি", "অন্য পিছপৰা শ্ৰেণী",       // Assamese
            "ओबिसी", "अन्य पिछडिएको वर्ग",        // Nepali
        )
        if (ncbcKw.any { lower.contains(it) }) types.add("NCBC")

        // NCST — ST rights, tribal commission
        val ncstKw = setOf(
            "ncst", "scheduled tribe commission", "st rights", "tribal atrocity",
            "अनुसूचित जनजाति आयोग",
            "షెడ్యూల్డ్ తెగల కమిషన్",
            "তফসিলি উপজাতি কমিশন",       // Bengali
            "અનુસૂચિત જનજાતિ આ",         // Gujarati
            "अनुसूचित जमाती आयोग",        // Marathi
            "ଅନୁସୂଚିତ ଜନଜାତି ଆୟୋଗ",     // Odia
            "অনুসূচিত জনজাতি আয়োগ",     // Assamese
            "अनुसूचित जनजाति आयोग",       // Nepali
        )
        if (ncstKw.any { lower.contains(it) }) types.add("NCST")

        // NCW — women rights, sexual harassment, gender violence
        val ncwKw = setOf(
            "ncw", "women commission", "sexual harassment", "gender violence",
            "women rights", "mahila aayog",
            "महिला आयोग", "यौन उत्पीड़न",
            "మహిళా కమిషన్", "లైంగిక వేధింపు",
            "വനിതാ കമ്മിഷൻ", "ലൈംഗിക പീഡനം",
            "ಮಹಿಳಾ ಆಯೋಗ", "ಲೈಂಗಿಕ ಕಿರುಕುಳ",
            "பெண்கள் ஆணையம்", "பாலியல் துன்புறுத்தல்",
            "মহিলা কমিশন", "যৌন হয়রানি",    // Bengali
            "મહિલા આયોગ", "જાતીય ઉત્પીડન", // Gujarati
            "महिला आयोग", "लैंगिक छळ",       // Marathi
            "ମହିଳା ଆୟୋଗ", "ଯୌନ ହୟରାଣ",   // Odia
            "মহিলা আয়োগ", "যৌন হাৰাশমেন্ট", // Assamese
            "महिला आयोग", "यौन दुर्व्यवहार", // Nepali
        )
        if (ncwKw.any { lower.contains(it) }) types.add("NCW")

        // NHRC — human rights violations, custodial death, police brutality
        val nhrcKw = setOf(
            "nhrc", "human rights", "custodial death", "police brutality",
            "rights violation", "manav adhikar",
            "मानव अधिकार", "हिरासत में मौत",
            "మానవ హక్కులు", "కస్టడీ మరణం",
            "മനുഷ്യാവകാശം", "കസ്റ്റഡി മരണം",
            "ಮಾನವ ಹಕ್ಕು", "ಕಸ್ಟಡಿ ಸಾವು",
            "மனித உரிமை", "காவல் நிலையத்தில் மரணம்",
            "মানবাধিকার", "হেফাজতে মৃত্যু",    // Bengali
            "માનવ અધિકાર", "કસ્ટડીમાં મૃત્યુ", // Gujarati
            "मानवाधिकार", "कोठडीत मृत्यू",      // Marathi
            "ମାନବ ଅଧିକାର", "ହାଜତ ମୃତ୍ୟୁ",    // Odia
            "মানৱ অধিকাৰ", "হাজতত মৃত্যু",     // Assamese
            "मानव अधिकार", "हिरासतमा मृत्यु",  // Nepali
        )
        if (nhrcKw.any { lower.contains(it) }) types.add("NHRC")

        // Location follow-up — surface all common office types so Gemma can
        // pick whichever is relevant from context.
        val locationKw = setOf(
            "where", "address", "location", "directions", "how to reach", "how to go",
            "how do i get", "find the office", "office address", "office location",
            "कहाँ", "कहां", "पता", "कार्यालय",           // Hindi
            "ఎక్కడ", "చిరునామా", "కార్యాలయం",            // Telugu
            "എവിടെ", "വിലാസം", "ഓഫീസ്",                 // Malayalam
            "ಎಲ್ಲಿ", "ವಿಳಾಸ", "ಕಚೇರಿ",                  // Kannada
            "எங்கே", "முகவரி", "அலுவலகம்",               // Tamil
            "কোথায়", "ঠিকানা", "অফিস",                   // Bengali
            "ક્યાં", "સરનામું", "કચેરી",                  // Gujarati
            "कुठे", "पत्ता", "कार्यालय",                   // Marathi
            "କେଉଁଠି", "ଠିକଣା", "କାର୍ଯ୍ୟାଳୟ",             // Odia
            "ক'ত", "ঠিকনা", "কাৰ্যালয়",                  // Assamese
            "कहाँ", "ठेगाना", "कार्यालय",                  // Nepali
        )
        if (locationKw.any { lower.contains(it) }) {
            for (t in listOf(
                "AGRI", "CMO", "COLLECTOR", "DEO", "DLSA", "EPFO",
                "FCS", "LABOUR", "MGNREGA", "NABARD", "PASSPORT",
                "PMJAY", "PMKISAN", "SP", "SW", "TRIBAL", "UIDAI",
            )) {
                if (!types.contains(t)) types.add(t)
            }
        }

        return types
    }

    companion object {
        // Must match LiteRTEngine.MAX_TOKENS (2048).
        private const val KV_CACHE_TOKENS = 2048
        // Tokens reserved for Gemma's output — tighten if responses feel truncated,
        // loosen if KV overflow errors appear.
        private const val RESPONSE_RESERVE_TOKENS = 600
        // System prompt token count measured from prefill logs.
        private const val SYSTEM_PROMPT_TOKENS = 110

        // Keywords that trigger LawRepository lookup.
        // Covers all 12 supported languages across 5 categories:
        //   statutory/rights · offences/enforcement · labour/employment
        //   property/family  · consumer/other
        // hasLegalKeywords() calls contains() on the lowercased query —
        // non-ASCII scripts match verbatim (no case in Devanagari etc.).
        private val LEGAL_KEYWORDS = setOf(

            // ── English ───────────────────────────────────────────────────────
            // Statutory / rights
            "right", "rights", "law", "act", "section", "legal", "court",
            "entitled", "liable", "tribunal", "appeal", "violation", "protection",
            "under the", "according to", "remedy", "cognizable",
            // Offences / enforcement
            "penalty", "offence", "offense", "punish", "fine", "jail", "arrest",
            "warrant", "complaint", "dispute", "grievance", "damages", "compensation",
            // Labour / employment
            "wage", "wages", "salary", "minimum wage", "employer", "employee",
            "labour", "labor", "worker", "dismiss", "fired", "termination",
            "overtime", "working hours", "provident fund", "esi", "maternity",
            // Property / family
            "evict", "eviction", "tenant", "landlord", "divorce", "custody",
            "inheritance", "property", "dowry", "domestic violence",
            // Consumer / other
            "fraud", "cheat", "cheated", "bribe", "corruption", "ration",

            // ── Hindi (hi) ────────────────────────────────────────────────────
            // Statutory / rights
            "अधिकार",        // right / entitlement
            "कानून",          // law
            "अधिनियम",       // act (legislative)
            "धारा",           // section of an act
            "न्यायालय",       // court
            "अदालत",          // court (colloquial)
            "न्यायाधिकरण",   // tribunal
            "अपील",           // appeal
            "उल्लंघन",        // violation
            "संरक्षण",        // protection
            "हकदार",          // entitled
            // Offences / enforcement
            "दंड",            // penalty
            "जुर्माना",        // fine
            "जेल",            // jail
            "गिरफ्तारी",      // arrest
            "वारंट",          // warrant
            "शिकायत",         // complaint
            "विवाद",          // dispute
            "मुआवजा",         // compensation
            "हर्जाना",         // damages
            // Labour / employment
            "वेतन",           // salary
            "मजदूरी",         // wages
            "न्यूनतम मजदूरी", // minimum wages
            "नियोक्ता",       // employer
            "कर्मचारी",       // employee
            "श्रमिक",         // worker
            "बर्खास्त",       // dismissed / fired
            "ओवरटाइम",       // overtime
            "भविष्य निधि",   // provident fund
            "मातृत्व",        // maternity
            // Property / family
            "बेदखल",          // eviction
            "किरायेदार",      // tenant
            "मकान मालिक",    // landlord
            "तलाक",           // divorce
            "हिरासत",         // custody
            "विरासत",         // inheritance
            "संपत्ति",        // property
            "दहेज",           // dowry
            "घरेलू हिंसा",    // domestic violence
            // Consumer / other
            "धोखाधड़ी",       // fraud
            "धोखा",           // cheat
            "रिश्वत",         // bribe
            "भ्रष्टाचार",     // corruption
            "राशन",           // ration

            // ── Telugu (te) ───────────────────────────────────────────────────
            // Statutory / rights
            "హక్కు",          // right
            "చట్టం",          // law
            "చట్ట నిబంధన",   // section of an act
            "న్యాయస్థానం",    // court
            "న్యాయమండలి",    // tribunal
            "అప్పీలు",        // appeal
            "ఉల్లంఘన",        // violation
            "రక్షణ",          // protection
            // Offences / enforcement
            "శిక్ష",           // punishment / penalty
            "జరిమానా",        // fine
            "జైలు",           // jail
            "అరెస్టు",        // arrest
            "వారెంటు",        // warrant
            "ఫిర్యాదు",       // complaint
            "వివాదం",         // dispute
            "నష్టపరిహారం",    // compensation
            "నష్టాలు",        // damages
            // Labour / employment
            "వేతనం",          // salary / wages
            "కూలి",           // daily wages
            "కార్మికుడు",     // worker
            "యజమాని",        // employer
            "ఉద్యోగి",        // employee
            "తొలగింపు",       // dismissal
            "అదనపు గంటలు",   // overtime
            "భవిష్యనిధి",     // provident fund
            "మాతృత్వం",      // maternity
            // Property / family
            "అద్దెదారు",      // tenant
            "ఇంటి యజమాని",  // landlord
            "విడాకులు",       // divorce
            "సంరక్షణ",        // custody
            "వారసత్వం",       // inheritance
            "ఆస్తి",          // property
            "కట్నం",          // dowry
            "గృహహింస",       // domestic violence
            // Consumer / other
            "మోసం",           // fraud
            "లంచం",           // bribe
            "అవినీతి",        // corruption
            "రేషను",          // ration

            // ── Malayalam (ml) ────────────────────────────────────────────────
            // Statutory / rights
            "അവകാശം",        // right
            "നിയമം",          // law
            "വകുപ്പ്",         // section of an act
            "കോടതി",          // court
            "ട്രൈബ്യൂണൽ",    // tribunal
            "അപ്പീൽ",         // appeal
            "ലംഘനം",         // violation
            "സംരക്ഷണം",      // protection
            // Offences / enforcement
            "ശിക്ഷ",          // punishment
            "പിഴ",            // fine
            "ജയിൽ",          // jail
            "അറസ്റ്റ്",        // arrest
            "വാറണ്ട്",        // warrant
            "പരാതി",          // complaint
            "തർക്കം",         // dispute
            "നഷ്ടപരിഹാരം",   // compensation
            "നഷ്ടം",          // damages
            // Labour / employment
            "വേതനം",          // salary / wages
            "കൂലി",           // daily wages
            "തൊഴിലാളി",      // worker
            "തൊഴിലുടമ",      // employer
            "ജീവനക്കാരൻ",    // employee
            "പിരിച്ചുവിടൽ",  // dismissal
            "ഓവർടൈം",        // overtime
            "പ്രൊവിഡന്റ് ഫണ്ട്", // provident fund
            "പ്രസവ ആനുകൂല്യം",   // maternity benefit
            // Property / family
            "വാടകക്കാരൻ",    // tenant
            "വീട്ടുടമ",       // landlord
            "വിവാഹമോചനം",    // divorce
            "കസ്റ്റഡി",       // custody
            "അനന്തരാവകാശം",  // inheritance
            "സ്വത്ത്",        // property
            "സ്ത്രീധനം",      // dowry
            "ഗാർഹിക പീഡനം",  // domestic violence
            // Consumer / other
            "തട്ടിപ്പ്",       // fraud
            "കൈക്കൂലി",       // bribe
            "അഴിമതി",        // corruption
            "റേഷൻ",          // ration

            // ── Kannada (kn) ──────────────────────────────────────────────────
            // Statutory / rights
            "ಹಕ್ಕು",          // right
            "ಕಾನೂನು",         // law
            "ಕಾಯ್ದೆ",         // act (legislative)
            "ಕಲಂ",            // section of an act
            "ನ್ಯಾಯಾಲಯ",       // court
            "ನ್ಯಾಯಮಂಡಳಿ",    // tribunal
            "ಮೇಲ್ಮನವಿ",       // appeal
            "ಉಲ್ಲಂಘನೆ",       // violation
            "ರಕ್ಷಣೆ",         // protection
            // Offences / enforcement
            "ಶಿಕ್ಷೆ",          // punishment
            "ದಂಡ",            // fine / penalty
            "ಜೈಲು",           // jail
            "ಬಂಧನ",           // arrest
            "ವಾರಂಟ್",         // warrant
            "ದೂರು",           // complaint
            "ವಿವಾದ",          // dispute
            "ಪರಿಹಾರ",         // compensation
            "ನಷ್ಟ",           // damages
            // Labour / employment
            "ವೇತನ",           // salary / wages
            "ಕೂಲಿ",           // daily wages
            "ಕಾರ್ಮಿಕ",        // worker
            "ಮಾಲೀಕ",          // employer
            "ನೌಕರ",           // employee
            "ವಜಾ",            // dismissal
            "ಹೆಚ್ಚುವರಿ ಸಮಯ",  // overtime
            "ಭವಿಷ್ಯ ನಿಧಿ",    // provident fund
            "ಮಾತೃತ್ವ",        // maternity
            // Property / family
            "ಬಾಡಿಗೆದಾರ",      // tenant
            "ಮನೆ ಮಾಲೀಕ",     // landlord
            "ವಿಚ್ಛೇದನ",       // divorce
            "ಪಾಲನೆ",          // custody
            "ಉತ್ತರಾಧಿಕಾರ",    // inheritance
            "ಆಸ್ತಿ",          // property
            "ವರದಕ್ಷಿಣೆ",      // dowry
            "ಗೃಹ ಹಿಂಸೆ",      // domestic violence
            // Consumer / other
            "ಮೋಸ",            // fraud
            "ಲಂಚ",            // bribe
            "ಭ್ರಷ್ಟಾಚಾರ",     // corruption
            "ರೇಷನ್",          // ration

            // ── Tamil (ta) ────────────────────────────────────────────────────
            // Statutory / rights
            "உரிமை",          // right
            "சட்டம்",         // law
            "சட்டப்பிரிவு",   // section of an act
            "நீதிமன்றம்",     // court
            "தீர்ப்பாயம்",    // tribunal
            "மேல்முறையீடு",   // appeal
            "மீறல்",          // violation
            "பாதுகாப்பு",     // protection
            // Offences / enforcement
            "தண்டனை",        // punishment / penalty
            "அபராதம்",        // fine
            "சிறைச்சாலை",    // jail
            "கைது",           // arrest
            "வாரண்ட்",        // warrant
            "புகார்",          // complaint
            "தகராறு",         // dispute
            "இழப்பீடு",       // compensation
            "சேதஈடு",        // damages
            // Labour / employment
            "ஊதியம்",         // salary / wages
            "கூலி",           // daily wages
            "தொழிலாளர்",     // worker
            "முதலாளி",        // employer
            "ஊழியர்",         // employee
            "பணிநீக்கம்",     // dismissal
            "கூடுதல் நேரம்",  // overtime
            "வருங்கால வைப்பு நிதி", // provident fund
            "மகப்பேறு",       // maternity
            // Property / family
            "குத்தகைதாரர்",   // tenant
            "வீட்டு உரிமையாளர்", // landlord
            "விவாகரத்து",     // divorce
            "காவல்",          // custody
            "வாரிசுரிமை",     // inheritance
            "சொத்து",         // property
            "வரதட்சணை",      // dowry
            "குடும்ப வன்முறை", // domestic violence
            // Consumer / other
            "மோசடி",          // fraud
            "இலஞ்சம்",        // bribe
            "ஊழல்",           // corruption
            "ரேஷன்",          // ration

            // ── Bengali (bn) ──────────────────────────────────────────────────
            // Statutory / rights
            "অধিকার",         // right
            "আইন",            // law
            "ধারা",           // section of an act
            "আদালত",          // court
            "ট্রাইব্যুনাল",   // tribunal
            "আপিল",           // appeal
            "লঙ্ঘন",          // violation
            "সুরক্ষা",        // protection
            // Offences / enforcement
            "শাস্তি",          // punishment
            "জরিমানা",        // fine
            "কারাগার",        // jail
            "গ্রেফতার",       // arrest
            "পরোয়ানা",       // warrant
            "অভিযোগ",        // complaint
            "বিরোধ",          // dispute
            "ক্ষতিপূরণ",      // compensation
            "ক্ষতি",          // damages
            // Labour / employment
            "বেতন",           // salary
            "মজুরি",          // wages
            "শ্রমিক",         // worker
            "নিয়োগকর্তা",    // employer
            "কর্মচারী",       // employee
            "বরখাস্ত",        // dismissal
            "অতিরিক্ত সময়",  // overtime
            "ভবিষ্যৎ তহবিল",  // provident fund
            "মাতৃত্ব",        // maternity
            // Property / family
            "ভাড়াটে",        // tenant
            "বাড়িওয়ালা",     // landlord
            "বিবাহবিচ্ছেদ",   // divorce
            "হেফাজত",         // custody
            "উত্তরাধিকার",    // inheritance
            "সম্পত্তি",       // property
            "যৌতুক",          // dowry
            "পারিবারিক সহিংসতা", // domestic violence
            // Consumer / other
            "প্রতারণা",       // fraud
            "ঘুষ",            // bribe
            "দুর্নীতি",       // corruption
            "রেশন",           // ration

            // ── Gujarati (gu) ─────────────────────────────────────────────────
            // Statutory / rights
            "અધિકાર",         // right
            "કાયદો",          // law
            "કલમ",            // section of an act
            "અદાલત",          // court
            "ન્યાયાધિકરણ",    // tribunal
            "અપીલ",           // appeal
            "ઉલ્લંઘન",        // violation
            "રક્ષણ",          // protection
            // Offences / enforcement
            "સજા",            // punishment
            "દંડ",            // fine / penalty
            "જેલ",            // jail
            "ધરપકડ",          // arrest
            "વૉરંટ",          // warrant
            "ફરિયાદ",         // complaint
            "વિવાદ",          // dispute
            "વળતર",           // compensation
            "નુકસાન",         // damages
            // Labour / employment
            "વેતન",           // salary
            "મજૂરી",          // wages
            "કામદાર",         // worker
            "માલિક",          // employer
            "કર્મચારી",       // employee
            "બરખાસ્ત",        // dismissal
            "ઓવરટાઇમ",       // overtime
            "ભવિષ્ય નિધિ",    // provident fund
            "માતૃત્વ",        // maternity
            // Property / family
            "ભાડૂત",          // tenant
            "મકાનમાલિક",     // landlord
            "છૂટાછેડા",       // divorce
            "વાલીપણ",         // custody
            "વારસો",          // inheritance
            "મિલકત",          // property
            "દહેજ",           // dowry
            "ઘરેલુ હિંસા",    // domestic violence
            // Consumer / other
            "છેતરપિંડી",      // fraud
            "લાંચ",           // bribe
            "ભ્રષ્ટાચાર",     // corruption
            "રેશન",           // ration

            // ── Marathi (mr) ──────────────────────────────────────────────────
            // Statutory / rights
            "अधिकार",         // right
            "कायदा",          // law
            "कलम",            // section of an act
            "न्यायालय",       // court
            "न्यायाधिकरण",   // tribunal
            "अपील",           // appeal
            "उल्लंघन",        // violation
            "संरक्षण",        // protection
            // Offences / enforcement
            "शिक्षा",          // punishment
            "दंड",            // fine / penalty
            "तुरुंग",         // jail
            "अटक",            // arrest
            "वॉरंट",          // warrant
            "तक्रार",         // complaint
            "वाद",            // dispute
            "नुकसानभरपाई",    // compensation
            "नुकसान",         // damages
            // Labour / employment
            "वेतन",           // salary
            "मजुरी",          // wages
            "कामगार",         // worker
            "मालक",           // employer
            "कर्मचारी",       // employee
            "बडतर्फ",         // dismissal
            "ओव्हरटाइम",     // overtime
            "भविष्य निर्वाह निधी", // provident fund
            "मातृत्व",        // maternity
            // Property / family
            "भाडेकरू",        // tenant
            "घरमालक",         // landlord
            "घटस्फोट",        // divorce
            "ताबा",           // custody
            "वारसा",          // inheritance
            "मालमत्ता",       // property
            "हुंडा",          // dowry
            "कौटुंबिक हिंसाचार", // domestic violence
            // Consumer / other
            "फसवणूक",         // fraud
            "लाच",            // bribe
            "भ्रष्टाचार",     // corruption
            "रेशन",           // ration

            // ── Odia (or) ─────────────────────────────────────────────────────
            // Statutory / rights
            "ଅଧିକାର",        // right
            "ଆଇନ",            // law
            "ଧାରା",           // section of an act
            "ଆଦାଲତ",         // court
            "ଟ୍ରାଇବ୍ୟୁନାଲ",  // tribunal
            "ଅପିଲ",           // appeal
            "ଉଲ୍ଲଂଘନ",        // violation
            "ସୁରକ୍ଷା",        // protection
            // Offences / enforcement
            "ଶାସ୍ତି",         // punishment
            "ଜୁର୍ମାନା",       // fine
            "ଜେଲ",            // jail
            "ଗିରଫ",           // arrest
            "ୱାରେଣ୍ଟ",        // warrant
            "ଅଭିଯୋଗ",        // complaint
            "ବିବାଦ",          // dispute
            "କ୍ଷତିପୂରଣ",     // compensation
            "କ୍ଷତି",          // damages
            // Labour / employment
            "ବେତନ",           // salary
            "ମଜୁରି",          // wages
            "ଶ୍ରମିକ",         // worker
            "ମାଲିକ",          // employer
            "କର୍ମଚାରୀ",      // employee
            "ବରଖାସ୍ତ",        // dismissal
            "ଅଧିକ ସମୟ",      // overtime
            "ଭବିଷ୍ୟ ନିଧି",   // provident fund
            "ମାତୃତ୍ୱ",        // maternity
            // Property / family
            "ଭଡ଼ାଟିଆ",        // tenant
            "ଗୃହ ମାଲିକ",     // landlord
            "ବିବାହ ବିଚ୍ଛେଦ", // divorce
            "ଅଭିରକ୍ଷା",      // custody
            "ଉତ୍ତରାଧିକାର",   // inheritance
            "ସଂପତ୍ତି",       // property
            "ଯୌତୁକ",         // dowry
            "ଘରୋଇ ହିଂସା",    // domestic violence
            // Consumer / other
            "ଠକାମି",          // fraud
            "ଘୁଷ",            // bribe
            "ଦୁର୍ନୀତି",       // corruption
            "ରେସନ",           // ration

            // ── Assamese (as) ─────────────────────────────────────────────────
            // Statutory / rights
            "অধিকাৰ",        // right
            "আইন",            // law
            "ধাৰা",           // section of an act
            "আদালত",          // court
            "ন্যায়াধিকৰণ",   // tribunal
            "আপীল",           // appeal
            "উল্লংঘন",        // violation
            "সুৰক্ষা",        // protection
            // Offences / enforcement
            "শাস্তি",          // punishment
            "জৰিমনা",         // fine
            "কাৰাগাৰ",        // jail
            "গ্ৰেপ্তাৰ",      // arrest
            "ৱাৰেণ্ট",        // warrant
            "অভিযোগ",        // complaint
            "বিবাদ",          // dispute
            "ক্ষতিপূৰণ",     // compensation
            "ক্ষতি",          // damages
            // Labour / employment
            "দৰমহা",          // salary
            "মজুৰি",          // wages
            "শ্ৰমিক",         // worker
            "নিয়োগকৰ্তা",   // employer
            "কৰ্মচাৰী",      // employee
            "বৰখাস্ত",        // dismissal
            "অতিৰিক্ত সময়",  // overtime
            "ভৱিষ্যৎ নিধি",   // provident fund
            "মাতৃত্ব",        // maternity
            // Property / family
            "ভাড়াতীয়া",     // tenant
            "গৃহস্বামী",      // landlord
            "বিবাহ বিচ্ছেদ",  // divorce
            "অভিৰক্ষা",      // custody
            "উত্তৰাধিকাৰ",   // inheritance
            "সম্পত্তি",       // property
            "যৌতুক",         // dowry
            "ঘৰেলু হিংসা",   // domestic violence
            // Consumer / other
            "প্ৰতাৰণা",       // fraud
            "ঘুচ",            // bribe
            "দুৰ্নীতি",       // corruption
            "ৰেচন",           // ration

            // ── Nepali (ne) ───────────────────────────────────────────────────
            // Statutory / rights
            "अधिकार",         // right
            "कानून",           // law
            "दफा",            // section of an act
            "अदालत",          // court
            "न्यायाधिकरण",   // tribunal
            "पुनरावेदन",      // appeal
            "उल्लंघन",        // violation
            "संरक्षण",        // protection
            // Offences / enforcement
            "सजाय",           // punishment
            "जरिमाना",        // fine
            "जेल",            // jail
            "पक्राउ",         // arrest
            "पक्राउ पूर्जी",  // warrant
            "गुनासो",         // complaint
            "विवाद",          // dispute
            "मुआवजा",         // compensation
            "हर्जाना",         // damages
            // Labour / employment
            "तलब",            // salary
            "ज्याला",         // wages
            "मजदूरी",         // wages (colloquial)
            "रोजगारदाता",     // employer
            "कर्मचारी",       // employee
            "श्रमिक",         // worker
            "बर्खास्त",       // dismissal
            "ओभरटाइम",       // overtime
            "भविष्य कोष",     // provident fund
            "मातृत्व",        // maternity
            // Property / family
            "भाडावाल",        // tenant
            "घरधनी",          // landlord
            "सम्बन्धविच्छेद", // divorce
            "अभिरक्षा",       // custody
            "उत्तराधिकार",    // inheritance
            "सम्पत्ति",       // property
            "दाइजो",          // dowry
            "घरेलु हिंसा",    // domestic violence
            // Consumer / other
            "ठगी",            // fraud
            "घुस",            // bribe
            "भ्रष्टाचार",     // corruption
            "राशन",           // ration
        )
    }
}
