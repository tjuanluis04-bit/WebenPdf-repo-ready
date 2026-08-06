# Sitio a PDF — App Android

App nativa Android (Kotlin + Jetpack Compose + Material 3) que:

1. Recibe una URL.
2. **Detecta automáticamente todas las páginas del sitio**: rastrea en anchura (BFS) siguiendo todos los enlaces internos (`<a href>`), sin límite fijo salvo `maxPages` (200 por defecto, configurable en el código).
3. Muestra la lista de páginas encontradas con checkboxes para incluir/excluir.
4. Genera un único PDF con las páginas en orden, conservando texto, imágenes y **enlaces clicables** (usa el motor de impresión de WebView por página + fusión real de PDFs con PDFBox, no capturas de pantalla).
5. Permite compartir/abrir el PDF resultante.
6. Ícono adaptativo (`mipmap-anydpi-v26`), tema Material 3 con **color dinámico** (Android 12+), modo claro/oscuro automático.

## ⚠️ Estructura del repositorio — MUY IMPORTANTE

Este proyecto está pensado para vivir en la **raíz** de tu repositorio de GitHub, así:

```
tu-repo/
  .github/workflows/build-apk.yml   ← el workflow
  app/                               ← el módulo Android
    build.gradle.kts
    src/main/...
  build.gradle.kts                  ← raíz de Gradle
  settings.gradle.kts
  gradle.properties
  README.md
```

**No metas todo esto dentro de una subcarpeta `android-app/`** salvo que edites el workflow para que apunte ahí con `working-directory`. Si tu repo ya tiene otro proyecto (por ejemplo un script Node), este proyecto Android puede convivir en una subcarpeta, pero en ese caso el workflow **debe** tener:

```yaml
defaults:
  run:
    working-directory: nombre-de-la-subcarpeta
```

y el `path` del `upload-artifact` debe incluir esa misma subcarpeta como prefijo. El workflow incluido aquí asume que el proyecto está en la **raíz**, sin subcarpeta.

## Compilar el APK en GitHub Actions

1. Copia **todo el contenido** de este paquete (incluyendo la carpeta oculta `.github/`) directamente en la raíz de tu repositorio.
2. Confirma en GitHub (pestaña **Code**) que ves `app/`, `build.gradle.kts`, `settings.gradle.kts` y `.github/workflows/build-apk.yml` en la raíz.
3. Ve a la pestaña **Actions** → workflow **"Build APK"** → **Run workflow**.
4. Al terminar (unos 5-10 min), descarga el artefacto **`sitio-a-pdf-apk`**: contiene `app-debug.apk`.
5. Instala el APK en tu teléfono (activa "Instalar apps de orígenes desconocidos" si lo pides por fuera de Play Store).

## Compilar localmente (opcional, requiere Android Studio)

1. Abre esta carpeta en Android Studio (Hedgehog o superior).
2. Deja que sincronice Gradle (descargará SDK/dependencias).
3. Run ▶ en un emulador o dispositivo, o `Build > Build Bundle(s)/APK(s) > Build APK(s)`.

## Cómo se detectan "todas las páginas"

`SiteCrawler` hace un recorrido en anchura: visita la URL inicial, extrae todos los `<a href>`, los normaliza a URL absolutas, descarta los que no son del mismo dominio (configurable) o no son HTTP/HTTPS, y los agrega a la cola si no fueron vistos. Repite hasta agotar la cola o llegar a `maxPages`. El resultado son las páginas **en el orden en que fueron descubiertas**, que es el mismo orden en el que se generan las páginas del PDF final.

## Notas y límites

- Sitios muy grandes: `maxPages` evita rastreos infinitos; puedes subirlo editando `SiteCrawler(maxPages = 200, ...)` en `MainActivity.kt`.
- Contenido muy dinámico (SPA con mucho JavaScript) puede necesitar más tiempo de carga; el timeout por página es de 25s (`PdfGenerator.loadPage`).
- Sitios que bloquean bots o requieren login no serán accesibles.
- El PDF se guarda en almacenamiento privado de la app (`Android/data/com.sitetopdf.app/files/SiteToPDF/`) y se comparte vía `FileProvider` — no requiere permisos de almacenamiento en tiempo de ejecución.
