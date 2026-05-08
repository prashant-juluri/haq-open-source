package com.haq.app.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haq.app.data.ProfileManager
import com.haq.app.data.SessionManager
import com.haq.app.inference.GemmaManager
import com.haq.app.stt.STTManager
import com.haq.app.tts.TTSManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class OnboardingStep {
    object PreparingVoices       : OnboardingStep()  // pre-onboarding voice readiness gate
    object LanguageSelect        : OnboardingStep()
    object Introduction          : OnboardingStep()
    object AskName               : OnboardingStep()
    object AskState              : OnboardingStep()
    object AskCaste              : OnboardingStep()
    object AskOccupation         : OnboardingStep()
    object InstallingVoicePacks  : OnboardingStep()  // edge case: single language still missing
    object Complete              : OnboardingStep()
}

enum class OnboardingListenState { IDLE, LISTENING, PROCESSING }

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ProfileManager.init(application)
        // LanguageSelect is shown immediately — no voice pre-loading.
        // Readiness gating happens after the user picks a language.
    }

    // ── Step state ───────────────────────────────────────────────────────────────

    private val _step = MutableStateFlow<OnboardingStep>(OnboardingStep.LanguageSelect)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _listenState = MutableStateFlow(OnboardingListenState.IDLE)
    val listenState: StateFlow<OnboardingListenState> = _listenState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _createdProfileId = MutableStateFlow(-1)
    val createdProfileId: StateFlow<Int> = _createdProfileId.asStateFlow()

    // Incremented each time a new question is ready for mic input.
    // MainActivity observes this and calls onMicPressed() automatically.
    private val _micActivationEvent = MutableStateFlow(0)
    val micActivationEvent: StateFlow<Int> = _micActivationEvent.asStateFlow()

    // Languages confirmed available by TTS engine on this device.
    // Computed when voice readiness polling completes.
    private val _supportedLanguages = MutableStateFlow(listOf("hi", "te", "ml", "kn", "ta", "bn", "gu", "mr", "or", "as", "ne", "en"))
    val supportedLanguages: StateFlow<List<String>> = _supportedLanguages.asStateFlow()

    // Status shown on PreparingVoices screen. Updated as model/voice download progresses.
    private val _preparingStatus = MutableStateFlow("Preparing...")
    val preparingStatus: StateFlow<String> = _preparingStatus.asStateFlow()

    // ── Data collected during onboarding ────────────────────────────────────────
    var selectedLanguage    = "hi"
    var collectedName       = ""
    var collectedState      = ""
    var collectedCaste      = ""
    var collectedOccupation = ""

    // Consecutive empty/error count for the current question
    private var retryCount = 0

    // Active voice readiness polling coroutine (fallback only)
    private var voicePollingJob: Job? = null

    // ── Voice + model readiness (single language) ───────────────────────────────

    /**
     * Attempts a silent test utterance for [languageCode]. Returns true when synthesis
     * succeeds. On failure clears the cached voice so [TTSManager.findBestVoice] re-scans
     * on the next attempt. Does NOT reinitialise the engine — that would interrupt any
     * in-progress Google TTS background download.
     */
    private suspend fun verifySingleVoiceSpeakable(languageCode: String): Boolean {
        val ok = TTSManager.testSpeak(languageCode)
        Log.d("Haq/Onboard", "verifySingleVoiceSpeakable: testSpeak($languageCode)=$ok")
        if (ok) return true
        Log.w("Haq/Onboard", "voice data missing for lang=$languageCode — waiting for silent download")
        TTSManager.clearCachedVoice(languageCode)
        return false
    }

    /**
     * Called by MainActivity when ACTION_TTS_DATA_INSTALLED fires — Google TTS has finished
     * writing the selected language's voice pack to disk. Always reinitialises TTS so the
     * engine picks up the new data files regardless of which step we are on.
     *
     * - PreparingVoices: re-runs the testSpeak gate and advances to Introduction if ready.
     * - Any other step: reinit runs silently so subsequent speak() calls (onboarding
     *   questions or Gemma responses) pick up the newly downloaded voice without a restart.
     *   This handles the case where the escape hatch fired before the download finished.
     */
    fun onTtsDataInstalled() {
        Log.d("Haq/Onboard",
            "onTtsDataInstalled — reinitialising TTS for $selectedLanguage (step=${_step.value})")
        viewModelScope.launch {
            TTSManager.reinitialiseAndWait()
            TTSManager.clearCachedVoice(selectedLanguage)

            if (_step.value is OnboardingStep.PreparingVoices ||
                _step.value is OnboardingStep.InstallingVoicePacks) {
                val modelReady = GemmaManager.isModelReady(getApplication())
                if (!modelReady) {
                    Log.d("Haq/Onboard", "TTS data installed but model not ready — poll loop will pick it up")
                    return@launch
                }
                if (verifySingleVoiceSpeakable(selectedLanguage)) {
                    voicePollingJob?.cancel()
                    voicePollingJob = null
                    enterIntroduction()
                }
                // If testSpeak still fails the outer poll continues retrying every 30 s.
            } else {
                // Beyond PreparingVoices (escape hatch already fired, or main app).
                // Voice is now reinitialised — subsequent speak() calls will find the
                // newly downloaded voice via findBestVoice() without an app restart.
                val voiceReady = TTSManager.checkLanguageSupport(selectedLanguage)
                Log.d("Haq/Onboard",
                    "onTtsDataInstalled: step=${_step.value} — silent reinit done, " +
                    "$selectedLanguage voiceReady=$voiceReady")

                // If the escape hatch fired before voice data arrived, onboarding
                // questions were asked silently. Now that voice is ready, re-speak
                // the current question so the user knows what to answer.
                // Only re-trigger when the mic is IDLE — if the user is already
                // speaking their answer, the next question will be audible anyway.
                if (_listenState.value == OnboardingListenState.IDLE) {
                    val questionText: String? = when (_step.value) {
                        is OnboardingStep.Introduction,
                        is OnboardingStep.AskName       -> getIntroductionText(selectedLanguage)
                        is OnboardingStep.AskState      -> getAskStateText(selectedLanguage)
                        is OnboardingStep.AskCaste      -> getAskCasteText(selectedLanguage)
                        is OnboardingStep.AskOccupation -> getAskOccupationText(selectedLanguage)
                        else                            -> null
                    }
                    if (questionText != null) {
                        Log.d("Haq/Onboard",
                            "onTtsDataInstalled: re-speaking question for step=${_step.value}")
                        speakOnboarding(text = questionText, languageCode = selectedLanguage)
                        _micActivationEvent.value++
                    }
                }
            }
        }
    }

    /**
     * Triggers a silent download for [languageCode], then polls until both the model
     * file and the selected language's voice data are confirmed ready. Only then
     * advances to Introduction — never with a silent voice.
     *
     * ### Download strategy
     * When the voice is **missing from the engine list** (`checkLanguageSupport` false):
     * `ensureVoiceDownloading()` calls `setLanguage()` every 5 s to keep Google TTS
     * aware it is needed.
     *
     * When the voice **appears in the list but synthesis fails** (`testSpeak` returns
     * false / -4): the failure itself signals to Google TTS that the data is incomplete
     * and a background download begins. Calling `speak()` again every 5 s can restart
     * or interrupt that download. So after the first `testSpeak` failure we back off
     * to a 30 s check interval and rely on `ACTION_TTS_DATA_INSTALLED` (received by
     * [onTtsDataInstalled]) as the primary completion signal.
     */
    private fun startSingleLanguageReadinessPolling(languageCode: String) {
        voicePollingJob?.cancel()
        voicePollingJob = viewModelScope.launch {
            // Initial trigger — idempotent if voice is already present.
            TTSManager.ensureVoiceDownloading(languageCode)

            // Counter of 5 s cycles to wait before the next testSpeak() call.
            // 0 = test immediately, N > 0 = wait N more cycles.
            var testSpeakCountdown = 0
            // Consecutive testSpeak failures. Resets when voice goes missing (re-appears after
            // re-download) or model becomes unready.
            var testSpeakFailures = 0
            // Show InstallingVoicePacks after this many 30 s testSpeak failures (~3 min).
            val escapeAfterFailures = 6

            while (true) {
                val modelReady   = GemmaManager.isModelReady(getApplication())
                val voiceMissing = !TTSManager.checkLanguageSupport(languageCode)
                updatePreparingStatus(voiceMissing, !modelReady)
                Log.d("Haq/Onboard",
                    "Single-lang readiness poll: lang=$languageCode " +
                    "modelReady=$modelReady voiceMissing=$voiceMissing " +
                    "testSpeakIn=${testSpeakCountdown * 5}s")

                if (modelReady) {
                    if (voiceMissing) {
                        // Voice entry absent — re-signal download intent via setLanguage.
                        TTSManager.ensureVoiceDownloading(languageCode)
                        testSpeakCountdown = 0  // test immediately once it appears
                        testSpeakFailures  = 0
                    } else {
                        // Voice entry present — check synthesability via speak(), but at
                        // most once per 30 s. Repeated speak() calls while Google TTS is
                        // downloading the same voice can restart/interrupt the download.
                        // NOTE: ACTION_TTS_DATA_INSTALLED is only sent for user-initiated
                        // installs (Settings → TTS), NOT for silent background downloads
                        // triggered by speak(). We therefore poll here as the only signal.
                        if (testSpeakCountdown <= 0) {
                            testSpeakCountdown = 6  // next check in 6 × 5 s = 30 s
                            if (verifySingleVoiceSpeakable(languageCode)) {
                                enterIntroduction()
                                break
                            }
                            testSpeakFailures++
                            Log.d("Haq/Onboard",
                                "testSpeak($languageCode) failed " +
                                "(failure $testSpeakFailures/$escapeAfterFailures) — " +
                                "download in progress; next check in 30 s")

                            // After ~3 minutes of failed synthesis, surface the
                            // InstallingVoicePacks screen so the user sees progress and
                            // has a Continue option. Background polling continues — if
                            // the download finishes, onTtsDataInstalled() advances directly.
                            if (testSpeakFailures >= escapeAfterFailures &&
                                _step.value is OnboardingStep.PreparingVoices) {
                                Log.w("Haq/Onboard",
                                    "testSpeak failed $testSpeakFailures times — " +
                                    "surfacing InstallingVoicePacks escape screen")
                                _step.value = OnboardingStep.InstallingVoicePacks
                            }
                        } else {
                            testSpeakCountdown--
                        }
                    }
                } else {
                    testSpeakCountdown = 0
                    testSpeakFailures  = 0
                }

                // Keep polling on both PreparingVoices and InstallingVoicePacks —
                // the background download may complete while the escape screen is shown.
                val currentStep = _step.value
                if (currentStep !is OnboardingStep.PreparingVoices &&
                    currentStep !is OnboardingStep.InstallingVoicePacks) break

                delay(5_000L)
            }
            voicePollingJob = null
        }
    }

    private fun updatePreparingStatus(voiceMissing: Boolean, modelMissing: Boolean) {
        _preparingStatus.value = when {
            modelMissing && voiceMissing -> "Downloading model and voices..."
            modelMissing                 -> "Downloading AI model..."
            else                         -> "Preparing voices..."
        }
    }

    // ── Question text helpers ────────────────────────────────────────────────────

    fun getIntroductionText(language: String): String = when (language) {
        "te" -> "నమస్కారం. నేను హక్ — మీ హక్కుల నావిగేటర్. నేను మీకు ప్రభుత్వ సంక్షేమ పథకాల గురించి సహాయం చేస్తాను. మీ పేరు ఏమిటి?"
        "ml" -> "നമസ്കാരം. ഞാൻ ഹഖ് — നിങ്ങളുടെ അവകാശ നാവിഗേറ്റർ. ഞാൻ നിങ്ങൾക്ക് സർക്കാർ ക്ഷേമ പദ്ധതികളെ കുറിച്ച് സഹായിക്കും. നിങ്ങളുടെ പേര് എന്താണ്?"
        "kn" -> "ನಮಸ್ಕಾರ. ನಾನು ಹಕ್ — ನಿಮ್ಮ ಹಕ್ಕುಗಳ ನ್ಯಾವಿಗೇಟರ್. ನಾನು ನಿಮಗೆ ಸರ್ಕಾರಿ ಕಲ್ಯಾಣ ಯೋಜನೆಗಳ ಬಗ್ಗೆ ಸಹಾಯ ಮಾಡುತ್ತೇನೆ. ನಿಮ್ಮ ಹೆಸರು ಏನು?"
        "ta" -> "வணக்கம். நான் ஹக் — உங்கள் உரிமைகள் வழிகாட்டி. நான் உங்களுக்கு அரசு நல திட்டங்களில் உதவுவேன். உங்கள் பெயர் என்ன?"
        "bn" -> "নমস্কার। আমি হক — আপনার অধিকার নেভিগেটর। আমি আপনাকে সরকারি কল্যাণ প্রকল্পে সহায়তা করব। আপনার নাম কী?"
        "gu" -> "નમસ્તે. હું હક — તમારા અધિકારોનો નેવિગેટર. હું તમને સરકારી કલ્યાણ યોજનાઓ અંગે મદદ કરીશ. તમારું નામ શું છે?"
        "mr" -> "नमस्कार. मी हक — तुमचा हक्क नेव्हिगेटर. मी तुम्हाला सरकारी कल्याण योजनांबद्दल मदद करेन. तुमचे नाव काय आहे?"
        "or" -> "ନମସ୍କାର। ମୁଁ ହକ — ଆପଣଙ୍କ ଅଧିକାର ନେଭିଗେଟର। ମୁଁ ଆପଣଙ୍କୁ ସରକାରୀ କଲ୍ୟାଣ ଯୋଜନା ବିଷୟରେ ସାହାଯ୍ୟ କରିବି। ଆପଣଙ୍କ ନାମ କ'ଣ?"
        "as" -> "নমস্কাৰ। মই হক — আপোনাৰ অধিকাৰ নেভিগেটৰ। মই আপোনাক চৰকাৰী কল্যাণ আঁচনিৰ বিষয়ে সহায় কৰিম। আপোনাৰ নাম কি?"
        "ne" -> "नमस्ते। म हक — तपाईंको अधिकार नेभिगेटर। म तपाईंलाई सरकारी कल्याण योजनाहरूमा मद्दत गर्नेछु। तपाईंको नाम के हो?"
        "en" -> "Hello. I am Haq — your rights navigator. I help you claim government welfare schemes you are entitled to. What is your name?"
        else -> "नमस्ते। मैं हक़ हूँ — आपका अधिकार नेविगेटर। मैं आपको सरकारी कल्याण योजनाओं का लाभ दिलाने में मदद करूँगा। आपका नाम क्या है?"
    }

    fun getAskStateText(language: String): String = when (language) {
        "te" -> "$collectedName, మీరు ఏ రాష్ట్రంలో నివసిస్తున్నారు?"
        "ml" -> "$collectedName, നിങ്ങൾ ഏത് സംസ്ഥാനത്താണ് താമസിക്കുന്നത്?"
        "kn" -> "$collectedName, ನೀವು ಯಾವ ರಾಜ್ಯದಲ್ಲಿ ವಾಸಿಸುತ್ತೀರಿ?"
        "ta" -> "$collectedName, நீங்கள் எந்த மாநிலத்தில் வாழ்கிறீர்கள்?"
        "bn" -> "$collectedName, আপনি কোন রাজ্যে থাকেন?"
        "gu" -> "$collectedName, તમે કયા રાજ્યમાં રહો છો?"
        "mr" -> "$collectedName, तुम्ही कोणत्या राज्यात राहता?"
        "or" -> "$collectedName, ଆପଣ କେଉଁ ରାଜ୍ୟରେ ରୁହନ୍ତି?"
        "as" -> "$collectedName, আপোনি কোন ৰাজ্যত থাকে?"
        "ne" -> "$collectedName, तपाईं कुन राज्यमा बस्नुहुन्छ?"
        "en" -> "$collectedName, which state do you live in?"
        else -> "$collectedName, आप किस राज्य में रहते हैं?"
    }

    fun getAskCasteText(language: String): String = when (language) {
        "te" -> "మీరు SC, ST, OBC, లేదా General వర్గానికి చెందిన వారా?"
        "ml" -> "നിങ്ങൾ SC, ST, OBC, അല്ലെങ്കിൽ General വിഭാഗത്തിൽ പെടുന്നുവോ?"
        "kn" -> "ನೀವು SC, ST, OBC ಅಥವಾ General ವರ್ಗಕ್ಕೆ ಸೇರಿದ್ದೀರಾ?"
        "ta" -> "நீங்கள் SC, ST, OBC அல்லது General பிரிவைச் சேர்ந்தவரா?"
        "bn" -> "আপনি কি SC, ST, OBC বা General বিভাগের অন্তর্গত?"
        "gu" -> "તમે SC, ST, OBC અથવા General વર્ગના છો?"
        "mr" -> "तुम्ही SC, ST, OBC किंवा General प्रवर्गातील आहात का?"
        "or" -> "ଆପଣ SC, ST, OBC ବା General ବର୍ଗର?"
        "as" -> "আপোনি SC, ST, OBC বা General শ্ৰেণীৰ নেকি?"
        "ne" -> "तपाईं SC, ST, OBC वा General वर्गका हुनुहुन्छ?"
        "en" -> "Do you belong to SC, ST, OBC, or General category?"
        else -> "आप SC, ST, OBC, या General श्रेणी में से किसमें आते हैं?"
    }

    fun getAskOccupationText(language: String): String = when (language) {
        "te" -> "మీరు ఏమి పని చేస్తారు?"
        "ml" -> "നിങ്ങൾ എന്ത് ജോലി ചെയ്യുന്നു?"
        "kn" -> "ನೀವು ಏನು ಕೆಲಸ ಮಾಡುತ್ತೀರಿ?"
        "ta" -> "நீங்கள் என்ன வேலை செய்கிறீர்கள்?"
        "bn" -> "আপনি কী কাজ করেন?"
        "gu" -> "તમે શું કામ કરો છો?"
        "mr" -> "तुम्ही काय काम करता?"
        "or" -> "ଆପଣ କ'ଣ କାମ କରନ୍ତି?"
        "as" -> "আপোনি কি কাম কৰে?"
        "ne" -> "तपाईं के काम गर्नुहुन्छ?"
        "en" -> "What work do you do?"
        else -> "आप क्या काम करते हैं?"
    }

    fun getCompletionText(language: String): String = when (language) {
        "te" -> "అన్నీ సెట్ అయ్యాయి. మైక్ నొక్కి ఏదైనా అడగండి."
        "ml" -> "എല്ലാം സജ്ജമായി. മൈക്ക് ടാപ്പ് ചെയ്ത് ചോദിക്കൂ."
        "kn" -> "ಎಲ್ಲಾ ಸಿದ್ಧವಾಗಿದೆ. ಮೈಕ್ ಒತ್ತಿ ಏನಾದರೂ ಕೇಳಿ."
        "ta" -> "அனைத்தும் தயாராகிவிட்டது. மைக்கை அழுத்தி கேளுங்கள்."
        "bn" -> "সব প্রস্তুত। মাইক চাপুন এবং যেকোনো প্রশ্ন করুন।"
        "gu" -> "બધું તૈયાર છે. માઇક દબાવો અને કંઈ પૂછો."
        "mr" -> "सर्व तयार आहे. मायक दाबा आणि काहीही विचारा."
        "or" -> "ସବୁ ପ୍ରସ୍ତୁତ। ମାଇକ୍ ଦବାନ୍ତୁ ଏବଂ ଯାହା ପଚାରିବେ ପଚାରନ୍ତୁ।"
        "as" -> "সকলো সাজু। মাইক টিপক আৰু যিকোনো প্ৰশ্ন সুধক।"
        "ne" -> "सबै तयार छ। माइक थिच्नुस् र केही सोध्नुस्।"
        "en" -> "All set. Tap the mic and ask anything."
        else -> "सब तैयार है। माइक दबाएं और कुछ भी पूछें।"
    }

    // ── Private TTS + mic chain driver ───────────────────────────────────────────

    /**
     * Enters the Introduction step, speaks the introduction text via [speakOnboarding],
     * then calls [onIntroductionComplete] to advance the flow. All code paths that enter
     * Introduction must call this — never set _step = Introduction directly.
     */
    private fun enterIntroduction() {
        _step.value = OnboardingStep.Introduction
        viewModelScope.launch {
            speakOnboarding(
                text = getIntroductionText(selectedLanguage),
                languageCode = selectedLanguage,
            )
            onIntroductionComplete()
        }
    }

    /**
     * Speaks the completion message via [speakOnboarding], then calls [onDone].
     * Called by MainActivity when the Complete step arrives.
     */
    fun speakCompletion(onDone: () -> Unit) {
        viewModelScope.launch {
            speakOnboarding(
                text = getCompletionText(selectedLanguage),
                languageCode = selectedLanguage,
            )
            onDone()
        }
    }

    /**
     * Speaks [text] in [languageCode] with up to 4 automatic retries on ERROR_OUTPUT (-4).
     *
     * On the first failure, reinitialises TTS once and retries. Subsequent failures just
     * clear the voice cache and wait 500 ms — repeated reinits don't fix missing voice data,
     * only waste ~7 s each.
     *
     * Step management during reinit:
     * - Introduction step only: shows PreparingVoices while reinit runs, then restores to
     *   Introduction. At Introduction entry `micActivationEvent == 0`, so no spurious mic
     *   activation happens on the re-compose.
     * - All other steps (AskState, AskCaste, …): reinit runs silently without changing the
     *   step. Changing the step would unmount/remount ConversationOnboardingScreen and
     *   re-fire LaunchedEffect(micActivationEvent) with the current (non-zero) value,
     *   spuriously triggering STT before the question is spoken.
     */
    private suspend fun speakOnboarding(text: String, languageCode: String) {
        val maxRetries = 4
        val stepOnEntry = _step.value
        var reinitDone = false

        repeat(maxRetries) { attempt ->
            Log.d("Haq/Onboard", "speakOnboarding attempt ${attempt + 1}/$maxRetries lang=$languageCode")
            val success = suspendCancellableCoroutine<Boolean> { cont ->
                TTSManager.speak(
                    text = text,
                    languageCode = languageCode,
                    onComplete = { if (cont.isActive) cont.resume(true) },
                    onOutputError = { if (cont.isActive) cont.resume(false) },
                )
            }
            if (success) return

            // Only reinit when a real (non-stub) voice was used but returned -4.
            // If there is no real voice at all (checkLanguageSupport == false),
            // reinit won't produce one — it just wastes ~4 s and forces another
            // 4-second freeze between every question.
            val hasRealVoice = TTSManager.checkLanguageSupport(languageCode)
            if (!reinitDone && hasRealVoice) {
                Log.w("Haq/Onboard", "speakOnboarding failed attempt ${attempt + 1} — reinitialising TTS (once)")
                _preparingStatus.value = when (languageCode) {
                    "te" -> "తెలుగు స్వరం సిద్ధమవుతోంది..."
                    "hi" -> "हिंदी आवाज़ तैयार हो रही है..."
                    "ml" -> "മലയാളം ശബ്ദം തയ്യാറാകുന്നു..."
                    "kn" -> "ಕನ್ನಡ ಧ್ವನಿ ಸಿದ್ಧವಾಗುತ್ತಿದೆ..."
                    "ta" -> "தமிழ் குரல் தயாராகிறது..."
                    "bn" -> "বাংলা কণ্ঠস্বর প্রস্তুত হচ্ছে..."
                    "gu" -> "ગુજરાતી અવાજ તૈયાર થઈ રહ્યો છે..."
                    "mr" -> "मराठी आवाज तयार होत आहे..."
                    "or" -> "ଓଡ଼ିଆ ସ୍ୱର ପ୍ରସ୍ତୁତ ହେଉଛି..."
                    "as" -> "অসমীয়া মাত প্ৰস্তুত হৈ আছে..."
                    "ne" -> "नेपाली आवाज तयार हुँदैछ..."
                    else -> "Preparing English voice..."
                }
                // Only flash the PreparingVoices screen for Introduction. For question
                // steps the screen must stay stable — unmounting it re-fires
                // LaunchedEffect(micActivationEvent) on re-mount, triggering STT early.
                if (stepOnEntry is OnboardingStep.Introduction) {
                    _step.value = OnboardingStep.PreparingVoices
                }
                TTSManager.reinitialiseAndWait()
                TTSManager.clearCachedVoice(languageCode)
                if (stepOnEntry is OnboardingStep.Introduction) {
                    _step.value = OnboardingStep.Introduction
                }
                reinitDone = true
            } else {
                // No real voice or reinit already done — skip the ~4 s reinit cycle.
                // Just clear the cache so findBestVoice() re-scans on the next attempt.
                Log.w("Haq/Onboard",
                    "speakOnboarding failed attempt ${attempt + 1} — skipping reinit " +
                    "(hasRealVoice=$hasRealVoice reinitDone=$reinitDone)")
                TTSManager.clearCachedVoice(languageCode)
                delay(200L)
            }
        }
        Log.e("Haq/Onboard", "speakOnboarding exhausted $maxRetries attempts for $languageCode — giving up")
    }

    /**
     * Derives the question text from the current step, resets listening state,
     * speaks the question with -4 recovery, then increments [_micActivationEvent]
     * so MainActivity auto-activates the mic once TTS finishes.
     */
    private fun startNextQuestion() {
        val questionText = when (_step.value) {
            is OnboardingStep.AskState      -> getAskStateText(selectedLanguage)
            is OnboardingStep.AskCaste      -> getAskCasteText(selectedLanguage)
            is OnboardingStep.AskOccupation -> getAskOccupationText(selectedLanguage)
            else -> return
        }
        _isListening.value = false
        _listenState.value = OnboardingListenState.IDLE
        viewModelScope.launch {
            speakOnboarding(text = questionText, languageCode = selectedLanguage)
            _micActivationEvent.value++
        }
    }

    fun getRetryText(language: String): String = when (language) {
        "te" -> "నేను వినలేదు. దయచేసి మళ్ళీ చెప్పండి."
        "ml" -> "എനിക്ക് കേൾക്കാൻ കഴിഞ്ഞില്ല. ദയവായി വീണ്ടും പറയൂ."
        "kn" -> "ನನಗೆ ಕೇಳಿಸಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಹೇಳಿ."
        "ta" -> "எனக்கு கேட்கவில்லை. மீண்டும் சொல்லுங்கள்."
        "bn" -> "আমি শুনতে পাইনি। আবার বলুন।"
        "gu" -> "મને સંભળાયું નહીં. ફરી કહો."
        "mr" -> "मला ऐकू आले नाही. पुन्हा सांगा."
        "or" -> "ମୁଁ ଶୁଣି ପାରିଲି ନାହିଁ। ପୁଣି କୁହନ୍ତୁ।"
        "as" -> "মই শুনা নাপালোঁ। আকৌ কওক।"
        "ne" -> "मैले सुनिनँ। फेरि भन्नुस्।"
        "en" -> "I didn't catch that. Please say it again."
        else -> "मुझे सुनाई नहीं दिया। कृपया फिर से बोलें।"
    }

    fun getTapMicText(language: String): String = when (language) {
        "te" -> "దయచేసి మైక్ బటన్ నొక్కి మాట్లాడండి."
        "ml" -> "ദയവായി മൈക്ക് ബട്ടൺ അമർത്തി സംസാരിക്കൂ."
        "kn" -> "ದಯವಿಟ್ಟು ಮೈಕ್ ಬಟನ್ ಒತ್ತಿ ಮಾತನಾಡಿ."
        "ta" -> "மைக் பொத்தானை அழுத்தி பேசுங்கள்."
        "bn" -> "মাইক বোতাম চাপুন এবং কথা বলুন।"
        "gu" -> "કૃપા કરીને માઇક બટન દબાવો અને બોલો."
        "mr" -> "कृपया मायक बटण दाबा आणि बोला."
        "or" -> "ଦୟାକରି ମାଇକ୍ ବଟନ୍ ଦବାନ୍ତୁ ଏବଂ କୁହନ୍ତୁ।"
        "as" -> "অনুগ্ৰহ কৰি মাইক বুটাম টিপক আৰু কথা কওক।"
        "ne" -> "कृपया माइक बटन थिच्नुस् र बोल्नुस्।"
        "en" -> "Please tap the mic button and speak."
        else -> "कृपया माइक बटन दबाकर बोलें।"
    }

    // ── Step transitions ─────────────────────────────────────────────────────────

    /**
     * Called when the user taps a language on the LanguageSelect screen. Saves the choice,
     * then gates on model availability and the single selected language's voice pack.
     *
     * Fast path (returning users, model + voice already present): enters Introduction
     * within one poll cycle (~1 s).
     * Slow path (first launch): shows PreparingVoices and polls until both are ready.
     */
    fun selectLanguage(language: String) {
        selectedLanguage = language
        Log.d("Haq/Onboard", "selectLanguage: lang=$language — starting single-language readiness gate")
        _step.value = OnboardingStep.PreparingVoices
        startSingleLanguageReadinessPolling(language)
    }

    fun onIntroductionComplete() {
        _step.value = OnboardingStep.AskName
        // Introduction TTS already asked the name question — activate mic immediately.
        _micActivationEvent.value++
    }

    fun submitName(name: String) {
        if (name.isBlank()) return
        collectedName = name.trim()
        _step.value = OnboardingStep.AskState
        startNextQuestion()
    }

    fun submitState(state: String) {
        if (state.isBlank()) return
        collectedState = state.trim()
        _step.value = OnboardingStep.AskCaste
        startNextQuestion()
    }

    fun submitCaste(caste: String) {
        if (caste.isBlank()) return
        collectedCaste = when {
            caste.contains("SC", ignoreCase = true) ||
            caste.contains("scheduled caste", ignoreCase = true) -> "SC"
            caste.contains("ST", ignoreCase = true) ||
            caste.contains("scheduled tribe", ignoreCase = true) -> "ST"
            caste.contains("OBC", ignoreCase = true) ||
            caste.contains("other backward", ignoreCase = true)  -> "OBC"
            else -> "General"
        }
        _step.value = OnboardingStep.AskOccupation
        startNextQuestion()
    }

    /**
     * Routes a non-blank transcript to the correct step handler.
     * Passes the transcript through Gemma first to extract the structured
     * value from natural-language input ("My name is Prashant" → "Prashant").
     * Falls back to the raw transcript if extraction fails or times out.
     */
    suspend fun submitAnswer(transcript: String) {
        retryCount = 0
        val step = _step.value
        val extracted = extractForStep(step, transcript)
        when (step) {
            is OnboardingStep.AskName       -> submitName(extracted)
            is OnboardingStep.AskState      -> submitState(extracted)
            is OnboardingStep.AskCaste      -> submitCaste(extracted)
            is OnboardingStep.AskOccupation -> submitOccupation(extracted)
            else -> {}
        }
    }

    /**
     * Builds a terse extraction prompt for [step] and runs it through Gemma.
     * The prompt is placed entirely in the user turn — the system prompt is not
     * changed — so Gemma sees the task instructions as an explicit override.
     * Returns [transcript] unchanged if the engine is not ready or extraction fails.
     */
    private suspend fun extractForStep(step: OnboardingStep, transcript: String): String {
        if (!GemmaManager.isModelReady()) return transcript
        val prompt = when (step) {
            is OnboardingStep.AskName ->
                "TASK: Extract the person's name from their reply.\n" +
                "Reply: \"$transcript\"\n" +
                "Output the name only. Nothing else."

            is OnboardingStep.AskState ->
                "TASK: Extract the Indian state name from their reply.\n" +
                "Reply: \"$transcript\"\n" +
                "Output the state name only. Nothing else."

            is OnboardingStep.AskCaste ->
                "TASK: Identify the caste category from their reply.\n" +
                "Reply: \"$transcript\"\n" +
                "Output exactly one of: SC, ST, OBC, General. Nothing else."

            is OnboardingStep.AskOccupation ->
                "TASK: Extract the occupation from their reply and translate it to English.\n" +
                "Reply: \"$transcript\"\n" +
                "Output the occupation in English only. Nothing else. Examples: farmer, construction worker, teacher, daily wage labourer, weaver."

            else -> return transcript
        }
        return GemmaManager.extractField(prompt, fallback = transcript)
    }

    fun submitOccupation(occupation: String) {
        if (occupation.isBlank()) return
        collectedOccupation = occupation.trim()

        // Safety net: if name was somehow missed, loop back
        if (collectedName.isBlank()) {
            _step.value = OnboardingStep.AskName
            return
        }

        viewModelScope.launch {
            val id = ProfileManager.createProfile(
                name          = collectedName,
                language      = selectedLanguage,
                state         = collectedState,
                casteCategory = collectedCaste,
                occupation    = collectedOccupation,
            )
            SessionManager.setActiveProfileId(getApplication(), id)
            _createdProfileId.value = id
            _step.value = OnboardingStep.Complete
        }
    }

    /** Advances directly to Complete — used for testing. */
    fun skipToComplete() {
        _step.value = OnboardingStep.Complete
    }

    /**
     * Called when the user taps Continue on the InstallingVoicePacks screen (edge case).
     * Always proceeds to Introduction — never blocks the user indefinitely.
     */
    fun onVoicePackInstalled() {
        val nowSupported = TTSManager.checkLanguageSupport(selectedLanguage)
        Log.d("Haq/Onboard", "onVoicePackInstalled: $selectedLanguage supported=$nowSupported — user skipped wait")
        voicePollingJob?.cancel()
        voicePollingJob = null
        enterIntroduction()
    }

    // ── Tap-to-speak STT ─────────────────────────────────────────────────────────

    /**
     * Called when the user taps the mic button, or automatically by MainActivity
     * when [micActivationEvent] increments after a TTS question completes.
     *
     * Uses generous onboarding VAD thresholds (3 s minimum, 5 s silence).
     * On blank transcript: retries 1-2 speak a retry prompt and auto-restart STT
     * via onComplete; retry 3 speaks "tap the mic" and waits for manual tap.
     * On exception: retries 1-2 delay 500 ms then restart; retry 3 waits.
     */
    fun onMicPressed() {
        if (_listenState.value == OnboardingListenState.LISTENING) return
        viewModelScope.launch {
            _listenState.value = OnboardingListenState.LISTENING
            _isListening.value = true
            try {
                val transcript = STTManager.recordForOnboarding(getApplication())
                Log.d("Haq/Onboard", "Transcript: '$transcript'")

                if (transcript.isBlank()) {
                    _listenState.value = OnboardingListenState.IDLE
                    _isListening.value = false
                    retryCount++
                    Log.d("Haq/Onboard", "Empty transcript, attempt $retryCount")
                    if (retryCount >= 3) {
                        retryCount = 0
                        TTSManager.speak(
                            text = getTapMicText(selectedLanguage),
                            languageCode = selectedLanguage,
                            onOutputError = { /* voice unavailable — tap-mic prompt silent, wait for tap */ },
                        )
                        // Wait for manual tap — chain stops here
                    } else {
                        TTSManager.speak(
                            text = getRetryText(selectedLanguage),
                            languageCode = selectedLanguage,
                            onComplete = { onMicPressed() },
                            onOutputError = { onMicPressed() }, // TTS failed — restart mic anyway
                        )
                    }
                } else {
                    retryCount = 0
                    _isListening.value = false
                    _listenState.value = OnboardingListenState.PROCESSING
                    submitAnswer(transcript)
                }

            } catch (e: Exception) {
                Log.e("Haq/Onboard", "STT error: ${e.message}")
                _listenState.value = OnboardingListenState.IDLE
                _isListening.value = false
                retryCount++
                Log.d("Haq/Onboard", "STT error, attempt $retryCount")
                if (retryCount >= 3) {
                    retryCount = 0
                    TTSManager.speak(
                        text = getTapMicText(selectedLanguage),
                        languageCode = selectedLanguage,
                        onOutputError = { /* voice unavailable — silent, wait for tap */ },
                    )
                } else {
                    delay(500L)
                    onMicPressed()
                }
            }
        }
    }

    /** Reset to start a fresh onboarding (for "add new profile" flow). */
    fun reset() {
        voicePollingJob?.cancel()
        voicePollingJob = null
        selectedLanguage    = "hi"
        collectedName       = ""
        collectedState      = ""
        collectedCaste      = ""
        collectedOccupation = ""
        retryCount          = 0
        _listenState.value  = OnboardingListenState.IDLE
        _isListening.value  = false
        _micActivationEvent.value = 0
        _supportedLanguages.value = listOf("hi", "te", "ml", "kn", "ta", "bn", "gu", "mr", "or", "as", "ne", "en")
        _createdProfileId.value = -1
        _step.value = OnboardingStep.LanguageSelect
        // Readiness polling starts only after the user picks a language (selectLanguage).
    }
}
