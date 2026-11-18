# AGENT.md — Ondara

Guía para quien (persona o agente) trabaje en este repositorio: arquitectura, convenciones y las
decisiones que se tomaron al construirlo.

## Cómo compilar

```bash
./gradlew :app:assembleDebug          # APK en app/build/outputs/apk/debug/
./gradlew test testDebugUnitTest      # tests JVM + Robolectric
./gradlew :core:designsystem:recordRoborazziDebug   # screenshots por tema
./gradlew :feature:home:recordRoborazziDebug --max-workers=1   # idem por pantalla (home, search, library, artist, account, lyrics, player)
./gradlew detekt :app:lintDebug
ONDARA_LIVE=1 ./gradlew :source:resolver:test --tests '*Live*'   # contra YouTube real
```

Requiere JDK 21 (`gradle/gradle-daemon-jvm.properties` lo fija) y Android SDK con `platforms;android-37.0`.
En máquinas con poca RAM: `gradle.properties` ya limita el daemon a 2 GB y el de Kotlin a 1 GB. Con 9–10 GB,
los tests de Robolectric deben ir con `--max-workers=1` (cada JVM de test reserva hasta 1,5 GB más memoria nativa).

## Módulos

```
:app                 Activity, grafo de navegación, raíz de tema, DI de arranque
:core:model          modelos de dominio puros (JVM)
:core:domain         interfaces de repositorio, PlayerController, casos de uso (JVM)
:core:network        Ktor (motor OkHttp) + helpers de JSON tolerante (JVM)
:core:database       Room
:core:datastore      DataStore tipado (protobuf vía kotlinx.serialization)
:core:data           repositorios offline-first, importación, backup, módulo Hilt de red
:core:designsystem   tokens, ThemeSpec, 7 temas JSON (incl. Tinta), componentes propios, shaders, ImmersiveHeroScaffold
:core:ui             componentes compuestos (SongRow, Shelf, DetailScaffold, menú de canción), base MVI, Nav
:source:innertube    cliente + parser de InnerTube (JVM)
:source:resolver     StreamResolver + estrategias + caché LRU/TTL (JVM)
:source:lyrics       proveedores, parsers LRC/KRC, agregador con scoring (JVM)
:source:spotify      SpotifyAuthProvider (PKCE oficial / cookie experimental), cliente, TrackMatcher (JVM)
:source:canvas       cadena de proveedores de canvas (Apple Music → Tidal), caché LRU + JSON y cálculo del clip (JVM)
:playback            ExoPlayer, MediaLibraryService, crossfade, descargas, widget, canvas en bucle (Cine)
:feature:*           home, news, radio, search, library, player, album, artist, playlist, lyrics, settings, themes, account
:benchmark           Macrobenchmark + generador de Baseline Profile
build-logic/         convention plugins (ondara.jvm.library, ondara.android.library/compose/feature/application, ondara.hilt)
```

Regla de dependencias: `feature → domain ← data → source`. **Ningún feature depende de otro**: se
comunican emitiendo `Nav` (en `:core:ui`) que `:app` traduce a rutas, y `:app` inyecta la vista de
letra de `:feature:lyrics` en `:feature:player` como *slot*. `:playback` implementa
`PlayerController` y `DownloadRepository`; los features solo ven las interfaces.

## Convenciones

- **MVI** por pantalla: `XUiState` (data class inmutable), `XEvent` (sealed), `XEffect` (one-shot),
  sobre `MviViewModel` (`:core:ui`). La UI solo llama a `onEvent`.
- **Offline-first**: `CatalogRepositoryImpl.cached()` emite Room → red → Room. `Resource<T>` modela carga/éxito/error.
- **Sin Material 3**: no es dependencia. Todo componente visible vive en `:core:designsystem`.
  Nada de valores sueltos: colores, tipos, formas, movimiento y materiales salen de `OndaraTheme.*`.
- **Sin spinners**: los estados de carga usan `Skeleton`.
- **Animaciones**: springs de `OndaraTheme.motion`; lo que cambia por frame se lee en la fase de
  dibujo (`graphicsLayer {}`, `drawBehind {}`) para no recomponer. Respetar `motion.reduced`.
- **Accesibilidad**: todo `OndaraIconButton` exige `contentDescription`; objetivos táctiles ≥ 48 dp;
  las paletas se validan contra WCAG AA en `ThemeSpecTest`.
