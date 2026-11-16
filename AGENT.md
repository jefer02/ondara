# AGENT.md — Ondara

Guía para quien (persona o agente) trabaje en este repositorio: arquitectura, convenciones y las
decisiones que se tomaron al construirlo.

## Cómo compilar

```bash
./gradlew :app:assembleDebug          # APK en app/build/outputs/apk/debug/
./gradlew test testDebugUnitTest      # tests JVM + Robolectric
./gradlew :core:designsystem:recordRoborazziDebug   # screenshots por tema
./gradlew detekt :app:lintDebug
ONDARA_LIVE=1 ./gradlew :source:resolver:test --tests '*Live*'   # contra YouTube real
```

Requiere JDK 21 (`gradle/gradle-daemon-jvm.properties` lo fija) y Android SDK con `platforms;android-37.0`.
En máquinas con poca RAM: `gradle.properties` ya limita el daemon a 2 GB y el de Kotlin a 1 GB.

## Módulos

```
:app                 Activity, grafo de navegación, raíz de tema, DI de arranque
:core:model          modelos de dominio puros (JVM)
:core:domain         interfaces de repositorio, PlayerController, casos de uso (JVM)
:core:network        Ktor (motor OkHttp) + helpers de JSON tolerante (JVM)
:core:database       Room
:core:datastore      DataStore tipado (protobuf vía kotlinx.serialization)
:core:data           repositorios offline-first, importación, backup, módulo Hilt de red
:core:designsystem   tokens, ThemeSpec, 6 temas JSON, componentes propios, shaders
:core:ui             componentes compuestos (SongRow, Shelf, DetailScaffold, menú de canción), base MVI, Nav
:source:innertube    cliente + parser de InnerTube (JVM)
:source:resolver     StreamResolver + estrategias + caché LRU/TTL (JVM)
:source:lyrics       proveedores, parsers LRC/KRC, agregador con scoring (JVM)
:playback            ExoPlayer, MediaLibraryService, crossfade, descargas, widget
:feature:*           home, search, library, player, album, artist, playlist, lyrics, settings, themes
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

## Estado de verificación (2026-11-16)

| Comprobación | Resultado |
|---|---|
| `./gradlew :app:assembleDebug` | OK (APK ~25 MB) |
| `./gradlew test testDebugUnitTest` | 63 tests, 0 fallos (3 omitidos: los *live*, opt-in) |
| `./gradlew detekt` | sin hallazgos |
| `./gradlew :app:lintDebug` | 0 errores, 1 aviso conocido |
| `ONDARA_LIVE=1 … LiveResolverTest` | NewPipe e InnerTube devuelven URLs reproducibles |

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

## Trabajo futuro

Fuera de alcance de la v0.1, con la interfaz ya definida en `core/domain/Future.kt`:

- **Reconocimiento de canciones** → `SongRecognizer`.
- **Escucha en grupo sincronizada** → `ListenTogetherSession` + `SyncEvent` (requiere servidor propio).
- **Escritorio (Compose Multiplatform)**: `:core:model`, `:core:domain`, `:core:network` y `:source:*`
  ya son JVM puro sin Android; habría que portar `:core:designsystem` y dar otra implementación de `PlayerController`.

Otros pendientes: PoToken propio como estrategia adicional, descarga por rangos para evitar
*throttling*, Google Cast, portadas en el widget.
