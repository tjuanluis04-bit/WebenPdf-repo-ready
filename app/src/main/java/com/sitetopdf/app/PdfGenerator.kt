package com.sitetopdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class TextRun(val text: String, val href: String?)

sealed class Block {
    data class Text(val kind: String, val level: Int, val runs: List<TextRun>) : Block()
    data class Image(val src: String, val alt: String) : Block()
}

/**
 * Genera un PDF de TEXTO REAL (copiable) a partir del contenido de una página web,
 * respetando el orden del documento: encabezados, párrafos con sus enlaces
 * incrustados palabra por palabra como texto clicable, e imágenes descargadas
 * tal como aparecen en la página (no es una captura de pantalla).
 *
 * La carga de páginas usa un WebView real (motor Chromium), igual que un
 * navegador normal, para evitar bloqueos de sitios que detectan clientes HTTP
 * simples como bots.
 */
object PdfGenerator {

    const val PAGE_WIDTH_PT = 595f  // A4
    const val PAGE_HEIGHT_PT = 842f // A4

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun loadPage(webView: WebView, url: String, timeoutMs: Long = 25_000): Boolean =
        suspendCancellableCoroutine { cont ->
            var finished = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!finished) {
                        finished = true
                        view?.postDelayed({ if (cont.isActive) cont.resume(true) }, 500)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                ) {
                    if (!finished) { finished = true; if (cont.isActive) cont.resume(false) }
                }
            }
            webView.loadUrl(url)
            webView.postDelayed({
                if (!finished && cont.isActive) { finished = true; cont.resume(true) }
            }, timeoutMs)
        }

    /** Extrae los enlaces (href absolutos) de la página actualmente cargada, incluyendo frames/iframes. */
    suspend fun extractLinks(webView: WebView): List<String> = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var out = [];
                var visitedDocs = [];
                function collect(doc) {
                    if (!doc || visitedDocs.indexOf(doc) !== -1) return;
                    visitedDocs.push(doc);
                    try {
                        var anchors = doc.querySelectorAll('a[href]');
                        for (var i = 0; i < anchors.length; i++) out.push(anchors[i].href);
                    } catch (e) {}
                    try {
                        var frames = doc.querySelectorAll('frame, iframe');
                        for (var j = 0; j < frames.length; j++) {
                            try {
                                var innerDoc = frames[j].contentDocument;
                                if (innerDoc) collect(innerDoc);
                            } catch (e) {}
                        }
                    } catch (e) {}
                }
                collect(document);
                return JSON.stringify(out);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            try {
                val arr = JSONArray(unescapeJs(raw))
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                if (cont.isActive) cont.resume(list)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    /**
     * Extrae el contenido en orden: encabezados, párrafos (con enlaces por palabra) e imágenes.
     * Entra recursivamente en <frame>/<iframe> del mismo dominio, ya que varios sitios antiguos
     * (como el de este caso) guardan el contenido real dentro de un frame en vez del documento raíz.
     */
    suspend fun extractBlocks(webView: WebView): List<Block> = suspendCancellableCoroutine { cont ->
        val js = """
            (function() {
                var blocks = [];
                var runs = [];
                var curType = 'paragraph';
                var curLevel = 0;

                function flush() {
                    var hasText = false;
                    for (var i = 0; i < runs.length; i++) {
                        if (runs[i].text.trim().length > 0) { hasText = true; break; }
                    }
                    if (hasText) blocks.push({type: curType, level: curLevel, runs: runs});
                    runs = [];
                    curType = 'paragraph';
                    curLevel = 0;
                }

                var blockTags = {P:1,DIV:1,LI:1,BLOCKQUOTE:1,SECTION:1,ARTICLE:1,HEADER:1,FOOTER:1,UL:1,OL:1,TR:1,TABLE:1};
                var headingTags = {H1:1,H2:2,H3:3,H4:4,H5:5,H6:6};

                function visit(node, href) {
                    if (!node) return;
                    if (node.nodeType === 3) {
                        var t = node.textContent.replace(/\s+/g, ' ');
                        if (t.trim().length > 0) runs.push({text: t, href: href || null});
                        return;
                    }
                    if (node.nodeType !== 1) return;
                    var tag = node.tagName;
                    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return;
                    var cs = null;
                    try { cs = window.getComputedStyle(node); } catch (e) {}
                    if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) return;

                    if (tag === 'FRAME' || tag === 'IFRAME') {
                        flush();
                        try {
                            var innerDoc = node.contentDocument;
                            if (innerDoc) visit(innerDoc.body || innerDoc.documentElement, href);
                        } catch (e) {}
                        return;
                    }

                    if (tag === 'IMG') {
                        flush();
                        var src = node.currentSrc || node.src;
                        if (src) blocks.push({type: 'image', src: src, alt: node.alt || ''});
                        return;
                    }
                    if (tag === 'BR') { runs.push({text: '\n', href: null}); return; }

                    var newHref = href;
                    if (tag === 'A' && node.href) newHref = node.href;

                    if (headingTags[tag]) {
                        flush();
                        curType = 'heading';
                        curLevel = headingTags[tag];
                        for (var i = 0; i < node.childNodes.length; i++) visit(node.childNodes[i], newHref);
                        flush();
                        return;
                    }
                    if (blockTags[tag]) {
                        flush();
                        for (var j = 0; j < node.childNodes.length; j++) visit(node.childNodes[j], newHref);
                        flush();
                        return;
                    }
                    for (var k = 0; k < node.childNodes.length; k++) visit(node.childNodes[k], newHref);
                }

                visit(document.body || document.documentElement, null);
                flush();
                return JSON.stringify(blocks);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            try {
                val arr = JSONArray(unescapeJs(raw))
                val blocks = mutableListOf<Block>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    when (o.getString("type")) {
                        "image" -> blocks.add(Block.Image(o.optString("src"), o.optString("alt")))
                        else -> {
                            val runsArr = o.getJSONArray("runs")
                            val runs = mutableListOf<TextRun>()
                            for (j in 0 until runsArr.length()) {
                                val r = runsArr.getJSONObject(j)
                                runs.add(TextRun(r.getString("text"), r.optString("href").ifBlank { null }))
                            }
                            blocks.add(Block.Text(o.getString("type"), o.optInt("level", 0), runs))
                        }
                    }
                }
                if (cont.isActive) cont.resume(blocks)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    private fun unescapeJs(raw: String?): String {
        if (raw == null || raw == "null") return "[]"
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private suspend fun downloadBitmap(url: String, maxWidthPx: Int = 900): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android)").build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxWidthPx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Genera un PDF de texto real (una o varias páginas A4) a partir del contenido actual del WebView. */
    suspend fun renderToPdf(context: Context, webView: WebView, outputFile: File): Boolean {
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val blocks = extractBlocks(webView)
            if (blocks.isEmpty()) return false

            val document = PDDocument()
            val writer = PdfPageWriter(document)

            for (block in blocks) {
                when (block) {
                    is Block.Text -> writer.drawText(block)
                    is Block.Image -> {
                        val bitmap = downloadBitmap(block.src)
                        if (bitmap != null) {
                            writer.drawImage(bitmap)
                            bitmap.recycle()
                        }
                    }
                }
            }
            writer.finish()
            document.save(outputFile)
            document.close()
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

/** Construye el PDF de texto real, paginando automáticamente en A4. */
private class PdfPageWriter(private val document: PDDocument) {
    private val marginX = 50f
    private val marginTop = 50f
    private val marginBottom = 50f
    private val contentWidth = PdfGenerator.PAGE_WIDTH_PT - marginX * 2

    private var page: PDPage
    private var stream: PDPageContentStream
    private var cursorY: Float

    init {
        page = PDPage(PDRectangle(PdfGenerator.PAGE_WIDTH_PT, PdfGenerator.PAGE_HEIGHT_PT))
        document.addPage(page)
        stream = PDPageContentStream(document, page)
        cursorY = PdfGenerator.PAGE_HEIGHT_PT - marginTop
    }

    fun finish() {
        stream.close()
    }

    private fun newPage() {
        stream.close()
        page = PDPage(PDRectangle(PdfGenerator.PAGE_WIDTH_PT, PdfGenerator.PAGE_HEIGHT_PT))
        document.addPage(page)
        stream = PDPageContentStream(document, page)
        cursorY = PdfGenerator.PAGE_HEIGHT_PT - marginTop
    }

    private fun ensureSpace(height: Float) {
        if (cursorY - height < marginBottom) newPage()
    }

    fun drawText(block: Block.Text) {
        val isHeading = block.kind == "heading"
        val font = if (isHeading) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        val fontSize = when {
            !isHeading -> 11f
            block.level <= 1 -> 20f
            block.level == 2 -> 17f
            block.level == 3 -> 15f
            block.level == 4 -> 13f
            else -> 12f
        }
        val lineHeight = fontSize * 1.35f
        val tokens = tokenize(block.runs)
        if (tokens.isEmpty()) return
        val lines = wrap(tokens, font, fontSize, contentWidth)

        if (isHeading) { ensureSpace(lineHeight); cursorY -= 6f }

        val spaceWidth = font.getStringWidth(" ") / 1000f * fontSize
        for (line in lines) {
            ensureSpace(lineHeight)
            val safeWords = line.map { safe(it.word, font) }
            val lineText = safeWords.joinToString(" ")
            try {
                stream.beginText()
                stream.setFont(font, fontSize)
                stream.newLineAtOffset(marginX, cursorY)
                stream.showText(lineText)
                stream.endText()
            } catch (e: Exception) { /* ignora la línea si falla el dibujo */ }

            var x = marginX
            for ((idx, tok) in line.withIndex()) {
                val w = try { font.getStringWidth(safeWords[idx]) / 1000f * fontSize } catch (e: Exception) { 0f }
                if (tok.href != null && w > 0f) {
                    addLink(page, x, cursorY - 2f, w, fontSize + 3f, tok.href)
                }
                x += w + spaceWidth
            }
            cursorY -= lineHeight
        }
        cursorY -= if (isHeading) 8f else 10f
    }

    fun drawImage(bitmap: Bitmap) {
        val maxContentHeight = PdfGenerator.PAGE_HEIGHT_PT - marginTop - marginBottom
        var w = bitmap.width.toFloat()
        var h = bitmap.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val widthScale = contentWidth / w
        w *= widthScale
        h *= widthScale
        if (h > maxContentHeight) {
            val heightScale = maxContentHeight / h
            w *= heightScale
            h *= heightScale
        }
        ensureSpace(h + 6f)
        try {
            val image = LosslessFactory.createFromImage(document, bitmap)
            stream.drawImage(image, marginX, cursorY - h, w, h)
        } catch (e: Exception) { /* omite la imagen si falla */ }
        cursorY -= (h + 12f)
    }

    private fun addLink(targetPage: PDPage, x: Float, y: Float, width: Float, height: Float, href: String) {
        try {
            val annotation = PDAnnotationLink()
            annotation.rectangle = PDRectangle(x, y, width, height)
            val border = PDBorderStyleDictionary()
            border.width = 0f
            annotation.borderStyle = border
            val action = PDActionURI()
            action.uri = href
            annotation.action = action
            targetPage.annotations.add(annotation)
        } catch (e: Exception) { /* ignora */ }
    }

    private data class Token(val word: String, val href: String?)

    private fun tokenize(runs: List<TextRun>): List<Token> {
        val tokens = mutableListOf<Token>()
        for (run in runs) {
            if (run.text == "\n") { tokens.add(Token("\n", null)); continue }
            val words = run.text.trim().split(Regex("\\s+"))
            for (w in words) if (w.isNotEmpty()) tokens.add(Token(w, run.href))
        }
        return tokens
    }

    private fun wrap(tokens: List<Token>, font: PDType1Font, fontSize: Float, maxWidth: Float): List<List<Token>> {
        val lines = mutableListOf<MutableList<Token>>()
        var current = mutableListOf<Token>()
        var currentWidth = 0f
        val spaceWidth = font.getStringWidth(" ") / 1000f * fontSize

        fun pushLine() {
            if (current.isNotEmpty()) lines.add(current)
            current = mutableListOf()
            currentWidth = 0f
        }

        for (tok in tokens) {
            if (tok.word == "\n") { pushLine(); continue }
            val safeWord = safe(tok.word, font)
            val w = try { font.getStringWidth(safeWord) / 1000f * fontSize } catch (e: Exception) { 0f }
            val extraIfAppend = if (current.isEmpty()) w else spaceWidth + w
            if (current.isNotEmpty() && currentWidth + extraIfAppend > maxWidth) {
                pushLine()
            }
            val extraFinal = if (current.isEmpty()) w else spaceWidth + w
            current.add(tok)
            currentWidth += extraFinal
        }
        pushLine()
        return lines
    }

    private fun safe(text: String, font: PDType1Font): String {
        return try {
            font.getStringWidth(text)
            text
        } catch (e: Exception) {
            text.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
        }
    }
}