- **Big O**: cada algoritmo no trivial documenta su complejidad en el KDoc (ver tabla abajo).
- Commits: Conventional Commits. Comentarios y textos de UI en español.
- Tests: JUnit 5 en todos los módulos; el motor *vintage* ejecuta los JUnit 4 de Robolectric/Roborazzi.

## Algoritmos y su complejidad

| Algoritmo | Dónde | Complejidad |
|---|---|---|
| Shuffle Fisher–Yates con actual en 0 | `core/domain/QueueShuffler.kt` | O(n) tiempo, O(1) extra |
| Línea activa de la letra (búsqueda binaria) | `core/model/Lyrics.kt` | O(log n) por frame |
| Caché de streams LRU + TTL | `source/resolver/StreamResolver.kt` | O(1) get/put |
| Cadena de estrategias | `StreamResolver.resolve` | O(k) peor caso, O(1) con caché |
| Scoring de letras + Jaro–Winkler | `source/lyrics/LyricsAggregator.kt` | O(p·m); tiempo de pared = max de latencias |
| Parser LRC / KRC | `source/lyrics/Parsers.kt` | O(n log n) / O(n) |
| Debounce de búsqueda | `feature/search` | `debounce(300).distinctUntilChanged()` |
| Pre-resolución i+1 | `playback/PlayerControllerImpl.prefetchNext` | salto: de O(red) a acierto O(1) |
| Contraste AA (búsqueda binaria de mezcla) | `designsystem/theme/ColorMath.kt` | O(log 1/ε) |
| CSV RFC 4180 | `core/domain/UseCases.kt` | O(n) |
| `TrackMatcher` (0.45·título + 0.30·artista + 0.25·duración, Jaro–Winkler) | `source/spotify/TrackMatcher.kt` | O(k·L²) por pista (k candidatos, L longitud); n pistas con `Semaphore(4)` |
| Ranking de Top Picks / mixes (`rankBy`) | `core/data/PersonalRepositoryImpl.kt` | O(n log n) |
| Estado de ánimo por géneros (MusicBrainz, caché Room) | `PersonalRepositoryImpl.MoodClassifier` | O(a·t), a artistas, t etiquetas |
| Traducción de letras por lotes de 1 400 caracteres | `core/data/LyricsTranslator.kt` | O(n), ⌈chars/1400⌉ peticiones |
| Agrupación de resultados de búsqueda (resultado principal = canción; vídeos al final) | `feature/search/SearchResults.kt` | O(n) |
| Equivalencia vídeo → canción (título normalizado + artista + duración ±9 s) | `core/domain/SongEquivalence.kt` | O(n + l) construcción, O(l) por consulta |
| Canción equivalente en la primera página de "Todo" | `core/data/CatalogRepositoryImpl.preferSong` | O(n + m + l), ≤ 1 petición extra |
| Cadena de canvas Apple Music → Tidal → clip de YouTube | `source/canvas/CanvasChain.kt` | O(p) proveedores; acierto de caché O(1) (LRU 64 + JSON) |
| Clip de 7 s (estribillo = línea más repetida, o centro) | `source/canvas/CanvasChain.canvasClip` | O(n + c) |
| Stream de vídeo del canvas (1080p → 720p → >1080p → SD) | `source/resolver/Video.kt` `pickBackground` | O(n) tiempo, O(1) espacio |
| Portada de mix (pila de carátulas rasterizada, LRU 16 MiB) | `core/ui/MixCover.kt` | O(p) píxeles al generar, O(1) en acierto |
| Subtítulo de mix ("N canciones · A, B, C…") | `core/model/MixPresentation.kt` | O(n + a log a) |
| Etiqueta de pestaña con *auto-size* por pasos | `designsystem/component/Layout.kt` `NavigationLabel` | O(k·l), solo al cambiar ancho o texto |
| Línea de letra en caja fija (búsqueda binaria del tamaño, 8 pasos) | `feature/lyrics/Lyrics.kt` `CurrentLyricLineContent` | O(8·n) por línea nueva |
| Dithering Bayer 4×4 (AGSL) | `designsystem/component/Effects.kt` | O(1) por píxel |
| Colapso del título grande / barra inferior (NestedScroll) | `designsystem/component/Layout.kt` | O(1) por evento de scroll |

## Decisiones

