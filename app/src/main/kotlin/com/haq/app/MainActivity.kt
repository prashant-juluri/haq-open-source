package com.haq.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.haq.app.onboarding.OnboardingViewModel
import com.haq.app.tts.TTSManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haq.app.data.UserProfile
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
import com.haq.app.onboarding.OnboardingListenState
import com.haq.app.onboarding.OnboardingStep
import com.haq.app.ui.theme.HaqGreen
import com.haq.app.ui.theme.HaqMuted
import com.haq.app.ui.theme.HaqTheme
enum class AppState(val label: String) {
    LOADING("Loading Haq…"),
    READY("Ready"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    ERROR("Error"),
}

class MainActivity : ComponentActivity() {

    private var ttsDataReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerTtsDataReceiver()
        setContent { HaqTheme { HaqScreen() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsDataReceiver?.let { unregisterReceiver(it) }
        ttsDataReceiver = null
    }

    /**
     * Registers for ACTION_TTS_DATA_INSTALLED — fired by Google TTS when it finishes
     * writing a voice pack to disk. On receipt, notifies OnboardingViewModel so it can
     * reinitialise TTS, pick up the new data, and re-run the testSpeak gate.
     */
    private fun registerTtsDataReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action != TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED) return
                Log.d("Haq/TTS", "ACTION_TTS_DATA_INSTALLED received — notifying OnboardingViewModel")
                ViewModelProvider(this@MainActivity)
                    .get(OnboardingViewModel::class.java)
                    .onTtsDataInstalled()
            }
        }
        ttsDataReceiver = receiver
        val filter = IntentFilter(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        Log.d("Haq/TTS", "TTS data receiver registered")
    }
}

// ── Root screen ───────────────────────────────────────────────────────────────
//
// Strict startup order:
//   1. Download gate — shown until DownloadState.Complete
//   2. Onboarding gate — shown until profile is created
//   3. Main app
//
// The download check MUST come first so onboarding never launches
// before the model is present.

@Composable
fun HaqScreen(
    haqVm: HaqViewModel = viewModel(),
    onboardingVm: OnboardingViewModel = viewModel(),
) {
    val context         = LocalContext.current
    val downloadState   by haqVm.downloadState.collectAsStateWithLifecycle()
    val needsOnboarding by haqVm.needsOnboarding.collectAsStateWithLifecycle()
    val onboardingStep  by onboardingVm.step.collectAsStateWithLifecycle()

    // Request RECORD_AUDIO once on first composition so the system dialog
    // appears before onboarding tries to listen.
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("Haq/Permission", "RECORD_AUDIO granted: $isGranted")
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Reload the active profile as soon as onboarding reaches Complete — the profile
    // is already committed to the DB at this point (OnboardingViewModel saves it before
    // transitioning to Complete). This ensures activeLanguage is correct before the
    // first query and before MainAppFlow's LaunchedEffect(Unit) fires.
    LaunchedEffect(onboardingStep) {
        if (onboardingStep is OnboardingStep.Complete) {
            Log.d("Haq/Main", "Onboarding complete — reloading active profile")
            haqVm.reloadActiveProfile(context)
        }
    }

    when (downloadState) {
        // ── Step 1: model not yet ready — show download UI only ──────────────
        is DownloadState.Idle,
        is DownloadState.Checking,
        is DownloadState.WifiRequired,
        is DownloadState.Downloading ->
            DownloadScreen(state = downloadState, onCheckAgain = { haqVm.retryDownload() })

        is DownloadState.Error ->
            ErrorScreen(
                message = (downloadState as DownloadState.Error).message,
                onRetry = { haqVm.retryDownload() },
            )

        // ── Step 2: model ready — check onboarding, then main app ─────────────
        is DownloadState.Complete -> when (needsOnboarding) {
            null  -> FullScreenSpinner("Loading…")
            true  -> OnboardingScreen(
                vm = onboardingVm,
                onComplete = { haqVm.setOnboardingComplete() },
            )
            false -> MainAppFlow(haqVm = haqVm, onboardingVm = onboardingVm)
        }
    }
}

// ── Main app flow — engine loading guard then main content ────────────────────

