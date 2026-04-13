package com.haq.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haq.app.ui.theme.HaqTheme

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
    // Skeleton state — wired up to Whisper + Gemma in Week 1
    var isListening by remember { mutableStateOf(false) }
    var responseText by remember { mutableStateOf("") }

    val micColor by animateColorAsState(
        targetValue = if (isListening) MaterialTheme.colorScheme.error
                      else MaterialTheme.colorScheme.primary,
        animationSpec = tween(200),
        label = "micColor",
    )
    val micSize by animateDpAsState(
        targetValue = if (isListening) 96.dp else 88.dp,
        animationSpec = tween(150),
        label = "micSize",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // App wordmark
        Text(
            text = "হক  |  హక్కు  |  உரிமை",   // "right / entitlement" in Hindi, Telugu, Tamil
            color = Color(0xFF424242),
            fontSize = 13.sp,
            letterSpacing = 2.sp,
        )

        Spacer(modifier = Modifier.weight(1f))

        // ── Microphone button ────────────────────────────────────────────────
        FloatingActionButton(
            onClick = { isListening = !isListening },
            modifier = Modifier.size(micSize),
            shape = CircleShape,
            containerColor = micColor,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Tap to speak",
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isListening) "Listening…" else "Tap to speak",
            color = if (isListening) MaterialTheme.colorScheme.error
                    else Color(0xFF757575),
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.weight(1f))

        // ── Gemma response area ──────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 340.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Box(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopStart,
            ) {
                if (responseText.isEmpty()) {
                    Text(
                        text = "आपके अधिकार यहाँ दिखेंगे।\nYour entitlements will appear here.",
                        color = Color(0xFF424242),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Text(
                        text = responseText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
private fun HaqScreenPreview() {
    HaqTheme { HaqScreen() }
}