1. **Carpeta del proyecto**: se construyó en `~/ondara` porque el directorio de trabajo era el home (no vacío).
2. **Toolchain sin sudo**: JDK 21 (Temurin), Gradle y Android SDK instalados en `~/Android/`. El sistema solo tenía JDK 27, que AGP no soporta.
3. **Versiones** (verificadas en Maven el 2026-11-15): AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.14, Gradle 9.9.1, Compose BOM 2026.09.00, Media3 1.11.1, Hilt 2.60.1, Room 2.8.5, Ktor 3.6.1. `compileSdk = 37` (lo exigen dependencias recientes), `targetSdk = 36`, `minSdk = 26`.
4. **AGP 9 con Kotlin integrado**: no se aplica `org.jetbrains.kotlin.android`; los convention plugins configuran `KotlinCompile` directamente.
5. **Proto DataStore sin plugin de protobuf**: `DataStore<UserSettings>` con un `Serializer` que codifica en Protocol Buffers mediante `kotlinx-serialization-protobuf`. Mismo formato binario, sin generación de código ni el plugin de Gradle (compatibilidad incierta con AGP 9).
6. **Sin Material 3 como dependencia**: el prompt lo permitía "como base técnica"; se prescindió por completo (texto sobre `BasicText`, interacción con spring en vez de ripple, hojas y diálogos propios). Así es imposible que quede un componente M3 sin personalizar.
7. **Blur de vidrio con Haze 1.7.3**: la 2.x cambió toda la API; se fijó la última 1.x. Dentro de la fuente de haze (las pantallas) las barras usan tinte translúcido; la navegación y el mini-reproductor (hermanos de la fuente) sí desenfocan de verdad.
8. **Tipografías**: Clash Display / General Sans no son redistribuibles en un repo GPL. Se usan fuentes variables OFL: Space Grotesk (display), Inter (UI), JetBrains Mono (tiempos), Doto (matriz de puntos, tema Mono), Fraunces (Vinilo), Unbounded (Neón).
9. **Iconos propios** (`OndaraIcons`): set de trazo dibujado a mano con `ImageVector.Builder`; no se depende de material-icons.
10. **Resolución de streams**: orden `ExtensionStrategy` (solo si el usuario configura un servidor) → `NewPipeStrategy` → `InnerTubeClientStrategy` (rota ANDROID → IOS → ANDROID_VR). Verificado en vivo: NewPipe e InnerTube (ANDROID/IOS) devuelven URLs reproducibles; ANDROID_VR pide inicio de sesión desde esta red, por eso va al final. No se implementó generación propia de PoToken: NewPipeExtractor ya cubre el descifrado de firma.
11. **403**: `onPlayerError` invalida la caché y llama a `resolveAfterFailure`, que salta la estrategia que falló; un solo reintento por canción conservando la posición.
12. **Crossfade real con dos ExoPlayer**: la cola de la canción saliente pasa a un reproductor secundario (fade-out `cos`) mientras el principal entra en la siguiente (fade-in `sin`). Con crossfade = 0 se usa el gapless nativo de ExoPlayer.
13. **Normalización**: `volume = 10^(−loudnessDb/20)` solo cuando `loudnessDb > 0` (atenuar, nunca amplificar). La loudness viene de InnerTube; NewPipe no la expone, así que con NewPipe no hay ajuste.
14. **Amplitud para el tema Neón**: `TeeAudioProcessor` mide el RMS del PCM propio; no se pide permiso de micrófono (se descartó `Visualizer`).
15. **Shuffle físico**: al activar se reordena la cola del reproductor (actual en 0) y se guarda el orden original para restaurarlo; la cola visible siempre es el orden real.
16. **Descargas**: `WorkManager` + `CacheWriter` de Media3 hacia una `SimpleCache` permanente con clave = id de canción; el reproductor consulta esa caché antes que la red.
17. **Widget con RemoteViews** (no Glance): menos dependencias para un widget de una fila.
18. **Letras**: KuGou (KRC, palabra a palabra), LRCLIB (por línea) y la pestaña de letra de YouTube Music (texto plano) vía `PlainTextProvider`. **SimpMusic Lyrics no se integró**: su API responde 403 "only available to SimpMusic clients". BetterLyrics exige API key y YouLyPlus devolvía error; quedan fuera.
19. **Inicio de sesión opcional**: WebView a la página oficial de Google; se guarda solo la cookie de `music.youtube.com` en DataStore y se excluye de los backups.
20. **Informes de fallos**: ACRA solo se inicializa si el usuario lo activa; el *sender* es local (JSON en `files/crash`). Sin Firebase ni red.
21. **Backup**: JSON; con contraseña → PBKDF2-HMAC-SHA256 (120 000 iteraciones) + AES-256-GCM.
22. **Detekt 1.23.8 + detekt-formatting** hace de linter y de ktlint (un solo plugin).
23. **Transición de tema**: `ThemeTransitionHost` graba la pantalla en un `GraphicsLayer`, espera a que el tema nuevo esté compuesto y lo descubre con un círculo desde el toque.

