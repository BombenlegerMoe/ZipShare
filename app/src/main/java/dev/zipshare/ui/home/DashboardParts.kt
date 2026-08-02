package dev.zipshare.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.zipshare.data.net.ZFile
import dev.zipshare.data.net.hasVisualPreview
import dev.zipshare.data.net.previewUrl
import java.util.Locale

/** Zipline's Stat card: dimmed title + icon on one row, big bold value underneath. */
@Composable
fun StatCard(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Reserve room for the icon so a long title truncates instead of colliding.
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A recent-upload card: preview on top, name and metadata below. */
@Composable
fun RecentFileCard(
    file: ZFile,
    baseUrl: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(180.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                // Tapping the preview opens the viewer; the link is copied from the button below.
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (file.hasVisualPreview()) {
                AsyncImage(
                    model = file.previewUrl(baseUrl),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    iconFor(file.type),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.favorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${humanSize(file.size)} - ${file.views} views",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Row(
                Modifier.padding(top = 2.dp).clickable(onClick = onCopy),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Copy link",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        trailing()
    }
}

/** The "File types" table from the Zipline dashboard. */
@Composable
fun FileTypesTable(counts: Map<String, Int>, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("File type", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text("Count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        counts.entries.sortedByDescending { it.value }.forEach { (type, count) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    type,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("$count", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SkeletonBox(height: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(height.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            ),
    )
}

fun iconFor(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.Audiotrack
    mime.startsWith("text/") -> Icons.Filled.Description
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") ->
        Icons.Filled.FolderZip
    // A padlock here used to imply the file was password protected. It just means "some file".
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

fun humanSize(bytes: Double): String = humanSize(bytes.toLong())
