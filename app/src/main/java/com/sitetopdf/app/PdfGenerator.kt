package com.sitetopdf.app

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Convierte cada página web (renderizada en un WebView) a un PDF individual usando
 * el motor de impresión de Chromium/WebView (conserva texto, imágenes y enlaces
 * como anotaciones), y luego fusiona todos los PDFs en uno solo, en orden, usando
 * PDFBox (fusión real de páginas, no un "screenshot": los enlaces siguen siendo
 * clicables).
 */
object PdfGenerator {

    suspend fun loadPage(webView: WebView, url: String, timeoutMs: Long = 25_000): Boolean =
        suspendCancellableCoroutine { cont ->
            var finished = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!finished) {
                        finished = true
                        // Pequeño margen para que terminen de cargar imágenes diferidas
                        view?.postDelayed({
                            if (cont.isActive) cont.resume(true)
                        }, 600)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    if (!finished) {
                        finished = true
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            webView.loadUrl(url)

            webView.postDelayed({
                if (!finished && cont.isActive) {
                    finished = true
                    cont.resume(true)
                }
            }, timeoutMs)
        }

    suspend fun renderToPdf(webView: WebView, outputFile: File): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val adapter = webView.createPrintDocumentAdapter(outputFile.nameWithoutExtension)
                val attributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                    .setMinMargins(PrintAttributes.Margins(20, 20, 20, 20))
                    .build()

                adapter.onLayout(
                    null,
                    attributes,
                    null,
                    object : PrintDocumentAdapter.LayoutResultCallback() {
                        override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                            try {
                                val pfd = ParcelFileDescriptor.open(
                                    outputFile,
                                    ParcelFileDescriptor.MODE_CREATE or
                                        ParcelFileDescriptor.MODE_TRUNCATE or
                                        ParcelFileDescriptor.MODE_READ_WRITE
                                )
                                adapter.onWrite(
                                    arrayOf(PageRange.ALL_PAGES),
                                    pfd,
                                    CancellationSignal(),
                                    object : PrintDocumentAdapter.WriteResultCallback() {
                                        override fun onWriteFinished(pages: Array<out PageRange>?) {
                                            try { pfd.close() } catch (_: Exception) {}
                                            if (cont.isActive) cont.resume(true)
                                        }

                                        override fun onWriteFailed(error: CharSequence?) {
                                            try { pfd.close() } catch (_: Exception) {}
                                            if (cont.isActive) cont.resume(false)
                                        }
                                    }
                                )
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resume(false)
                            }
                        }

                        override fun onLayoutFailed(error: CharSequence?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    fun mergePdfs(context: Context, sourceFiles: List<File>, outputFile: File): Boolean {
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val merger = PDFMergerUtility()
            sourceFiles.forEach { merger.addSource(it) }
            merger.destinationFileName = outputFile.absolutePath
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            true
        } catch (e: Exception) {
            false
        }
    }
}
