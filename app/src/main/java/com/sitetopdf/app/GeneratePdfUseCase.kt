package com.sitetopdf.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import java.io.File

suspend fun generatePdf(
    context: Context,
    webView: WebView,
    items: List<PageItem>,
    siteUrl: String,
    onProgress: (progress: Float, text: String) -> Unit
): Pair<Uri, String>? {
    val tempDir = File(context.cacheDir, "pdf-pages").apply {
        deleteRecursively()
        mkdirs()
    }
    val partFiles = mutableListOf<File>()
    val total = items.size

    items.forEachIndexed { index, item ->
        try {
            onProgress(index / total.toFloat(), "Cargando ${index + 1} de $total: ${item.title}")
            val loaded = PdfGenerator.loadPage(webView, item.url)
            if (loaded) {
                onProgress((index + 0.5f) / total.toFloat(), "Generando página ${index + 1} de $total")
                val partFile = File(tempDir, "page-${index.toString().padStart(4, '0')}.pdf")
                val ok = PdfGenerator.renderToPdf(context, webView, partFile)
                if (ok) partFiles.add(partFile)
            }
        } catch (e: Throwable) {
            // Se salta esta página (incluye errores de memoria) y sigue con las demás,
            // para que una sola página problemática no tumbe toda la generación.
        }
    }

    if (partFiles.isEmpty()) {
        tempDir.deleteRecursively()
        return null
    }

    onProgress(0.95f, "Uniendo ${partFiles.size} páginas en un solo PDF…")
    val mergedFile = File(tempDir, "merged.pdf")
    val merged = try {
        PdfGenerator.mergePdfs(context, partFiles, mergedFile)
    } catch (e: Throwable) {
        false
    }

    if (!merged) {
        tempDir.deleteRecursively()
        return null
    }

    val displayName = siteDisplayName(siteUrl)
    val uri = try {
        saveToDownloads(context, mergedFile, displayName)
    } catch (e: Throwable) {
        null
    }

    tempDir.deleteRecursively()
    return uri?.let { it to displayName }
}

/** Guarda el PDF en la carpeta pública Descargas (Download/) con el nombre del sitio. */
private fun saveToDownloads(context: Context, sourceFile: File, displayName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    val opened = resolver.openOutputStream(uri)?.use { out ->
        sourceFile.inputStream().use { input -> input.copyTo(out) }
        true
    } ?: false
    return if (opened) uri else null
}

/** Construye un nombre de archivo legible a partir del dominio del sitio, p. ej. "menstribune.com.pdf". */
private fun siteDisplayName(url: String): String {
    val host = try {
        java.net.URI(url).host ?: url
    } catch (e: Exception) {
        url
    }
    val cleaned = host.removePrefix("www.")
        .map { c -> if (c.isLetterOrDigit() || c == '.' || c == '-') c else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "sitio" }
    return "$cleaned.pdf"
}

fun sharePdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir PDF"))
}

fun normalizeInputUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "https://$trimmed"
    } else trimmed
    return try {
        java.net.URI(withScheme)
        withScheme
    } catch (e: Exception) {
        null
    }
}