24. **R8 desactivado en release**: sin dispositivo no se pudieron validar las reglas *keep* (NewPipe/Rhino, Ktor). `proguard-rules.pro` trae un punto de partida.
25. **Prueba de humo sobre Robolectric** (`app/src/test/.../AppSmokeTest.kt`): arranca la `MainActivity` real con el grafo Hilt de producción, recorre Inicio → Buscar → Biblioteca → Ajustes → Temas y aplica los 6 temas. Corre sin red (proxy inexistente) y precalienta la caché JNI de `java.nio` del runtime nativo de Robolectric, que aborta si el primer uso ocurre en un hilo de fondo.
26. **Lint**: 0 errores; queda 1 aviso conocido (Haze 2.x disponible, ver decisión 7).

## Decisiones v2 (rediseño, 2026-11-17)

27. **Ejecución con agentes paralelos**: cada agente tuvo módulos propios (`docs/agentes/REGLAS.md`) y Gradle
    serializado con `flock /tmp/ondara-gradle.lock` (9 GB de RAM). Parte del trabajo se delegó a Codex (OpenAI)
    a petición del usuario; la integración, la compilación y los commits los hizo el agente principal.
28. **Navegación**: píldora de vidrio con 4 pestañas (Inicio, Novedades, Radio, Biblioteca) + círculo de Buscar.
    Al desplazar hacia abajo encoge al icono activo con el mini-reproductor al lado (`ChromeState`, spring 0.8).
    Ajustes y Temas salen de la cabecera y pasan a la hoja de Cuenta (avatar, único botón a la derecha).
29. **Haze por pantalla**: Haze no puede aplicar el efecto dentro de su propia fuente, así que `ScreenScaffold`
    crea un `HazeState` local para el título grande y el *scrim* progresivo de la barra de estado; la
    navegación, el mini-reproductor y la hoja de Cuenta (`GlassOverlaySheet`) son hermanos de la fuente global.
30. **Ondara Flow** se movió de Inicio a Radio. Inicio sigue el orden de Apple Music; "Volver a escuchar" es
    carrusel y se oculta con menos de 3 elementos.
31. **Mixes virtuales** (`MixIds`): Favoritos, Novedades, Calma, Descubrir, Replay y estaciones se calculan al
    abrirlos a partir del historial local; las portadas son *mesh gradients* generados, no imágenes.
32. **Estados de ánimo y géneros**: etiquetas de MusicBrainz por artista (sin clave, 1 petición/s, caché en
    Room `artist_tags`). Sin red, el perfil muestra los datos que haya, nunca valores inventados.
33. **Traducción de letras**: endpoint público `translate_a/single` (cliente `gtx`) de Google, sin clave; se
    guarda con la letra. Desactivable en Ajustes. Si el idioma ya es el del usuario no hay petición.
34. **Credenciales en Keystore**: las cookies de YouTube Music (multi-cuenta) y los tokens de Spotify van en
    `SecretStore` (AES-256-GCM con clave de Android Keystore), no en DataStore. La cookie antigua
    `youtubeCookie` se migra y se borra al arrancar. Cerrar sesión borra cookies del WebView y el secreto.
35. **YouTube Music autenticado**: `SAPISIDHASH` (`SHA-1(ts + " " + SAPISID + " " + origin)`) en InnerTube
    para biblioteca, *likes*, historial y recomendaciones. Los *likes* locales se envían con `like/like`
    (`LikePusher`), desactivable.
