package com.sitetopdf.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.TimeUnit

data class DiscoveredPage(val url: String, val title: String)

/**
 * Recorre un sitio en anchura (BFS) a partir de una URL inicial, siguiendo todos los
 * enlaces <a href> que encuentra, y devuelve las páginas en el orden en que fueron
 * descubiertas. Se limita al mismo dominio por defecto.
 */
class SiteCrawler(
    private val maxPages: Int = 200,
    private val sameOriginOnly: Boolean = true,
    private val onProgress: (visited: Int, currentUrl: String) -> Unit = { _, _ -> }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun crawl(startUrl: String): List<DiscoveredPage> = withContext(Dispatchers.IO) {
        val normalizedStart = normalize(startUrl, startUrl) ?: return@withContext emptyList()
        val origin = originOf(normalizedStart)
        val visited = LinkedHashSet<String>()
        val result = mutableListOf<DiscoveredPage>()
        val queue = ArrayDeque<String>()
        queue.add(normalizedStart)

        while (queue.isNotEmpty() && visited.size < maxPages) {
            val current = queue.removeFirst()
            if (visited.contains(current)) continue
            visited.add(current)
            onProgress(visited.size, current)

            val doc = fetch(current) ?: continue
            val title = doc.title().ifBlank { current }
            result.add(DiscoveredPage(current, title))

            val links = doc.select("a[href]")
            for (link in links) {
                val href = link.attr("href")
                val abs = normalize(href, current) ?: continue
                if (sameOriginOnly && originOf(abs) != origin) continue
                if (!visited.contains(abs) && !queue.contains(abs)) {
                    queue.add(abs)
                }
            }
        }
        result
    }

    private fun fetch(url: String): Document? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; SiteToPDF App)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val contentType = response.header("Content-Type") ?: ""
            if (contentType.isNotBlank() && !contentType.contains("text/html")) return null
            val body = response.body?.string() ?: return null
            Jsoup.parse(body, url)
        }
    } catch (e: Exception) {
        null
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
