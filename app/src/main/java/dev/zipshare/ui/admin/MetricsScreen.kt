package dev.zipshare.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.ui.home.SectionHeader
import dev.zipshare.ui.home.StatCard
import dev.zipshare.ui.home.humanSize
import dev.zipshare.ui.shell.EmptyOrError
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar

@Composable
fun MetricsScreen(onMenu: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.active?.id) { if (state.active != null) vm.loadStats() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ShellTopBar(
                title = "Metrics",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        val latest = state.stats?.latest?.data
        PullRefresh(
            refreshing = state.loading,
            onRefresh = { vm.loadStats(state.allTime) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (state.stats == null && !state.loading) {
                // Metrics can be disabled instance-wide (E3001) or admin-only (E3000);
                // the snackbar carries the server's own wording.
                EmptyOrError("No metrics available for this server.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !state.allTime,
                                onClick = { vm.loadStats(false) },
                                label = { Text("Last 7 days") },
                            )
                            FilterChip(
                                selected = state.allTime,
                                onClick = { vm.loadStats(true) },
                                label = { Text("All time") },
                            )
                        }
                    }

                    if (latest != null) {
                        val cards = listOf(
                            Triple("Users", latest.users.toString(), Icons.Filled.Group),
                            Triple("Files", latest.files.toString(), Icons.AutoMirrored.Filled.InsertDriveFile),
                            Triple("Storage", humanSize(latest.storage), Icons.Filled.SdStorage),
                            Triple("File views", latest.fileViews.toString(), Icons.Filled.Visibility),
                            Triple("URLs", latest.urls.toString(), Icons.Filled.Link),
                            Triple("URL views", latest.urlViews.toString(), Icons.Filled.Visibility),
                        )
                        items@ for (row in cards.chunked(2)) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    row.forEach { (t, v, i) -> StatCard(t, v, i, Modifier.weight(1f)) }
                                    if (row.size == 1) Box(Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    val points = state.stats?.points.orEmpty()
                    if (points.size > 1) {
                        item { SectionHeader("Files over time") }
                        item {
                            MetricChart(
                                values = points.map { it.files.toDouble() }.reversed(),
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                        }
                        item { SectionHeader("Storage over time") }
                        item {
                            MetricChart(
                                values = points.map { it.storage?.toDoubleOrNull() ?: 0.0 }.reversed(),
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                        }
                    }

                    if (!latest?.types.isNullOrEmpty()) {
                        item { SectionHeader("Types") }
                        item {
                            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                latest!!.types.sortedByDescending { it.sum }.take(15).forEach { t ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            t.type,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Text("${t.sum}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    if (!latest?.filesUsers.isNullOrEmpty()) {
                        item { SectionHeader("Files by user") }
                        item {
                            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                latest!!.filesUsers.sortedByDescending { it.sum }.forEach { u ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            u.username ?: "(deleted)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            "${u.sum} files - ${humanSize(u.storage)} - ${u.views} views",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Small line chart. A dependency-free Canvas beats pulling in a charting library for this. */
@Composable
private fun MetricChart(values: List<Double>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.18f)
    OutlinedCard(modifier, shape = RoundedCornerShape(12.dp)) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            if (values.size < 2) return@Canvas
            val max = values.max().takeIf { it > 0 } ?: 1.0
            val min = values.min().coerceAtMost(0.0)
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            val stepX = size.width / (values.size - 1)

            fun yFor(v: Double) = (size.height - ((v - min) / span * size.height)).toFloat()

            val path = Path().apply {
                moveTo(0f, yFor(values.first()))
                values.forEachIndexed { i, v -> if (i > 0) lineTo(stepX * i, yFor(v)) }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, color = fill)
            drawPath(path, color = line, style = Stroke(width = 3f))
            drawCircle(line, radius = 5f, center = Offset(stepX * (values.size - 1), yFor(values.last())))
            drawLine(
                Color.Gray.copy(alpha = 0.35f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }
    }
}
