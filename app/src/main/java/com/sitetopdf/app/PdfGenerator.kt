package com.sitetopdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Genera un PDF por cada página web.
 *
 * Android no permite construir un PrintDocumentAdapter "a mano" (las clases
 * LayoutResultCallback/WriteResultCallback tienen constructor package-private,
 * de uso exclusivo del framework de impresión). Por eso, en vez de usar la
 * impresión del sistema, esta clase:
 *  1) Mide y dibuja el WebView COMPLETO (no solo lo visible) en un Bitmap de
 *     alta resolución, tal como se ve renderizado (texto, imágenes, estilos).
 *  2) Detecta con JavaScript la posición de cada <a href> visible.
 *  3) Construye el PDF con PDFBox, cortando el bitmap en páginas A4 y
 *     superponiendo anotaciones de enlace (PDAnnotationLink) clicables en la
 *     posición exacta de cada link.
 *
 * Resultado: el PDF se ve igual que la página y los enlaces son clicables;
 * el texto queda "quemado" en la imagen (no es seleccionable/copiable).
 */
object PdfGenerator {

    private const val CAPTURE_WIDTH_PX = 1200
    private const val PAGE_WIDTH_PT = 595f  // A4
    private const val PAGE_HEIGHT_PT = 842f // A4

    private data class LinkRect(val href: String, val left: Float, val top: Float, val width: Float, val height: Float)

    suspend fun loadPage(webView: WebView, url: String, timeoutMs: Long = 25_000): Boolean =
        suspendCancellableCoroutine { cont ->
            var finished = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!finished) {
                        finished = true
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

    /** Mide y dibuja el WebView completo (no solo el área visible) en un Bitmap. */
    private fun captureFullPage(webView: WebView): Pair<Bitmap, Float> {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(CAPTURE_WIDTH_PX, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, CAPTURE_WIDTH_PX, webView.measuredHeight)

        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        val contentHeightPx = (webView.contentHeight * scale).toInt().coerceAtLeast(1)

        webView.layout(0, 0, CAPTURE_WIDTH_PX, contentHeightPx)

        val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH_PX, contentHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        webView.draw(canvas)
        return bitmap to scale
    }

    /** Extrae la posición (en px CSS) de cada enlace visible de la página. */
    private suspend fun extractLinks(webView: WebView): List<LinkRect> = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var out = [];
                var anchors = document.querySelectorAll('a[href]');
                for (var i = 0; i < anchors.length; i++) {
                    var a = anchors[i];
                    var r = a.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) {
                        out.push({
                            href: a.href,
                            left: r.left,
                            top: r.top,
                            width: r.width,
                            height: r.height
                        });
                    }
                }
                return JSON.stringify(out);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { rawResult ->
            try {
                val unescaped = rawResult
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?: "[]"
                val arr = JSONArray(unescaped)
                val links = mutableListOf<LinkRect>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    links.add(
                        LinkRect(
                            href = o.getString("href"),
                            left = o.getDouble("left").toFloat(),
                            top = o.getDouble("top").toFloat(),
                            width = o.getDouble("width").toFloat(),
                            height = o.getDouble("height").toFloat()
                        )
                    )
                }
                if (cont.isActive) cont.resume(links)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    /** Genera un PDF (una o varias páginas A4) a partir del contenido actual del WebView. */
    suspend fun renderToPdf(context: Context, webView: WebView, outputFile: File): Boolean {
        return try {
            val (bitmap, scale) = captureFullPage(webView)
            val links = extractLinks(webView)

            PDFBoxResourceLoader.init(context.applicationContext)
            val document = PDDocument()

            val pxToPt = PAGE_WIDTH_PT / bitmap.width
            val pageHeightPx = (PAGE_HEIGHT_PT / pxToPt).toInt().coerceAtLeast(1)

            var yOffset = 0
            while (yOffset < bitmap.height) {
                val sliceHeight = min(pageHeightPx, bitmap.height - yOffset)
                val slice = Bitmap.createBitmap(bitmap, 0, yOffset, bitmap.width, sliceHeight)

                val page = PDPage(PDRectangle(PAGE_WIDTH_PT, sliceHeight * pxToPt))
                document.addPage(page)

                val image = LosslessFactory.createFromImage(document, slice)
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(image, 0f, 0f, PAGE_WIDTH_PT, sliceHeight * pxToPt)
                }

                for (link in links) {
                    val linkTopPx = link.top * scale
                    val linkBottomPx = (link.top + link.height) * scale
                    if (linkBottomPx <= yOffset || linkTopPx >= yOffset + sliceHeight) continue

                    val localTopPx = (linkTopPx - yOffset).coerceAtLeast(0f)
                    val localBottomPx = (linkBottomPx - yOffset).coerceAtMost(sliceHeight.toFloat())
                    val leftPt = (link.left * scale) * pxToPt
                    val widthPt = (link.width * scale) * pxToPt
                    val topPt = (sliceHeight - localTopPx) * pxToPt
                    val bottomPt = (sliceHeight - localBottomPx) * pxToPt

                    val annotation = PDAnnotationLink()
                    annotation.rectangle = PDRectangle(leftPt, bottomPt, widthPt, (topPt - bottomPt))
                    val border = PDBorderStyleDictionary()
                    border.width = 0f
                    annotation.borderStyle = border
                    val action = PDActionURI()
                    action.uri = link.href
                    annotation.action = action
                    page.annotations.add(annotation)
                }

                yOffset += sliceHeight
            }

            if (document.numberOfPages == 0) {
                document.addPage(PDPage(PDRectangle(PAGE_WIDTH_PT, PAGE_HEIGHT_PT)))
            }

            document.save(outputFile)
            document.close()
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
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
