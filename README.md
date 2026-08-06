# Sitio a PDF — App Android

App nativa Android (Kotlin + Jetpack Compose + Material 3) que:

1. Recibe una URL.
2. **Detecta todas las páginas del sitio** navegando con un WebView real (motor Chromium) — igual que un navegador normal, siguiendo los mismos enlaces que vería una persona. Esto evita que sitios con protección anti-bot bloqueen el rastreo (a diferencia de un cliente HTTP simple, que muchos sitios rechazan aunque funcionen bien en Chrome).
3. Muestra la lista de páginas encontradas con checkboxes para incluir/excluir.
4. Genera un único PDF con **texto real y copiable** (no una captura de pantalla): lee la estructura de cada página (encabezados, párrafos, enlaces palabra por palabra, imágenes) y la reconstruye en el PDF con PDFBox, en el mismo orden del documento. Las imágenes se descargan tal cual aparecen en la página. Los enlaces quedan como anotaciones clicables sobre las palabras correspondientes.
5. Permite compartir/abrir el PDF resultante.
6. Ícono adaptativo, tema Material 3 con color dinámico (Android 12+), modo claro/oscuro automático.

## ⚠️ Estructura del repositorio

Este proyecto vive en la **raíz** de tu repositorio:

```
tu-repo/
  .github/workflows/build-apk.yml
  app/
    build.gradle.kts
    src/main/...
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  README.md
```

Copia todo el contenido de este paquete directamente en la raíz de tu repo (sin meterlo en una subcarpeta), y confirma en GitHub que ves `app/` y `build.gradle.kts` sueltos en la raíz.

## Compilar el APK en GitHub Actions

1. Sube el contenido a la raíz de tu repositorio.
2. Ve a **Actions → Build APK → Run workflow**.
3. Descarga el artefacto **`sitio-a-pdf-apk`** (contiene `app-debug.apk`).
4. Instala el APK en tu teléfono.

## Cómo funciona por dentro

### Detección de páginas (`SiteCrawler.kt`)
Recorrido en anchura (BFS): carga cada URL en un WebView real, espera a que la página termine de cargar (incluye JavaScript), lee con `document.querySelectorAll('a[href]')` todos los enlaces, los normaliza a URLs absolutas, descarta los que no son del mismo dominio (configurable) o no son HTTP/HTTPS, y sigue con los que no ha visitado. El resultado son las páginas en el orden en que fueron descubiertas.

### Generación del PDF (`PdfGenerator.kt`)
Para cada página seleccionada:
1. Se carga en el WebView.
2. Un script JavaScript recorre el DOM en orden y produce una lista de "bloques": encabezados, párrafos (cada uno con sus enlaces marcados palabra por palabra) e imágenes (con su URL resuelta).
3. Ese contenido se dibuja con PDFBox: texto real con `PDPageContentStream.showText`, saltos de línea y de página automáticos, y una anotación `PDAnnotationLink` invisible superpuesta sobre cada palabra que tenía un link, apuntando a esa URL. Las imágenes se descargan y se insertan con su tamaño real (ajustado al ancho de la página).
4. Cada página del sitio genera su propio PDF temporal; al final se unen todos en orden con `PDFMergerUtility`.

## Por qué antes fallaba con "No se encontraron páginas"

La primera versión usaba un cliente HTTP simple (sin JavaScript, sin comportarse como navegador) para descargar el HTML. Sitios con protección anti-bot, redirecciones, o que dependen de JavaScript para renderizar contenido, pueden rechazar ese tipo de petición o devolver una página vacía — aunque en Chrome se vean perfectos. Al usar el WebView real, el rastreo se comporta exactamente como abrir el sitio en el navegador del teléfono.

## Notas y límites

- `maxPages` (200 por defecto) evita rastreos infinitos; ajustable en `MainActivity.kt`.
- El texto se dibuja con la fuente estándar Helvetica (cubre español con tildes y eñes sin problema). Caracteres muy poco comunes fuera de Latin-1 se sustituyen por `?` para no romper la generación.
- Sitios que requieren login, o con protecciones muy agresivas (challenge de JavaScript tipo "verificando tu navegador"), pueden seguir sin ser accesibles.
- El PDF se guarda en `Android/data/com.sitetopdf.app/files/SiteToPDF/` y se comparte vía `FileProvider`.
