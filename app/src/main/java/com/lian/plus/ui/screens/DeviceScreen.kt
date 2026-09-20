package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.device.CapabilityAnalyzer
import com.lian.plus.core.device.ComputeDevices
import com.lian.plus.core.device.CapabilityCheck
import com.lian.plus.core.device.CheckStatus
import com.lian.plus.core.device.DeviceTier
import com.lian.plus.core.model.formatBytes
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.GradientButton
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.launch

/**
 * The "can this phone do it?" report. Shown from Settings and from About, and
 * the first place to look when generation feels slower than expected.
 */
@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { LianRuntime.get(context) }
    val report by runtime.capability.collectAsState()
    val measured by runtime.benchmark.collectAsState()
    val scope = rememberCoroutineScope()
    var engineInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (report == null) runtime.refreshCapability()
        engineInfo = runtime.llm.systemInfo()
    }

    Column(Modifier.fillMaxSize().background(Lian.Background)) {
        LianTopBar(title = "This device", onBack = onBack)

        val current = report
        if (current == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Lian.Cyan)
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BrandCard(Modifier.fillMaxWidth(), highlighted = true) {
                    Text(
                        "${current.profile.manufacturer} ${current.profile.model}",
                        style = MaterialTheme.typography.titleLarge,
                        color = Lian.TextPrimary,
                    )
                    Text(
                        "${current.profile.socModel} · Android ${current.profile.androidRelease}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${current.tier.label} tier",
                        style = MaterialTheme.typography.titleMedium,
                        color = when (current.tier) {
                            DeviceTier.UNSUPPORTED -> Lian.Danger
                            DeviceTier.MINIMAL, DeviceTier.ENTRY -> Lian.TextMuted
                            else -> Lian.Cyan
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Largest model", formatBytes(current.maxModelFileBytes),
                        "recommended", Modifier.weight(1f),
                    )
                    StatTile(
                        "Context", "${current.recommendedContext / 1024}K",
                        "up to ${current.maxContext / 1024}K", Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Threads", current.recommendedThreads.toString(),
                        "${current.profile.cpuCores} cores total", Modifier.weight(1f),
                    )
                    StatTile(
                        "Images",
                        if (current.canRunImageGen) "${current.recommendedImageSize}px" else "No",
                        if (current.canRunImageGen) "max size" else "needs 6 GB",
                        Modifier.weight(1f),
                    )
                }
            }

            item { SectionTitle("Hardware checks") }
            items(current.checks) { check -> CheckRow(check) }

            item { SectionTitle("Expected speed") }
            item {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (measured.hasRun) {
                            "Calculated from this phone's measured memory bandwidth of " +
                                "%.1f GB/s. Real numbers still depend on how warm it is "
                                    .format(measured.memoryBandwidthGbs) +
                                "and what else is running."
                        } else {
                            "Estimated from the CPU's capabilities. The app measures the " +
                                "device in the background shortly after starting, and these " +
                                "numbers get more accurate once it has."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "1.5B at Q4" to 1_100L * 1024 * 1024,
                        "3B at Q4" to 2_000L * 1024 * 1024,
                        "8B at Q4" to 4_900L * 1024 * 1024,
                    ).forEach { (label, size) ->
                        val fits = size <= current.hardLimitModelFileBytes
                        val tps = CapabilityAnalyzer.estimateTokensPerSecond(current.profile, size, measured)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = Lian.TextPrimary,
                            )
                            Text(
                                if (fits) "~%.1f tokens/s".format(tps) else "will not fit",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (fits) Lian.Cyan else Lian.Danger,
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Compute devices") }
            item {
                BrandCard(Modifier.fillMaxWidth()) {
                    val devices = remember { ComputeDevices.devices() }
                    if (devices.isEmpty()) {
                        Text(
                            "The engine reported no compute devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lian.TextMuted,
                        )
                    }
                    devices.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                device.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = Lian.TextPrimary,
                            )
                            Text(
                                device.type.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (device.isGpu) Lian.Cyan else Lian.TextMuted,
                            )
                        }
                    }
                    ComputeDevices.gpuUnavailableReason()?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item { SectionTitle("Measured") }
            item {
                BrandCard(Modifier.fillMaxWidth()) {
                    if (!measured.hasRun) {
                        Text(
                            "Not measured yet. The app times a matrix multiply on the CPU " +
                                "and, where there is one, the GPU — a couple of seconds in " +
                                "the background, without asking you to wait for it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lian.TextMuted,
                        )
                    } else {
                        MeasuredRow("CPU", "%.1f GFLOP/s".format(measured.cpuGflops))
                        when {
                            measured.gpuGflops > 0 ->
                                MeasuredRow(
                                    "GPU",
                                    "%.1f GFLOP/s".format(measured.gpuGflops),
                                    highlight = measured.gpuWorthUsing,
                                )
                            measured.gpuCrashed ->
                                MeasuredRow("GPU", "driver fault", danger = true)
                            measured.gpuRejected ->
                                MeasuredRow("GPU", "refused the work", danger = true)
                        }
                        MeasuredRow(
                            "Memory bandwidth",
                            "%.1f GB/s".format(measured.memoryBandwidthGbs),
                        )
                        MeasuredRow(
                            "Storage read",
                            "%.0f MB/s".format(measured.storageReadMbs),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            measured.gpuSpeedup?.let {
                                if (measured.gpuWorthUsing) {
                                    "The GPU is %.1f× the CPU here, so offloading is worth it."
                                        .format(it)
                                } else {
                                    "The GPU is only %.1f× the CPU here. On a phone the two "
                                        .format(it) +
                                        "share one memory bus, so offloading would cost " +
                                        "memory for little gain."
                                }
                            } ?: when {
                                measured.gpuCrashed ->
                                    "A previous GPU probe took the app down with it, so the " +
                                        "GPU is left alone. Re-measure to try again."
                                measured.gpuRejected ->
                                    "The driver enumerated a GPU but would not run the work."
                                else -> "No GPU compute device was offered by the driver."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                        )
                    }
                }
            }

            item { SectionTitle("Memory right now") }
            item {
                BrandCard(Modifier.fillMaxWidth()) {
                    val snap = remember { runtime.memoryBudget.snapshot(runtime.residency.residentBytes()) }
                    Text(snap.summary, style = MaterialTheme.typography.bodySmall, color = Lian.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Loadable now: ${formatBytes(snap.freeForNewModel)}. This is measured, " +
                            "not a share of total RAM, so it moves as you open and close apps.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                    )
                }
            }

            item { SectionTitle("Engine build") }
            item {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        engineInfo ?: "reading…",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = Lian.TextMuted,
                    )
                }
            }

            item {
                GradientButton(
                    text = "Re-check this device",
                    onClick = {
                        scope.launch {
                            runtime.refreshCapability()
                            ComputeDevices.refresh()
                            runtime.benchmarkStore.clearGpuCrash()
                            runtime.ensureBenchmark(force = true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MeasuredRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    danger: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Lian.TextPrimary)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                danger -> Lian.Danger
                highlight -> Lian.Cyan
                else -> Lian.TextMuted
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Lian.TextPrimary,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun StatTile(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    BrandCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Lian.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
    }
}

@Composable
private fun CheckRow(check: CapabilityCheck) {
    val (icon, tint) = when (check.status) {
        CheckStatus.PASS -> Icons.Default.CheckCircle to Lian.Success
        CheckStatus.WARN -> Icons.Default.Warning to Lian.Magenta
        CheckStatus.FAIL -> Icons.Default.Error to Lian.Danger
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = check.status.name, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(check.title, style = MaterialTheme.typography.bodyMedium, color = Lian.TextPrimary)
            Text(check.detail, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
        }
    }
}

/** Kept for the screens that were written against it. */
@Composable
fun rememberCoroutineScopeSafe() = rememberCoroutineScope()
