@file:OptIn(ExperimentalMaterial3Api::class)

package com.sitetopdf.app

import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sitetopdf.app.ui.theme.SiteToPdfTheme
import kotlinx.coroutines.launch

sealed class AppState {
    data object Idle : AppState()
    data class Crawling(val visited: Int, val currentUrl: String) : AppState()
    data class Crawled(val pages: List<PageItem>) : AppState()
    data class Generating(val progress: Float, val text: String) : AppState()
    data class Done(val uri: Uri, val displayName: String) : AppState()
    data class Error(val message: String) : AppState()
}

data class PageItem(val url: String, val title: String, val selected: Boolean = true)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SiteToPdfTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SiteToPdfApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteToPdfApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf("") }
    var appState by remember { mutableStateOf<AppState>(AppState.Idle) }
    var pages by remember { mutableStateOf(listOf<PageItem>()) }
    var crawledSiteUrl by remember { mutableStateOf("") }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // WebView interno oculto, usado solo para renderizar cada página a PDF
        AndroidView(
            factory = { webView },
            modifier = Modifier.size(360.dp, 640.dp)
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sitio a PDF", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("URL del sitio", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://ejemplo.com") },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                            singleLine = true,
                            enabled = appState !is AppState.Crawling && appState !is AppState.Generating
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val normalized = normalizeInputUrl(urlText)
                                if (normalized == null) {
                                    appState = AppState.Error("Escribe una URL válida, por ejemplo https://ejemplo.com")
                                    return@Button
                                }
                                appState = AppState.Crawling(0, normalized)
                                scope.launch {
                                    val crawler = SiteCrawler(webView, maxPages = 200, sameOriginOnly = true)
                                    val discovered = crawler.crawl(normalized) { visited, currentUrl ->
                                        appState = AppState.Crawling(visited, currentUrl)
                                    }
                                    if (discovered.isEmpty()) {
                                        appState = AppState.Error(
                                            "No se pudo cargar ninguna página de ese sitio. Puede ser un problema de conexión, " +
                                                "que el sitio bloquee la carga, o que no tenga contenido accesible."
                                        )
                                    } else {
                                        pages = discovered.map { PageItem(it.url, it.title, true) }
                                        crawledSiteUrl = normalized
                                        appState = AppState.Crawled(pages)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = appState !is AppState.Crawling && appState !is AppState.Generating
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (appState is AppState.Crawling) "Buscando páginas…" else "Detectar páginas")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                when (val state = appState) {
                    is AppState.Idle -> EmptyHint()

                    is AppState.Crawling -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                val label = if (state.visited == 0) "Cargando la página inicial…"
                                else "Página ${state.visited} encontrada…"
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.currentUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    is AppState.Crawled -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${pages.count { it.selected }} de ${pages.size} páginas seleccionadas",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = {
                                val allSelected = pages.all { it.selected }
                                pages = pages.map { it.copy(selected = !allSelected) }
                            }) {
                                Text(if (pages.all { it.selected }) "Ninguna" else "Todas")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(pages, key = { it.url }) { item ->
                                PageRow(
                                    item = item,
                                    onToggle = {
                                        pages = pages.map { p ->
                                            if (p.url == item.url) p.copy(selected = !p.selected) else p
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val selected = pages.filter { it.selected }
                                if (selected.isEmpty()) {
                                    appState = AppState.Error("Selecciona al menos una página.")
                                    return@Button
                                }
                                scope.launch {
                                    val result = generatePdf(context, webView, selected, crawledSiteUrl) { progress, text ->
                                        appState = AppState.Generating(progress, text)
                                    }
                                    appState = if (result != null) AppState.Done(result.first, result.second)
                                    else AppState.Error(
                                        "No se pudo generar el PDF. Puede haber sido un problema de memoria " +
                                            "(sitios muy grandes) o de conexión al descargar imágenes."
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Generar PDF")
                        }
                    }

                    is AppState.Generating -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(state.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    is AppState.Done -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("¡PDF generado con éxito!", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(state.displayName, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Guardado en Descargas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { sharePdf(context, state.uri) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Compartir / Abrir PDF")
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    appState = AppState.Idle
                                    urlText = ""
                                    pages = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Convertir otro sitio")
                            }
                        }
                    }

                    is AppState.Error -> {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { appState = AppState.Idle }) {
                                    Text("Entendido")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Escribe la URL de un sitio y toca \"Detectar páginas\" para empezar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun PageRow(item: PageItem, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1) },
        supportingContent = { Text(item.url, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
        trailingContent = {
            Checkbox(checked = item.selected, onCheckedChange = { onToggle() })
        },
        modifier = Modifier.clickable(onClick = onToggle)
    )
    HorizontalDivider()
}
