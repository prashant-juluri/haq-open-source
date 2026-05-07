package com.haq.app

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
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
            delay(300) // allow TTS to release audio focus
            _appState.value = AppState.LISTENING
            try {
                val langTag = _activeLanguage.value
                Log.d("Haq/VM", "Mic pressed: activeLanguage=$langTag profile=${activeProfile?.name}")
                val transcript = STTManager.recordAndTranscribe(getApplication(), langTag)
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

            // Build two queries: one with last-exchange history, one without.
            // The fallback is used if the first attempt fails before emitting
            // any tokens — the signature of a KV cache overflow during prefill.
            val baseQuery = buildString {
                append("IMPORTANT: Respond ONLY in $languageName. ")
                append("Do not use any other language. ")
                activeProfile?.takeIf { it.isOnboarded }?.let { p ->
                    append("User: ${p.name}, ")
                    append("State: ${p.state}, ")
                    append("Category: ${p.casteCategory}, ")
                    append("Occupation: ${p.occupation}. ")
                }
                append("Question: $prompt")
            }

            val hasHistory = activeProfile?.lastQuery?.isNotBlank() == true &&
                activeProfile?.lastResponse?.isNotBlank() == true

            val contextualQuery = if (hasHistory) {
                val p = activeProfile!!
                val fixedText = buildString {
                    append("IMPORTANT: Respond ONLY in $languageName. ")
                    append("Do not use any other language. ")
                    append("User: ${p.name}, State: ${p.state}, ")
                    append("Category: ${p.casteCategory}, Occupation: ${p.occupation}. ")
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
                    append("User: ${p.name}, State: ${p.state}, ")
                    append("Category: ${p.casteCategory}, Occupation: ${p.occupation}. ")
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
                if (sentence.length >= 15) {
                    TTSManager.speak(
                        text = sentence,
                        languageCode = langCode,
                        queueMode = TextToSpeech.QUEUE_ADD,
                        onOutputError = {
                            Log.w("Haq/VM", "Sentence speak failed for $langCode — skipping")
                        },
                    )
                }
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
        if (remaining.length >= 5) {
            TTSManager.speak(
                text = remaining,
                languageCode = langCode,
                queueMode = TextToSpeech.QUEUE_ADD,
                onOutputError = {
                    Log.w("Haq/VM", "Remaining speak failed for $langCode — skipping")
                },
            )
        }
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
        TTSManager.shutdown()
        GemmaManager.shutdown()
    }

    companion object {
        // Must match LiteRTEngine.MAX_TOKENS (2048).
        private const val KV_CACHE_TOKENS = 2048
        // Tokens reserved for Gemma's output — tighten if responses feel truncated,
        // loosen if KV overflow errors appear.
        private const val RESPONSE_RESERVE_TOKENS = 600
        // System prompt token count measured from prefill logs.
        private const val SYSTEM_PROMPT_TOKENS = 70
    }
}
