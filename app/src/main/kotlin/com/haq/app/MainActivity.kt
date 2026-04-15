package com.haq.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haq.app.data.UserProfile
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
import com.haq.app.onboarding.OnboardingListenState
import com.haq.app.onboarding.OnboardingStep
import com.haq.app.onboarding.OnboardingViewModel
import com.haq.app.tts.TTSManager
import com.haq.app.ui.theme.HaqGreen
import com.haq.app.ui.theme.HaqMuted
import com.haq.app.ui.theme.HaqTheme
import kotlinx.coroutines.delay

enum class AppState(val label: String) {
    LOADING("Loading Haq…"),
    READY("Ready"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    ERROR("Error"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HaqTheme { HaqScreen() } }
    }
}

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
fun HaqScreen(
    haqVm: HaqViewModel = viewModel(),
    onboardingVm: OnboardingViewModel = viewModel(),
) {
    val needsOnboarding by haqVm.needsOnboarding.collectAsStateWithLifecycle()

    when (needsOnboarding) {
        null -> FullScreenSpinner("Loading…")
        true -> OnboardingScreen(
            vm = onboardingVm,
            onComplete = {
                haqVm.setOnboardingComplete()
            }
        )
        false -> MainAppFlow(haqVm = haqVm, onboardingVm = onboardingVm)
    }
}

// ── Main app flow — handles download + engine loading before showing UI ───────

@Composable
private fun MainAppFlow(haqVm: HaqViewModel, onboardingVm: OnboardingViewModel) {
    val downloadState  by haqVm.downloadState.collectAsStateWithLifecycle()
    val engineState    by haqVm.engineState.collectAsStateWithLifecycle()
    val appState       by haqVm.appState.collectAsStateWithLifecycle()
    val responseText   by haqVm.responseText.collectAsStateWithLifecycle()
    val activeProfile  by haqVm.activeProfileName.collectAsStateWithLifecycle()
    val profiles       by haqVm.getAllProfiles().collectAsStateWithLifecycle(emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            downloadState !is DownloadState.Complete ->
                DownloadScreen(state = downloadState, onCheckAgain = { haqVm.retryDownload() })

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
                    onSwitchProfile   = { haqVm.switchProfile(it) },
                    onAddProfile      = {
                        onboardingVm.reset()
                        haqVm.startNewProfile()
                    },
                )
        }
    }
}

// ── Onboarding screen ─────────────────────────────────────────────────────────

@Composable
private fun OnboardingScreen(vm: OnboardingViewModel, onComplete: () -> Unit) {
    val step        by vm.step.collectAsStateWithLifecycle()
    val listenState by vm.listenState.collectAsStateWithLifecycle()

    // When the Complete step arrives, speak the message then hand off
    LaunchedEffect(step) {
        if (step is OnboardingStep.Complete) {
            TTSManager.speak(vm.getCompletionText(vm.selectedLanguage), vm.selectedLanguage)
            delay(2500)
            onComplete()
        }
    }

    when (step) {
        is OnboardingStep.LanguageSelect -> LanguageSelectScreen { vm.selectLanguage(it) }
        else -> ConversationOnboardingScreen(step, vm, listenState)
    }
}

// ── Language select ───────────────────────────────────────────────────────────

@Composable
private fun LanguageSelectScreen(onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 32.dp),
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
            text = "अपनी भाषा चुनें • భాష ఎంచుకోండి • ഭാഷ തിരഞ്ഞെടുക്കൂ",
            fontSize = 12.sp,
            color = HaqMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))

        listOf(
            Triple("hi", "हिंदी",    "Hindi"),
            Triple("te", "తెలుగు",   "Telugu"),
            Triple("ml", "മലയാളം",  "Malayalam"),
        ).forEach { (code, script, name) ->
            Button(
                onClick = { onSelect(code) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(script, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(name, fontSize = 12.sp, color = HaqMuted)
                }
            }
            Spacer(Modifier.height(16.dp))
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
    val lang = vm.selectedLanguage

    val displayText = when (step) {
        is OnboardingStep.Introduction   -> vm.getIntroductionText(lang)
        is OnboardingStep.AskName        -> "…"
        is OnboardingStep.AskState       -> vm.getAskStateText(lang)
        is OnboardingStep.AskCaste       -> vm.getAskCasteText(lang)
        is OnboardingStep.AskOccupation  -> vm.getAskOccupationText(lang)
        is OnboardingStep.Complete       -> vm.getCompletionText(lang)
        else -> ""
    }

    val statusText = when (listenState) {
        OnboardingListenState.LISTENING   -> when (lang) {
            "te" -> "మాట్లాడండి…"
            "ml" -> "സംസാരിക്കൂ…"
            else -> "बोलिए…"
        }
        OnboardingListenState.PROCESSING  -> when (lang) {
            "te" -> "అర్థం చేసుకుంటున్నాను…"
            "ml" -> "മനസ്സിലാക്കുന്നു…"
            else -> "समझ रहा हूँ…"
        }
        else -> ""
    }

    // Auto-TTS and auto-listen per step
    LaunchedEffect(step) {
        when (step) {
            is OnboardingStep.Introduction -> {
                TTSManager.speak(vm.getIntroductionText(lang), lang)
                delay(3500)
                vm.onIntroductionComplete()
            }
            is OnboardingStep.AskName -> {
                // Name question was already in the introduction; just listen
                delay(500)
                vm.startListening()
            }
            is OnboardingStep.AskState -> {
                TTSManager.speak(vm.getAskStateText(lang), lang)
                delay(2500)
                vm.startListening()
            }
            is OnboardingStep.AskCaste -> {
                TTSManager.speak(vm.getAskCasteText(lang), lang)
                delay(2500)
                vm.startListening()
            }
            is OnboardingStep.AskOccupation -> {
                TTSManager.speak(vm.getAskOccupationText(lang), lang)
                delay(2500)
                vm.startListening()
            }
            else -> {}
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

        OnboardingMicButton(
            listenState = listenState,
            onClick = { vm.startListening() },
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ── Onboarding mic button ────────────────────────────────────────────────────

@Composable
private fun OnboardingMicButton(
    listenState: OnboardingListenState,
    onClick: () -> Unit,
) {
    val isListening = listenState == OnboardingListenState.LISTENING
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val containerColor by animateColorAsState(
        targetValue = when (listenState) {
            OnboardingListenState.LISTENING  -> Color(0xFFE53935)
            OnboardingListenState.PROCESSING -> Color(0xFF42A5F5)
            else                             -> HaqGreen
        },
        animationSpec = tween(300),
        label = "obMicBg",
    )

    Box(contentAlignment = Alignment.Center) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .border(2.dp, Color(0xFFE53935).copy(alpha = 0.35f), CircleShape),
            )
        }
        FloatingActionButton(
            onClick = { if (listenState == OnboardingListenState.IDLE) onClick() },
            modifier = Modifier.size(88.dp),
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(10.dp, 14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Speak",
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
            appState = appState,
            onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
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
private fun MicButton(appState: AppState, onClick: () -> Unit) {
    val isActive  = appState == AppState.LISTENING || appState == AppState.THINKING
    val isEnabled = appState == AppState.READY

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
        targetValue = when (appState) {
            AppState.LOADING   -> HaqMuted
            AppState.LISTENING -> Color(0xFFE53935)
            AppState.THINKING  -> Color(0xFF42A5F5)
            else               -> HaqGreen
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
