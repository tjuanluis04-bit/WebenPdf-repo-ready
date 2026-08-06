package com.sitetopdf.app

import android.webkit.WebView
import java.net.URI

data class DiscoveredPage(val url: String, val title: String)

/**
 * Recorre un sitio en anchura (BFS) usando un WebView real (motor Chromium),
 * igual que un navegador normal: ejecuta JavaScript y sigue los mismos
 * enlaces que vería una persona navegando. Esto evita los bloqueos de sitios
 * que rechazan clientes HTTP simples (bots), aunque el sitio funcione bien
 * en un navegador de verdad.
 */
class SiteCrawler(
    private val webView: WebView,
    private val maxPages: Int = 200,
    private val sameOriginOnly: Boolean = true
) {
    suspend fun crawl(
        startUrl: String,
        onProgress: (visited: Int, currentUrl: String) -> Unit = { _, _ -> }
    ): List<DiscoveredPage> {
        val normalizedStart = normalize(startUrl, startUrl) ?: return emptyList()
        val origin = originOf(normalizedStart)
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(normalizedStart)
        val result = mutableListOf<DiscoveredPage>()

        while (queue.isNotEmpty() && visited.size < maxPages) {
            val current = queue.removeFirst()
            if (visited.contains(current)) continue
            visited.add(current)
            onProgress(visited.size, current)

            val loaded = PdfGenerator.loadPage(webView, current)
            if (!loaded) continue

            val title = webView.title?.takeIf { it.isNotBlank() } ?: current
            result.add(DiscoveredPage(current, title))

            val links = PdfGenerator.extractLinks(webView)
            for (href in links) {
                val abs = normalize(href, current) ?: continue
                if (sameOriginOnly && originOf(abs) != origin) continue
                if (!visited.contains(abs) && !queue.contains(abs)) queue.add(abs)
            }
        }
        return result
    }

    private fun normalize(href: String, base: String): String? {
        if (href.isBlank()) return null
        if (href.startsWith("mailto:") || href.startsWith("tel:") ||
            href.startsWith("javascript:") || href.startsWith("#")
        ) return null
        return try {
            val resolved = URI(base).resolve(href).normalize()
            if (resolved.scheme != "http" && resolved.scheme != "https") return null
            val builder = StringBuilder()
            builder.append(resolved.scheme).append("://").append(resolved.authority)
            builder.append(resolved.rawPath.ifBlank { "/" })
            if (resolved.rawQuery != null) builder.append("?").append(resolved.rawQuery)
            builder.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun originOf(url: String): String = try {
        val u = URI(url)
        "${u.scheme}://${u.authority}"
    } catch (e: Exception) {
        url
    }
}
