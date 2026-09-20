package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as GfxBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lian.plus.BuildConfig
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.LianAppMark
import com.lian.plus.ui.components.SettingRow
import com.lian.plus.ui.theme.Lian

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenDevice: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Lian.Background)) {
        LianTopBar(title = "About", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(
                        GfxBrush.radialGradient(
                            listOf(Lian.Purple.copy(alpha = 0.22f), Color.Transparent),
                            radius = 460f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LianAppMark(size = 88.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Lian+",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Lian.TextPrimary,
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Local AI. Limitless You.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lian.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            BrandCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                SettingRow(
                    title = "Privacy first",
                    subtitle = "Chats, documents and images stay in this app's storage",
                    icon = Icons.Default.Lock,
                )
                SettingRow(
                    title = "This device",
                    subtitle = "What the hardware can run",
                    icon = Icons.Default.Memory,
                    onClick = onOpenDevice,
                )
                SettingRow(
                    title = "Open source engines",
                    subtitle = "llama.cpp and stable-diffusion.cpp, both MIT",
                    icon = Icons.Default.Code,
                )
                SettingRow(
                    title = "Built for creators",
                    subtitle = "Text, images and a local OpenAI-compatible API",
                    icon = Icons.Default.Brush,
                )
                SettingRow(
                    title = "Made with care",
                    subtitle = "Every model you download carries its own licence",
                    icon = Icons.Default.Favorite,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "© 2026 Lian+. All rights reserved.",
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
