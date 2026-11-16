# Ondara 🌊

*Tu música, en otra frecuencia.*

Cliente de música para Android **gratuito, sin anuncios y de código abierto** que reproduce el
catálogo público de YouTube Music, con descargas offline, letras sincronizadas palabra por palabra
y un sistema de temas que cambia toda la interfaz.

> Estado: v0.1. Compila y pasa sus tests; todavía no se ha probado en dispositivos reales
> (ver "Lo que no se pudo verificar" en [AGENT.md](AGENT.md)).

## Qué incluye

- **Inicio** con las estanterías de YouTube Music, moods y charts; **búsqueda** con sugerencias en vivo, filtros e historial.
- Pantallas de **álbum, artista y playlist**; **biblioteca** local con favoritos, playlists, historial y descargas.
- **Reproductor** con transición compartida mini ↔ completo guiada por el gesto, cola reordenable,
  shuffle, repeat, temporizador de sueño, reproducción en segundo plano, notificación y pantalla de bloqueo.
- **Letras sincronizadas** (KuGou palabra a palabra, LRCLIB por línea, YouTube Music como texto).
- **6 temas** — Ondara, Cupertino, Mono, Vinilo, Neón y Dinámico — definidos en JSON, con galería en
  vivo, transición animada, **editor** y exportación/importación.
- Descargas offline, crossfade de igual potencia, gapless, normalización de volumen, ecualizador,
  radio infinita (*Ondara Flow*), widget y Android Auto.
- Importación de playlists de YouTube Music y de CSV (Exportify/Spotify); backup local con cifrado opcional.
- **Cero telemetría.** Sin Firebase. Los informes de fallos son opcionales y se quedan en el dispositivo.

## Compilar e instalar

Requisitos: JDK 21 y Android SDK (plataforma `android-37.0`).

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # instala en el dispositivo conectado por adb
```

Otros comandos útiles:

```bash
./gradlew test testDebugUnitTest                      # tests
./gradlew :core:designsystem:recordRoborazziDebug     # screenshots de cada tema
./gradlew detekt :app:lintDebug                       # análisis estático
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest   # rendimiento (requiere dispositivo)
```

## Arquitectura

Clean Architecture por módulos (`feature → domain ← data → source`), MVI, Hilt, Room, DataStore,
Media3, Ktor + kotlinx.serialization y Jetpack Compose sin Material 3. Detalles en
[AGENT.md](AGENT.md); el sistema de diseño está documentado en [DESIGN.md](DESIGN.md).

## Crear un tema

Copia uno de `core/designsystem/src/main/assets/themes/*.json`, cambia `id`, `name` y los tokens, e
impórtalo desde **Temas → Importar** (o usa el editor integrado). Un tema controla colores,
tipografía, formas, blur, curvas de animación, layout del reproductor, navegación y fondo.

## Descargo de responsabilidad

Ondara **no aloja ningún contenido**. Reproduce enlaces públicos de YouTube / YouTube Music, que
siguen siendo propiedad de sus respectivos dueños. El uso de clientes no oficiales puede ir contra
los Términos de Servicio de YouTube; este proyecto es **educativo y de uso personal**, y no está
afiliado a Google ni a YouTube.

**Apoya a los artistas**: suscríbete a YouTube Music Premium, compra su música y ve a sus conciertos.

El repositorio no contiene claves, cuentas ni tokens.

## Agradecimientos

Ondara no copia código de otros proyectos, pero se apoya en sus ideas y en su trabajo de ingeniería inversa:

| Proyecto | Qué se tomó |
|---|---|
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) (GPL-3.0) | Referencia funcional: rotación de clientes de InnerTube, resolución con fallback, agregación de proveedores de letras, crossfade de igual potencia. |
| [Metrolist](https://github.com/mostafaalagamy/Metrolist) (GPL-3.0) | Base de Echo Music; mismo mapa de endpoints de InnerTube y el enfoque de inicio de sesión por WebView. |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0) | Dependencia: descifrado de firmas y extracción de streams. |
| [LRCLIB](https://lrclib.net) | API pública de letras sincronizadas. |
| KuGou | Letras KRC; el esquema de decodificación (XOR de 16 bytes + zlib) es conocimiento público de la comunidad. |
| [Haze](https://github.com/chrisbanes/haze) (Apache-2.0) | Dependencia: desenfoque de fondo para las capas de vidrio. |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) (Apache-2.0) | Dependencia: arrastrar para reordenar la cola. |
| Tipografías (SIL OFL) | Space Grotesk, Inter, JetBrains Mono, Doto, Fraunces, Unbounded. |

## Licencia

[GPL-3.0](LICENSE).

## Instalar y actualizar desde GitHub

Descarga el APK de [Releases](https://github.com/jefer02/ondara/releases/latest).
La edición release consulta la última versión estable de GitHub al abrir la app y muestra
un aviso si hay un APK más reciente. «Descargar» abre el navegador; abre el APK descargado
y confirma la instalación en Android (permite instalar desde ese navegador si Android lo solicita).
Sin conexión o si GitHub limita las consultas, la app continúa normalmente y reintenta en la
siguiente apertura. No instala silenciosamente ni busca actualizaciones en la edición debug.

### Publicar otra versión

1. Cambia `version.properties`: por ejemplo, `versionName=0.1.1` y `versionCode=1001`.
   El código es `major * 1000000 + minor * 1000 + patch`; cada componente va de 0 a 999.
   La nueva versión debe ser mayor que todas las ya publicadas.
2. Confirma y sube los cambios a GitHub.
3. Crea y sube la etiqueta correspondiente: `git tag v0.1.1` y `git push origin v0.1.1`.
4. El workflow **Release Android** valida, compila, firma y publica el APK junto con su SHA-256.
   Un commit sin etiqueta no publica una versión. Para reintentar un fallo, usa el workflow
   manual seleccionando la etiqueta; una publicación existente no se sobrescribe.

Los secretos de Actions son `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`,
`ANDROID_KEY_PASSWORD` y `ANDROID_KEY_ALIAS`. La clave debe conservarse para todas las versiones.
Para compilar localmente, Gradle lee `~/.config/ondara/signing/signing.properties`, o la ruta
indicada por `ONDARA_SIGNING_PROPERTIES`, con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`.
`./gradlew :app:assembleRelease` genera el APK firmado; nunca utiliza la firma debug como reemplazo.
Guarda una copia privada de la clave y su contraseña; no las añadas al repositorio.
Una instalación antigua de `app.ondara` firmada con otra clave no puede actualizarse con esta firma:
es necesario desinstalarla primero (se pierden sus datos locales). La edición `.debug` puede coexistir.