@Composable
private fun MainAppFlow(haqVm: HaqViewModel, onboardingVm: OnboardingViewModel) {
    val context        = LocalContext.current
    val engineState    by haqVm.engineState.collectAsStateWithLifecycle()
    val appState       by haqVm.appState.collectAsStateWithLifecycle()
    val responseText   by haqVm.responseText.collectAsStateWithLifecycle()
    val activeProfile  by haqVm.activeProfileName.collectAsStateWithLifecycle()
    val activeLanguage by haqVm.activeLanguage.collectAsStateWithLifecycle()
    val profiles       by haqVm.getAllProfiles().collectAsStateWithLifecycle(emptyList())

    // Load the active profile once on entry so language + system prompt are ready.
    LaunchedEffect(Unit) {
        haqVm.reloadActiveProfile(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            engineState is EngineState.Loading ->
                FullScreenSpinner(label = "Loading Haq…")

            engineState is EngineState.Error ->
                ErrorScreen(
                    message = (engineState as EngineState.Error).message,
                    onRetry  = { haqVm.retryEngine() },
                )

            else ->
                MainAppContent(
                    appState          = appState,
                    responseText      = responseText,
                    activeProfileName = activeProfile,
                    profiles          = profiles,
                    onMicTap          = { haqVm.onMicButtonPressed() },
                    onSwitchProfile   = { profileId ->
                        // Stop TTS and cancel any in-flight Gemma query before switching.
                        // switchProfile() calls reloadActiveProfile() which restores the
                        // last conversation for the selected profile.
                        haqVm.resetToIdle()
                        haqVm.switchProfile(profileId)
                        Log.d("Haq/Main", "Profile switched to $profileId — previous session cancelled")
                    },
                    onAddProfile      = {
                        // Cancel everything before starting a fresh onboarding session.
                        // MainAppFlow is only rendered when downloadState == Complete,
                        // so there is no need to guard against model-not-ready here.
                        haqVm.resetToIdle()
                        onboardingVm.reset()
                        haqVm.startNewProfile()
                        Log.d("Haq/Main", "New profile flow started — all sessions cancelled")
                    },
                )
        }
    }
}

// ── Onboarding screen ─────────────────────────────────────────────────────────

@Composable
private fun OnboardingScreen(vm: OnboardingViewModel, onComplete: () -> Unit) {
    val step               by vm.step.collectAsStateWithLifecycle()
    val listenState        by vm.listenState.collectAsStateWithLifecycle()
    val supportedLanguages by vm.supportedLanguages.collectAsStateWithLifecycle()
    val preparingStatus    by vm.preparingStatus.collectAsStateWithLifecycle()

    // When the Complete step arrives, speak the message via speakOnboarding() then hand off.
    LaunchedEffect(step) {
        if (step is OnboardingStep.Complete) {
            vm.speakCompletion { onComplete() }
        }
    }

    when (step) {
        is OnboardingStep.PreparingVoices -> PreparingVoicesScreen(status = preparingStatus)
        is OnboardingStep.LanguageSelect  -> LanguageSelectScreen(
            supportedLanguages = supportedLanguages,
            onSelect           = { vm.selectLanguage(it) },
        )
        is OnboardingStep.InstallingVoicePacks -> InstallingVoicePacksScreen(
            language   = vm.selectedLanguage,
            attempt    = 0,
            onContinue = { vm.onVoicePackInstalled() },
        )
        else -> ConversationOnboardingScreen(step, vm, listenState)
    }
}

// ── PreparingVoices screen ────────────────────────────────────────────────────

@Composable
private fun PreparingVoicesScreen(status: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = Color(0xFF1D6F42))
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Preparing Haq...",
                fontSize = 18.sp,
                color = Color(0xFF1D6F42),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                fontSize = 14.sp,
                color = HaqMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Language select ───────────────────────────────────────────────────────────

