package com.sitetopdf.app

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File

suspend fun generatePdf(
    context: Context,
    webView: WebView,
    items: List<PageItem>,
    onProgress: (progress: Float, text: String) -> Unit
): File? {
    val tempDir = File(context.cacheDir, "pdf-pages").apply {
        deleteRecursively()
        mkdirs()
    }
    val partFiles = mutableListOf<File>()
    val total = items.size

    items.forEachIndexed { index, item ->
        onProgress(index / total.toFloat(), "Cargando ${index + 1} de $total: ${item.title}")
        val loaded = PdfGenerator.loadPage(webView, item.url)
        if (loaded) {
            onProgress((index + 0.5f) / total.toFloat(), "Generando página ${index + 1} de $total")
            val partFile = File(tempDir, "page-${index.toString().padStart(4, '0')}.pdf")
            val ok = PdfGenerator.renderToPdf(webView, partFile)
            if (ok) partFiles.add(partFile)
        }
    }

    if (partFiles.isEmpty()) {
        tempDir.deleteRecursively()
        return null
    }

    onProgress(0.95f, "Uniendo ${partFiles.size} páginas en un solo PDF…")
    val outputDir = File(context.getExternalFilesDir(null), "SiteToPDF").apply { mkdirs() }
    val outputFile = File(outputDir, "sitio-completo-${System.currentTimeMillis()}.pdf")
    val merged = PdfGenerator.mergePdfs(context, partFiles, outputFile)
    tempDir.deleteRecursively()

    return if (merged) outputFile else null
}

fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
