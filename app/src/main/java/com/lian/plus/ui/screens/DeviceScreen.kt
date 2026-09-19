package com.lian.plus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.device.CapabilityAnalyzer
import com.lian.plus.core.device.DeviceTier
import com.lian.plus.core.model.formatBytes
import com.lian.plus.ui.components.CheckRow
import com.lian.plus.ui.components.SectionHeader
import com.lian.plus.ui.components.StatCard
import kotlinx.coroutines.launch

/**
 * The "can this phone do it?" screen. Shown first to a new user, and the place
 * to come back to when generation feels slow.
 */
@Composable
fun DeviceScreen() {
    val context = LocalContext.current
    val runtime = remember { LianRuntime.get(context) }
    val report by runtime.capability.collectAsState()
    val scope = rememberCoroutineScopeSafe()
    var engineInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (report == null) runtime.refreshCapability()
        engineInfo = runtime.llm.systemInfo()
    }

    val current = report
    if (current == null) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (current.tier) {
                        DeviceTier.UNSUPPORTED -> MaterialTheme.colorScheme.errorContainer
                        DeviceTier.MINIMAL, DeviceTier.ENTRY -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${current.profile.manufacturer} ${current.profile.model}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${current.profile.socModel} · Android ${current.profile.androidRelease}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    Text(
                        "${current.tier.label} tier",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(current.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = "Largest model",
                    value = formatBytes(current.maxModelFileBytes),
                    subtitle = "recommended",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Context",
                    value = "${current.recommendedContext / 1024}K",
                    subtitle = "up to ${current.maxContext / 1024}K",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = "Threads",
                    value = current.recommendedThreads.toString(),
                    subtitle = "${current.profile.cpuCores} cores total",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Images",
                    value = if (current.canRunImageGen) "${current.recommendedImageSize}px" else "No",
                    subtitle = if (current.canRunImageGen) "max size" else "needs 6 GB",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item { SectionHeader("Hardware checks") }
        items(current.checks) { check -> CheckRow(check) }

        item { SectionHeader("Expected speed") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Estimated from memory bandwidth — real numbers depend on how warm " +
                        "the phone is and what else is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(
                    "1.5B at Q4" to 1_100L * 1024 * 1024,
                    "3B at Q4" to 2_000L * 1024 * 1024,
                    "8B at Q4" to 4_900L * 1024 * 1024,
                ).forEach { (label, size) ->
                    val fits = size <= current.hardLimitModelFileBytes
                    val tps = CapabilityAnalyzer.estimateTokensPerSecond(current.profile, size)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (fits) "~%.1f tokens/s".format(tps) else "will not fit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (fits) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Engine build") }
        item {
            Text(
                engineInfo ?: "reading…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item {
            Button(
                onClick = { scope.launch { runtime.refreshCapability() } },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Re-check this device") }
        }
    }
}

/** Small helper so screens do not each import the same three symbols. */
@Composable
fun rememberCoroutineScopeSafe() = androidx.compose.runtime.rememberCoroutineScope()