@Composable
private fun LanguageSelectScreen(
    supportedLanguages: List<String>,
    onSelect: (String) -> Unit,
) {
    // All 6 languages in canonical display order.
    val allLanguages = listOf(
        Triple("hi", "हिंदी",   "Hindi"),
        Triple("te", "తెలుగు",  "Telugu"),
        Triple("ml", "മലയാളം", "Malayalam"),
        Triple("kn", "ಕನ್ನಡ",  "Kannada"),
        Triple("ta", "தமிழ்",  "Tamil"),
        Triple("en", "English", "English"),
    )
    // Filter to languages confirmed available on this device.
    val visible = allLanguages.filter { (code, _, _) -> code in supportedLanguages }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "हक़",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = HaqGreen,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "अपनी भाषा चुनें • భాష ఎంచుకోండి • ഭാഷ തിരഞ്ഞെടുക്കൂ • ಭಾಷೆ ಆಯ್ಕೆ • மொழி தேர்வு",
            fontSize = 11.sp,
            color = HaqMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        // Render up to 3 languages per row; pad shorter rows with Spacers so
        // buttons stay equal-width regardless of how many are supported.
        visible.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { (code, script, name) ->
                    LanguageButton(
                        script = script,
                        name = name,
                        onClick = { onSelect(code) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Invisible spacers keep button widths consistent in partial rows
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LanguageButton(
    script: String,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(script, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(name,   fontSize = 11.sp, color = HaqMuted)
        }
    }
}

// ── Installing voice packs screen ─────────────────────────────────────────────

@Composable
private fun InstallingVoicePacksScreen(language: String, attempt: Int, onContinue: () -> Unit) {
    val preparingText = when (language) {
        "te" -> "తెలుగు స్వరం సిద్ధమవుతోంది..."
        "ml" -> "മലയാളം ശബ്ദം തയ്യാറാകുന്നു..."
        "kn" -> "ಕನ್ನಡ ಧ್ವನಿ ಸಿದ್ಧವಾಗುತ್ತಿದೆ..."
        "ta" -> "தமிழ் குரல் தயாராகிறது..."
        "en" -> "Preparing English voice..."
        else -> "हिंदी आवाज़ तैयार की जा रही है..."
    }
    val onceText = when (language) {
        "te" -> "ఇది ఒక్కసారి మాత్రమే జరుగుతుంది."
        "ml" -> "ഇത് ഒരിക്കൽ മാത്രം സംഭവിക്കും."
        "kn" -> "ಇದು ಒಂದು ಬಾರಿ ಮಾತ್ರ ಆಗುತ್ತದೆ."
        "ta" -> "இது ஒரே ஒரு முறை நடக்கும்."
        "en" -> "This will only happen once."
        else -> "यह केवल एक बार होगा।"
    }
    val continueText = when (language) {
        "te" -> "కొనసాగించు"
        "ml" -> "തുടരുക"
        "kn" -> "ಮುಂದುವರಿಸಿ"
        "ta" -> "தொடரவும்"
        "en" -> "Continue"
        else -> "जारी रखें"
    }

    LaunchedEffect(Unit) {
        TTSManager.speak(text = preparingText, languageCode = language)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(color = HaqGreen, strokeWidth = 3.dp)
            Text(
                text = preparingText,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = onceText,
                color = HaqMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            if (attempt > 0) {
                Text(
                    text = "Checking… ($attempt/5)",
                    color = HaqMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
            }
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = HaqGreen),
            ) {
                Text(continueText)
            }
        }
    }
}

// ── Conversation onboarding (Introduction → AskName → … → Complete) ──────────

@Composable
private fun ConversationOnboardingScreen(
    step: OnboardingStep,
    vm: OnboardingViewModel,
    listenState: OnboardingListenState,
) {
    val lang               = vm.selectedLanguage
    val ttsSpeaking        by TTSManager.isSpeaking.collectAsStateWithLifecycle()
    val isListening        by vm.isListening.collectAsStateWithLifecycle()
    val micActivationEvent by vm.micActivationEvent.collectAsStateWithLifecycle()

    val displayText = when (step) {
        is OnboardingStep.Introduction   -> vm.getIntroductionText(lang)
        is OnboardingStep.AskName        -> "…"
        is OnboardingStep.AskState       -> vm.getAskStateText(lang)
        is OnboardingStep.AskCaste       -> vm.getAskCasteText(lang)
        is OnboardingStep.AskOccupation  -> vm.getAskOccupationText(lang)
        is OnboardingStep.Complete       -> vm.getCompletionText(lang)
        else -> ""
    }

    val statusText = when {
        ttsSpeaking -> when (lang) {
            "te" -> "వింటున్నాను…"
            "ml" -> "സംസാരിക്കുന്നു…"
            "kn" -> "ಮಾತನಾಡುತ್ತಿದ್ದೇನೆ…"
            "ta" -> "பேசுகிறேன்…"
            "en" -> "Speaking…"
            else -> "बोल रहा हूँ…"
        }
        listenState == OnboardingListenState.LISTENING -> when (lang) {
            "te" -> "మాట్లాడండి…"
            "ml" -> "സംസാരിക്കൂ…"
            "kn" -> "ಮಾತನಾಡಿ…"
            "ta" -> "பேசுங்கள்…"
            "en" -> "Speak now…"
            else -> "बोलिए…"
        }
        listenState == OnboardingListenState.PROCESSING -> when (lang) {
            "te" -> "అర్థం చేసుకుంటున్నాను…"
            "ml" -> "മനസ്സിലാക്കുന്നു…"
            "kn" -> "ಅರ್ಥಮಾಡಿಕೊಳ್ಳುತ್ತಿದ್ದೇನೆ…"
            "ta" -> "புரிந்துகொள்கிறேன்…"
            "en" -> "Processing…"
            else -> "समझ रहा हूँ…"
        }
        else -> ""
    }

    // Introduction is spoken by OnboardingViewModel.enterIntroduction() via speakOnboarding().
    // All question TTS goes through speakOnboarding() for -4 retry recovery.

    // Auto-activate mic whenever the ViewModel signals a new question is ready.
    // micActivationEvent starts at 0 — only fire on increments (> 0).
    LaunchedEffect(micActivationEvent) {
        if (micActivationEvent > 0) {
            vm.onMicPressed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Response card fills available space
        ResponseCard(
            text = displayText,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.height(12.dp))

        if (statusText.isNotEmpty()) {
            Text(statusText, color = HaqMuted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        // Mic disabled while TTS is speaking; pulses green when ready for tap
        OnboardingMicButton(
            listenState = listenState,
            isListening = isListening,
            isSpeaking = ttsSpeaking,
            onClick = { vm.onMicPressed() },
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ── Onboarding mic button ────────────────────────────────────────────────────

@Composable
private fun OnboardingMicButton(
    listenState: OnboardingListenState,
    isListening: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit,
) {
    // Disabled while TTS is speaking or STT is processing
    val isDisabled  = isSpeaking || listenState == OnboardingListenState.PROCESSING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isDisabled                                       -> HaqMuted
            listenState == OnboardingListenState.LISTENING  -> Color(0xFFE53935)
            listenState == OnboardingListenState.PROCESSING -> Color(0xFF42A5F5)
            else                                            -> HaqGreen
        },
        animationSpec = tween(300),
        label = "obMicBg",
    )

    Box(contentAlignment = Alignment.Center) {
        // Pulse ring: green when waiting for tap, red when STT is active
        if (!isDisabled) {
            val ringColor = if (isListening) Color(0xFFE53935) else HaqGreen
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .border(2.dp, ringColor.copy(alpha = 0.35f), CircleShape),
            )
        }
        FloatingActionButton(
            onClick = { if (!isDisabled && listenState == OnboardingListenState.IDLE) onClick() },
            modifier = Modifier.size(88.dp),
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(10.dp, 14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = if (isSpeaking) "Speaking" else "Speak",
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

// ── Main app content (post-onboarding) ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppContent(
    appState: AppState,
    responseText: String,
    activeProfileName: String,
    profiles: List<UserProfile>,
    onMicTap: () -> Unit,
    onSwitchProfile: (Int) -> Unit,
    onAddProfile: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onMicTap() }

    val ttsSpeaking by TTSManager.isSpeaking.collectAsStateWithLifecycle()
    var showProfileSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar — shows profile initial, tapping opens switcher
            ProfileAvatar(
                name = activeProfileName,
                onClick = { showProfileSheet = true },
            )

            Text(
                text = "हक़",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = HaqGreen,
            )

            IconButton(onClick = onAddProfile) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add profile",
                    tint = HaqMuted,
                )
            }
        }

        // ── Response area — fills available space ─────────────────────────────
        ResponseCard(
            text = responseText,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.height(16.dp))

        // ── Status hint ───────────────────────────────────────────────────────
        Text(
            text = when (appState) {
                AppState.LOADING   -> "Loading model…"
                AppState.READY     -> "Tap to speak"
                AppState.LISTENING -> "Speak now…"
                AppState.THINKING  -> "Processing…"
                AppState.ERROR     -> "Something went wrong"
            },
            color = HaqMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        // ── Mic button ────────────────────────────────────────────────────────
        MicButton(
            appState   = appState,
            isSpeaking = ttsSpeaking,
            onClick    = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        Spacer(Modifier.height(28.dp))
    }

    // ── Profile switcher bottom sheet ─────────────────────────────────────────
    if (showProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ProfileSwitcherSheet(
                profiles = profiles,
                onSelect = { id ->
                    onSwitchProfile(id)
                    showProfileSheet = false
                },
                onAddNew = {
                    showProfileSheet = false
                    onAddProfile()
                },
            )
        }
    }
}

// ── Profile avatar ────────────────────────────────────────────────────────────

@Composable
private fun ProfileAvatar(name: String, onClick: () -> Unit) {
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: ""
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(HaqGreen)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (initial.isEmpty()) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Profile",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── Profile switcher sheet content ───────────────────────────────────────────

@Composable
private fun ProfileSwitcherSheet(
    profiles: List<UserProfile>,
    onSelect: (Int) -> Unit,
    onAddNew: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Profiles",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        profiles.forEach { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(profile.id) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(HaqGreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = profile.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(profile.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Text(profile.state.ifEmpty { "—" }, color = HaqMuted, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onAddNew,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = HaqGreen),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add new profile")
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Response card ─────────────────────────────────────────────────────────────

@Composable
private fun ResponseCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopStart,
        ) {
            Text(
                text = text.ifEmpty { "Speak your question…" },
                color = if (text.isEmpty()) HaqMuted else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            )
        }
    }
}

// ── Mic button ────────────────────────────────────────────────────────────────

@Composable
private fun MicButton(appState: AppState, isSpeaking: Boolean, onClick: () -> Unit) {
    val isActive  = appState == AppState.LISTENING || appState == AppState.THINKING
    // Block tap while TTS is playing so users can't cut off the response
    val isEnabled = appState == AppState.READY && !isSpeaking

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val micSize by animateDpAsState(
        targetValue = if (isActive) 96.dp else 88.dp,
        animationSpec = tween(200),
        label = "micSize",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isSpeaking                  -> HaqMuted   // grayed while TTS plays
            appState == AppState.LOADING   -> HaqMuted
            appState == AppState.LISTENING -> Color(0xFFE53935)
            appState == AppState.THINKING  -> Color(0xFF42A5F5)
            else                           -> HaqGreen
        },
        animationSpec = tween(300),
        label = "micBgColor",
    )

    Box(contentAlignment = Alignment.Center) {
        if (appState == AppState.LISTENING) {
            Box(
                modifier = Modifier
                    .size(micSize)
                    .scale(pulseScale)
                    .border(2.dp, Color(0xFFE53935).copy(alpha = 0.35f), CircleShape),
            )
        }
        FloatingActionButton(
            onClick = { if (isEnabled) onClick() },
            modifier = Modifier.size(micSize),
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(10.dp, 14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = if (isActive) "Recording" else "Speak",
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

// ── Download screen ───────────────────────────────────────────────────────────

@Composable
private fun DownloadScreen(state: DownloadState, onCheckAgain: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            is DownloadState.Idle, is DownloadState.Checking -> {
                CircularProgressIndicator(color = HaqGreen, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Preparing Haq…", color = HaqMuted, fontSize = 15.sp)
            }
            is DownloadState.WifiRequired -> {
                Text("WiFi Required", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Please connect to WiFi to download the AI model (2.4 GB).\n\nThis is a one-time download. All queries run offline afterwards.",
                    color = HaqMuted, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onCheckAgain, colors = ButtonDefaults.buttonColors(containerColor = HaqGreen)) {
                    Text("Check again")
                }
            }
            is DownloadState.Downloading -> {
                Text("Downloading AI model… ${state.progressPercent}%", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = HaqGreen,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("This is a one-time download of 2.4 GB", color = HaqMuted, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Please keep the app open and stay on WiFi", color = HaqMuted, fontSize = 13.sp)
            }
            is DownloadState.Error -> {
                Text("Download failed", color = MaterialTheme.colorScheme.error, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(state.message, color = HaqMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(28.dp))
                Button(onClick = onCheckAgain, colors = ButtonDefaults.buttonColors(containerColor = HaqGreen)) {
                    Text("Retry")
                }
            }
            is DownloadState.Complete -> { /* routed away */ }
        }
    }
}

// ── Full-screen spinner ───────────────────────────────────────────────────────

@Composable
private fun FullScreenSpinner(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = HaqGreen, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(label, color = HaqMuted, fontSize = 15.sp)
    }
}

// ── Engine error screen ───────────────────────────────────────────────────────

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Error: $message", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = HaqGreen)) {
            Text("Retry")
        }
    }
}

