package dev.zipshare.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares an export through the FileProvider, granting read for this one intent only. */
internal fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export"))
}
