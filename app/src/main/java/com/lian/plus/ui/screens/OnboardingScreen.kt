package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.GradientButton
import com.lian.plus.ui.components.GradientIconTile
import com.lian.plus.ui.components.LianWordmark
import com.lian.plus.ui.theme.Lian

private data class Highlight(val icon: ImageVector, val title: String, val detail: String)

private val highlights = listOf(
    Highlight(
        Icons.Default.Chat, "Chat with LLMs",
        "Run a language model straight off the phone's own CPU.",
    ),
    Highlight(
        Icons.Default.Image, "Generate images",
        "Diffusion models, no server and no queue.",
    ),
    Highlight(
        Icons.Default.CloudOff, "100% offline",
        "After the download, nothing needs a connection.",
    ),
    Highlight(
        Icons.Default.Lock, "Your data, your device",
        "Conversations and images never leave this phone.",
    ),
)

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Lian.Background),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Lian.Purple.copy(alpha = 0.28f), Color.Transparent),
                        radius = 700f,
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                "Welcome to",
                style = MaterialTheme.typography.headlineSmall,
                color = Lian.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            LianWordmark(width = 148.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Local AI in your hands.",
                style = MaterialTheme.typography.bodyMedium,
                color = Lian.TextMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))
            highlights.forEach { item ->
                BrandCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GradientIconTile(item.icon, size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Lian.TextPrimary,
                            )
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = Lian.TextMuted,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            GradientButton(
                text = "Get Started",
                onClick = onGetStarted,
                trailingIcon = Icons.Default.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Nothing is downloaded until you choose a model.",
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