36. **Spotify por estrategia** (`SpotifyAuthProvider`): oficial PKCE con Custom Tabs y Client ID del usuario
    (limitación de Spotify: 5 usuarios en modo desarrollo y Premium del propietario) o experimental por cookie
    `sp_dc` (APIs internas, TOTP; desactivado por defecto y con aviso; código adaptado de Echo Music y
    Metrolist, GPL-3.0). Importación cada 12 h con WorkManager o a mano, emparejamiento con
    `TrackMatcher` (acepta ≥ 0.8; el resto va a "Sin coincidencia") y caché `spotifyId → videoId` en Room.
37. **Room v2** con `AutoMigration(1 → 2)` (fijados, guardados, recientes, tablas de Spotify, etiquetas).
    Los campos nuevos de `UserSettings` se añadieron **al final**: el número de campo protobuf es posicional.
38. **Tema Tinta**: los tokens del prompt no daban AA en todos los pares; se ajustaron `onMuted` (#8E93AB),
    `surfaceHigh` (#2B2D3A) y `error` (#FF8A8F). Los bloques del layout Bento fuerzan su texto a AA en
    `ThemeSpec`. Portadas con dithering Bayer 4×4 (AGSL en API 33+, escala de grises antes) que pasan a color
    en 300 ms al reproducir. Iconos dibujados a mano (`HandDrawnIcons`) sustituyen al set por nombre.
39. **Reproductor "Cine"** (`PlayerLayout.CINEMATIC`): valor por defecto en la primera instalación
    (`UserSettings.playerLayout = "CINEMATIC"`); `"THEME"` respeta el layout de cada tema. El vídeo de fondo
    **v3: ya no es el vídeo completo** sino un *canvas* corto en bucle (ver "Decisiones v3"): Apple Music →
    Tidal → clip de 7 s del vídeo de YouTube en 720p/1080p. Se detiene en segundo plano, con la pantalla
    apagada, fuera de Cine y con movimiento reducido. En Tinta el canvas puede verse con dithering.
40. **Perfil bento**: paleta extraída con `androidx.palette`; cara dibujada según el ánimo dominante; compartir
    genera un PNG y lo comparte por `FileProvider` (`${applicationId}.share`).
41. **Screenshots por pantalla**: cada feature visible (Inicio, Buscar, Biblioteca, Artista, Perfil, Letras,
    Cine) expone un `XContent(state, onEvent)` sin estado que `XScreen` envuelve con su ViewModel; los tests
    Roborazzi lo pintan con datos de ejemplo (solo en `src/test`) en los 7 temas. En la captura de Cine el
    tramado de vídeo va desactivado: en Robolectric el `RenderEffect` AGSL del fondo se extiende a las capas
    hermanas (texto y controles); en código el fondo y la interfaz son capas separadas, pero falta verificarlo
    en un dispositivo.

## Decisiones v3 (correcciones de REF 6)

Dirección: Claude Code diagnosticó, escribió los *briefs* (`.codex-tasks/*.md`), revisó cada diff, compiló y
probó; **Codex CLI** escribió el grueso del código en *worktrees* aislados (`codex/<tarea>`), de dos en dos y sin
ejecutar Gradle. Cada entrega se revisó entera, se compiló, se integró con `merge --no-ff` y se borró su worktree.

42. **Crasheo al buscar (P1)** — *Codex `search`*. Causa probable (sin stacktrace ni dispositivo): la lambda
    `key` del `LazyColumn` y el contenido leían de Paging por índice (`peek`/`get`) con índices de **otro**
    snapshot; al cerrar el teclado o al cambiar de filtro la `PagingData` encoge entre medida y composición →
    `IndexOutOfBoundsException`. Ahora un único snapshot inmutable alimenta filas, claves y contenido; el acceso
    a Paging (para pedir más páginas) va en `LaunchedEffect` con rango comprobado; claves `tipo_id_índice` únicas
    también en sugerencias e historial; `listItem` del parser tolera renderers incompletos. Regresión:
    `SearchPagingRegressionTest` (Robolectric, lista que encoge + ids repetidos), Unicode/emoji en
    `SearchViewModelTest`, fixture `search_incomplete.json`.
    *Claude*: restauró la deduplicación entre páginas que Codex había quitado de `SearchPagingSource` (sin ella
    volvían las claves repetidas) y ajustó `SearchPagingSourceTest` y `SearchViewModelTest` a las firmas reales.
43. **Canción antes que vídeo (P8)** — *Codex `search`*. `Song.videoType` (`MusicVideoType`, último campo,
    compatible con el JSON cacheado) se lee de `watchEndpointMusicConfig.musicVideoType` (ATV → canción,
    OMV/UGC → vídeo). En "Todo" el resultado principal es la canción equivalente (índice O(n)); si no está, una
    sola petición extra con el filtro Canciones en la primera página. Secciones: principal → Canciones →
    Álbumes → Artistas → Playlists → Vídeos. Tocar un vídeo reproduce su canción equivalente si existe.
44. **Canvas en bucle (P2)** — *Codex `canvas`*. Nuevo `:source:canvas` (JVM): cadena de responsabilidad
    Apple Music → Tidal (se valida el artista) con caché LRU de 64 + archivo JSON y caducidad de URL. Si no hay
    canvas, `YouTubeCanvasProvider` toma un clip de 7 s (`ClippingConfiguration`) del vídeo oficial en 1080p/720p,
    empezando en el estribillo (línea de letra más repetida) o en el centro. Dos ExoPlayer en ranuras alternas con
    *crossfade* de 350 ms en el bucle, `SimpleCache` propia de 200 MB y precarga de la siguiente canción. Solo el
    clip de YouTube respeta "solo Wi-Fi" (los canvas son de pocos cientos de KB). Se eliminaron `VideoSync`, el
    controlador de vídeo completo y el modo "vídeo completo" (se pierde también la transición compartida de la
    portada del fondo antiguo). **Spotify Canvas no se integró**: la integración de Spotify existente no ofrece una
    consulta de canvas verificable sin endpoints privados. Técnicas tomadas de Echo Music (GPL-3.0), acreditado
    en cabeceras y README.
    *Claude*: si un canvas falla al reproducir, `onPlayerError` invalida su entrada de caché
    (`CanvasChain.invalidate`) para no quedarse con una URL caducada; arregló los hallazgos de detekt.
45. **Inicio sin colores genéricos ni texto duplicado (P6, P12)** — *Codex `home-covers`*. `Playlist.artworkUrls`
    (último campo) con hasta 4 carátulas reales; `MixCover` dibuja una pila de discos sobre la carátula principal
    desenfocada (API 31+) o reducida a 8 px con velo (< 31 y Tinta), cacheada como bitmap (LRU 16 MiB, clave = id +
    URLs + fondo + tema). Desaparecen `mixColors` y `moodColor`. Sin historial, los mixes se rellenan con Novedades
    del catálogo; si no hay carátulas, el mix no se muestra. Cada tarjeta muestra su nombre una sola vez (el título
    va dentro de la portada; debajo, "N canciones · artistas"); las etiquetas de Top Picks no repiten el título
    (`distinctPickLabel`). Ánimos con carátula real (`moodArtwork`) y velo del tema.
    *Claude*: quitó el candado global que serializaba la generación de todas las portadas (las descargas iban de
    una en una), añadió `onError` a `Artwork` para que un fallo no deje el *shimmer* para siempre y cacheó la
    medida de las etiquetas de la barra.
46. **Etiqueta "Novedad…" (P13)** — *Codex `home-covers` + Claude*. Codex repartió el ancho de la píldora en
    proporción a la medida de cada etiqueta. Su `NavigationLabel` escalaba la fuente con una regla de tres y el
    test nuevo (`NavigationLabelTest`, fuente al 130 % en 6 temas) **fallaba** (el ancho de los glifos no escala
    lineal). *Claude* la reescribió con `BasicText(autoSize = TextAutoSize.StepBased(7 sp…label, 0.25 sp))`.
47. **Texto fantasma al colapsar (P14)** — *Codex `home-covers`*. El desvanecimiento usa la altura real medida
    del bloque (`onSizeChanged`) y `drawWithContent { clipRect(top = offset) }` recorta lo que entra bajo la barra
    (así tampoco llega a la fuente de Haze); tinte de la barra al 92 % y progresivo 1 → 0.85.
48. **Reproductor (P3, P4, P5, P10, P11)** — *Codex `player-ui`*. Controles anclados abajo (el espacio libre
    queda arriba, sobre el canvas); la línea actual de la letra vive en una caja fija de 3 líneas con *auto-size*
    (búsqueda binaria) y elipsis, `AnimatedContent` con `SizeTransform` que no cambia la altura; scrim negro al
    55 % + sombra de texto (contraste 4.74:1 sobre portada blanca, `CurrentLyricLineTest`); título y artista en
    una línea con `basicMarquee` (elipsis con movimiento reducido) en todos los layouts; la franja roja "Vista
    previa de la letra" se sustituye por un tirador de 48 dp ("Abrir letra", deslizar o tocar) con el color
    dominante apagado de la portada. Componentes compartidos en `PlayerControls.kt` (metadatos, transporte,
    acciones, tirador). Test de posición Y del botón play con títulos/letras cortos y largos.
    *Claude*: reparó la compilación (Codex quitó imports de `getValue`/`setValue` y usó un `LocalContentColor`
    inexistente), un `BoxWithConstraints` sin usar (lint) y detekt.
49. **"Solo funciona Cine" (P7)** — causa raíz encontrada por *Claude* (Codex no la confirmó). La persistencia,
    el `Flow` de ajustes y `resolvePlayerLayout` funcionaban; el problema es de precedencia: desde v2
    `UserSettings.playerLayout = "CINEMATIC"` es un valor **explícito**, que gana siempre al layout del tema. El
    editor de temas ofrece "Layout del reproductor" y la galería muestra el layout de cada tema, pero ese valor
    nunca se aplicaba. Ahora, si el usuario cambia el layout en el editor, "Guardar y aplicar" pasa a "Según el
    tema" (`settingsAfterThemeSave`, con test); si no lo toca, se respeta su elección. Además (*Codex*): el selector
    de Ajustes > Reproductor aplica al instante con chips y muestra una vista previa con el reproductor real,
    el canvas se activa solo en Cine (`FullPlayer` controla `CanvasActive` según el layout) y los paneles se
    reinician al cambiar de layout.
50. **Créditos (P9)** — *Claude*. `OndaraAuthor` (nombre, GitHub, Instagram) en `feature/account/Credits.kt`:
    pantalla "Acerca de Ondara" (logo con tokens, versión, botones que abren la app de Instagram o el navegador),
    pie en la hoja de Cuenta, último paso del onboarding y sección "Autor" del README.

## Estado de verificación (2026-11-18, v3)

| Comprobación | Resultado |
|---|---|
| `./gradlew :app:assembleDebug` | OK |
| `./gradlew test` | 287 tests, 0 fallos (3 omitidos: los *live*, opt-in) |
| `recordRoborazziDebug` (módulo a módulo) | 8 módulos; reproductor: 6 layouts × 7 temas + Cine; Inicio, Buscar, Biblioteca, Letras, Cuenta, Artista, showcase y app |
| `./gradlew detekt` | sin hallazgos |
| `./gradlew :app:lintDebug` | 0 errores |
| Dispositivo (`adb devices`) | ninguno conectado: nada verificado en hardware |

## Estado de verificación (2026-11-17, v2)

| Comprobación | Resultado |
|---|---|
| `./gradlew :app:assembleDebug` | OK (APK ~27 MB) |
| `./gradlew test testDebugUnitTest` | 229 tests, 0 fallos (3 omitidos: los *live*, opt-in) |
| `recordRoborazziDebug` | 7 pantallas × 7 temas (49 capturas) + showcase por tema |
| `./gradlew detekt` | sin hallazgos |
| `./gradlew :app:lintDebug` | 0 errores, 9 avisos (JavaScript del WebView de inicio de sesión, sugerencias KTX, versiones) |
| `ONDARA_LIVE=1 … LiveResolverTest` | NewPipe e InnerTube devuelven URLs reproducibles (v1) |

## Lo que no se pudo verificar en este entorno

No había dispositivo ni emulador (máquina sin KVM configurado y con 9 GB de RAM):

- La app **no se ejecutó en un dispositivo**. Está verificado: compilación, tests unitarios, screenshots
  Roborazzi del sistema de diseño, la prueba de humo de la app completa sobre Robolectric y la resolución de
  URLs contra YouTube real desde la JVM. **La reproducción de audio de extremo a extremo (ExoPlayer, servicio,
  notificación, crossfade, descargas) y el reproductor a pantalla completa no se han ejecutado nunca.**
- Los objetivos medibles (salto < 300 ms pre-resuelto, frames lentos < 1 %, arranque < 600 ms,
  120 fps) **no están medidos**. `:benchmark` compila y contiene los tests para medirlos con
  `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` en un dispositivo real.
- El Baseline Profile incluido (`app/src/main/baseline-prof.txt`) está escrito a mano con las rutas
  críticas; el generador (`BaselineProfileGenerator`) lo regenera en un dispositivo.
- Android Auto y el widget no se probaron en hardware.
- **v3 sin dispositivo**: el crasheo de búsqueda se corrigió sobre la causa más probable (no había stacktrace) y
  su test Robolectric no se ejecutó contra el código antiguo; los endpoints de canvas de Apple Music y Tidal, el
  clip de YouTube, el *crossfade* del bucle y la caché de 200 MB; el desenfoque real de las portadas de mix
  (API 31+); el marquee y el gesto del tirador de letra en un dispositivo.
- **v2 sin dispositivo**: inicio de sesión real en YouTube Music y Spotify (WebView, Custom Tabs, cookies),
  la sincronización con WorkManager, el vídeo de fondo del layout Cine (segundo ExoPlayer, sincronía,
  Wi-Fi), el tramado de vídeo en Tinta, el desenfoque progresivo de Haze y las animaciones de la barra
  inferior solo están verificados por compilación, tests JVM/Robolectric y capturas.

## Trabajo futuro

Fuera de alcance de la v0.1, con la interfaz ya definida en `core/domain/Future.kt`:

- **Reconocimiento de canciones** → `SongRecognizer`.
- **Escucha en grupo sincronizada** → `ListenTogetherSession` + `SyncEvent` (requiere servidor propio).
- **Escritorio (Compose Multiplatform)**: `:core:model`, `:core:domain`, `:core:network` y `:source:*`
  ya son JVM puro sin Android; habría que portar `:core:designsystem` y dar otra implementación de `PlayerController`.

Otros pendientes: PoToken propio como estrategia adicional, descarga por rangos para evitar
*throttling*, Google Cast, portadas en el widget.

## Decisiones v4

51. **Cine y ciclo de vida**. Se eliminó el estado local que alternaba la visibilidad de los controles y la
    animación que lo consumía. Ese valor sobrevivía mientras la composición seguía activa, incluso al cambiar
    de canción o volver del segundo plano. La portada y las ranuras `TextureView` quedan detrás de los controles.
    El doble toque conserva los saltos de 10 s. La regresión usa una Activity de Robolectric y cambia al estado
    `CREATED` antes de volver a `RESUMED`.
52. **Reproductor desplazable**. El bloque principal mide lo mismo que el viewport en los seis layouts y
    consume sus insets dentro de ese bloque. La vista previa, los créditos y el artista empiezan después.
    La vista previa sincronizada consulta el índice en O(log n) y dibuja una ventana de hasta cinco líneas.
    `MarqueeText` mide en O(n) al cambiar el texto, el estilo o el ancho y aplica la máscara solo si hay desborde.
53. **Letras completas**. La vista se superpone a todo el reproductor e incluye su propio encabezado con insets.
    El arrastre hacia abajo en el encabezado, o desde el inicio de la lista, cierra la vista. El fondo ocupa el
    área bajo ambas barras del sistema.
54. **Reproducción en Cine**. El audio es el único modo de reproducción. El fondo conserva el canvas
    silenciado en bucle, con portada de respaldo. El clip de YouTube sigue la política de Wi‑Fi.
55. **Foto de perfil**. El Photo Picker entrega una URI temporal al recortador. Solo la ruta del PNG, los ajustes
    de efecto y la revisión se persisten al final de `UserSettings`. El recorte se guarda de forma atómica con
    un máximo de 1024 px. La caché del efecto usa la foto, la revisión, los tonos y el tamaño como clave. Procesa
    el Bayer 4×4 en O(n²) sobre un mosaico de n×n y lo amplía sin interpolación.
56. **Detalle inmersivo**. Palette conserva extremos claros y oscuros y calcula el dominante de la franja
    superior. El tinte de los botones garantiza AA incluso en zonas de la portada con luminancia opuesta.
    El héroe se recorta en la fase de dibujo antes de invadir la barra, que reutiliza los controles superiores.
57. **Navegación**. La selección usa la jerarquía de destinos tipados. Las pantallas de Cuenta se abren encima
    de la pestaña y el historial ordinario las devuelve a ella. Cambiar de pestaña descarta el tramo anterior
    sobre Inicio. Tocar Inicio hace pop a su entrada raíz y dispara el desplazamiento al comienzo.
