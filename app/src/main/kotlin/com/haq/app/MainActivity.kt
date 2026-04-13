package com.haq.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haq.app.ui.theme.HaqGreen
import com.haq.app.ui.theme.HaqMuted
import com.haq.app.ui.theme.HaqTheme

// App states — drives the status indicator and mic appearance
enum class AppState(val label: String) {
    READY("Ready"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    ERROR("Error — tap to retry"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HaqTheme {
                HaqScreen()
            }
        }
    }
}

@Composable
fun HaqScreen() {
    var appState by remember { mutableStateOf(AppState.READY) }
    var responseText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        // ── Status indicator ─────────────────────────────────────────────────
        StatusBar(appState = appState)

        Spacer(modifier = Modifier.weight(1f))

        // ── Microphone button ────────────────────────────────────────────────
        MicButton(
            appState = appState,
            onClick = {
                appState = if (appState == AppState.LISTENING) AppState.READY
                           else AppState.LISTENING
            },
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = when (appState) {
                AppState.READY     -> "Tap to speak"
                AppState.LISTENING -> "Tap to stop"
                AppState.THINKING  -> "Processing…"
                AppState.ERROR     -> "Something went wrong"
            },
            color = HaqMuted,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp,
        )

        Spacer(modifier = Modifier.weight(1f))

        // ── Response card ────────────────────────────────────────────────────
        ResponseCard(responseText = responseText)

        Spacer(modifier = Modifier.height(36.dp))
    }
}

// ── Status bar ───────────────────────────────────────────────────────────────

@Composable
private fun StatusBar(appState: AppState) {
    val dotColor by animateColorAsState(
        targetValue = when (appState) {
            AppState.READY     -> HaqGreen
            AppState.LISTENING -> Color(0xFFFFB300)   // amber while recording
            AppState.THINKING  -> Color(0xFF42A5F5)   // blue while Gemma works
            AppState.ERROR     -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(300),
        label = "dotColor",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Coloured status dot
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color = dotColor, shape = CircleShape),
            )

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = appState.label,
                color = HaqMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ── Microphone button ─────────────────────────────────────────────────────────

@Composable
private fun MicButton(appState: AppState, onClick: () -> Unit) {
    val isListening = appState == AppState.LISTENING

    // Pulse ring while listening
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val micSize by animateDpAsState(
        targetValue = if (isListening) 96.dp else 88.dp,
        animationSpec = tween(200),
        label = "micSize",
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer pulse ring — only rendered while listening
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(micSize)
                    .scale(pulseScale)
                    .border(
                        width = 2.dp,
                        color = HaqGreen.copy(alpha = 0.35f),
                        shape = CircleShape,
                    ),
            )
        }

        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(micSize),
            shape = CircleShape,
            containerColor = HaqGreen,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 10.dp,
                pressedElevation = 14.dp,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Start listening",
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

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(
    showBackground = true,
    backgroundColor = 0xFF0D0D0D,
    showSystemUi = true,
)
@Composable
private fun HaqScreenPreview() {
    HaqTheme { HaqScreen() }
}
