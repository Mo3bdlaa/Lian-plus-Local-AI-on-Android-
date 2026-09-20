package com.lian.plus.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lian.plus.ui.components.LianWordmark
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.delay

/**
 * The first frame. It holds only as long as the runtime needs to read the
 * saved model selection, then hands over — a splash that outstays that is just
 * a delay the user did not ask for.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600),
        label = "splash-fade",
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(1100)
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Lian.Background),
        contentAlignment = Alignment.Center,
    ) {
        // The violet bloom from the artwork, echoed behind the wordmark.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Lian.Purple.copy(alpha = 0.30f),
                            Lian.Blue.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        radius = 900f,
                    ),
                )
                .alpha(alpha),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.alpha(alpha),
        ) {
            LianWordmark(width = 200.dp)
            Text(
                "Local AI. Limitless You.",
                style = MaterialTheme.typography.bodyMedium,
                color = Lian.TextMuted,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            "AI on your terms.",
            style = MaterialTheme.typography.labelSmall,
            color = Lian.TextMuted.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(alpha),
        )
    }
}
