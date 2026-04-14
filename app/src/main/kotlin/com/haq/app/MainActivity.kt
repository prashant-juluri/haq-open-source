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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haq.app.inference.DownloadState
import com.haq.app.inference.EngineState
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
fun HaqScreen(vm: HaqViewModel = viewModel()) {
    val downloadState by vm.downloadState.collectAsStateWithLifecycle()
    val engineState   by vm.engineState.collectAsStateWithLifecycle()
    val appState      by vm.appState.collectAsStateWithLifecycle()
    val responseText  by vm.responseText.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            downloadState !is DownloadState.Complete ->
                DownloadScreen(state = downloadState, onCheckAgain = { vm.retryDownload() })

            engineState is EngineState.Loading ->
                FullScreenSpinner(label = "Loading Haq…")

            engineState is EngineState.Error ->
                ErrorScreen(
                    message = (engineState as EngineState.Error).message,
                    onRetry  = { vm.retryEngine() },
                )

            else ->
                AppContent(
                    appState     = appState,
                    engineState  = engineState,
                    responseText = responseText,
                    onMicTap     = { vm.onMicButtonPressed() },
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
                Text(
                    text = "WiFi Required",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Please connect to WiFi to download the AI model (2.4 GB).\n\n" +
                           "This is a one-time download. All queries run offline afterwards.",
                    color = HaqMuted,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onCheckAgain,
                    colors = ButtonDefaults.buttonColors(containerColor = HaqGreen),
                ) { Text("Check again") }
            }

            is DownloadState.Downloading -> {
                Text(
                    text = "Downloading AI model… ${state.progressPercent}%",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
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
                Text(
                    text = "Download failed",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.message,
                    color = HaqMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onCheckAgain,
                    colors = ButtonDefaults.buttonColors(containerColor = HaqGreen),
                ) { Text("Retry") }
            }

            is DownloadState.Complete -> { /* routed away in HaqScreen */ }
        }
    }
}

// ── Full-screen spinner ───────────────────────────────────────────────────────

@Composable
private fun FullScreenSpinner(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        Text(
            text = "Error: $message",
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = HaqGreen),
        ) { Text("Retry") }
    }
}

// ── Normal app UI ─────────────────────────────────────────────────────────────

@Composable
private fun AppContent(
    appState: AppState,
    engineState: EngineState,
    responseText: String,
    onMicTap: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onMicTap()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusBar(appState = appState, engineState = engineState)

        Spacer(modifier = Modifier.weight(1f))

        MicButton(
            appState = appState,
            onClick  = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = when (appState) {
                AppState.LOADING   -> "Loading model…"
                AppState.READY     -> "Tap to speak"
                AppState.LISTENING -> "Speak now — recording stops automatically"
                AppState.THINKING  -> "Processing…"
                AppState.ERROR     -> "Something went wrong"
            },
            color = HaqMuted,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))

        ResponseCard(responseText = responseText)

        Spacer(modifier = Modifier.height(36.dp))
    }
}

// ── Status bar ────────────────────────────────────────────────────────────────

@Composable
private fun StatusBar(appState: AppState, engineState: EngineState) {
    var secondsLeft by remember { mutableIntStateOf(10) }
    LaunchedEffect(appState) {
        if (appState == AppState.LISTENING) {
            secondsLeft = 10
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft--
            }
        }
    }

    val dotColor by animateColorAsState(
        targetValue = when (appState) {
            AppState.LOADING   -> HaqMuted
            AppState.READY     -> HaqGreen
            AppState.LISTENING -> Color(0xFFFFB300)
            AppState.THINKING  -> Color(0xFF42A5F5)
            AppState.ERROR     -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(300),
        label = "dotColor",
    )

    val statusLabel = when {
        appState == AppState.LISTENING ->
            "Listening… (${secondsLeft}s)"
        appState == AppState.ERROR && engineState is EngineState.Error ->
            "Error: ${(engineState as EngineState.Error).message}"
        else -> appState.label
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (appState == AppState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = HaqMuted,
                    strokeWidth = 1.5.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = statusLabel,
                color = HaqMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
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

// ── Response card ─────────────────────────────────────────────────────────────

@Composable
private fun ResponseCard(responseText: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
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
                text = responseText.ifEmpty { "Speak your question…" },
                color = if (responseText.isEmpty()) HaqMuted
                        else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            )
        }
    }
}
