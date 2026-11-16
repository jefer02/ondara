# Reglas comunes para agentes que trabajan en paralelo en Ondara (rediseño v2)

Repo: `/home/jeferson/ondara` (Android, Kotlin, Jetpack Compose, Hilt, multi-módulo). Lee primero `AGENT.md` y `DESIGN.md`.
Imágenes de referencia en `docs/referencias/` (ábrelas: `ref1_ondara_actual.png` estado actual con problemas,
`ref2_apple_pantallas.png` A=Artista B=Buscar C=Biblioteca D=Letras, `ref3_perfil_tema_tinta.png` perfil bento / tema Tinta,
`ref4_apple_inicio_esquema.png` orden del Inicio, `ref5_reproductor_video.jpg` reproductor "Cine").
Objetivo general: parecido a Apple Music (iOS 26) en orden, jerarquía y sensación, pero con identidad propia de Ondara.
No es un clon: nada de iconos, textos ni marcas de Apple.

## Convivencia (varios agentes editan a la vez)
- Toca SOLO los módulos que tu encargo te asigna. No edites `core/*`, `app/`, `settings.gradle.kts`, `gradle/libs.versions.toml`
  ni otros features. Si echas en falta algo de core (icono, componente, método de repositorio), NO lo añadas en core:
  resuélvelo dentro de tu módulo y dilo en el informe final.
- NO hagas `git add`, `git commit`, `git stash`, `git checkout` ni `git push`.
- Máquina con 9 GB de RAM y varios agentes compilando. Gradle SIEMPRE serializado con flock y solo tus módulos:
  `flock /tmp/ondara-gradle.lock ./gradlew :feature:<x>:compileDebugKotlin :feature:<x>:detekt --offline`
  (y `:feature:<x>:testDebugUnitTest` si escribes tests). Nunca `assembleDebug` ni builds globales.
- Si la compilación falla FUERA de tus módulos (otro agente a medias), espera 2–3 minutos y reintenta (hasta ~10 veces);
  no lo arregles tú. Si persiste, termina tu código y déjalo dicho en el informe.

## Convenciones de código
- MVI por pantalla sobre `MviViewModel` (`core/ui/.../Mvi.kt`): `XUiState` inmutable, `XEvent` sealed, `XEffect`. La UI solo llama a `onEvent`.
- SIN Material 3 (no es dependencia). Todo componente sale de `:core:designsystem` (`component/Basics.kt`, `Controls.kt`,
  `Layout.kt`, `Immersive.kt`, `Effects.kt`, `icon/OndaraIcons.kt`, `icon/HandDrawnIcons.kt`) y `:core:ui`
  (`Artwork.kt`, `MusicRows.kt`, `Personal.kt`, `SongMenu.kt`). Lee esos archivos antes de escribir UI.
- Nada de valores sueltos: colores, tipografía, formas, espaciado, movimiento y materiales salen de `OndaraTheme.*`
  (`colors`, `type`, `shapes`, `spacing`, `motion`, `materials`, `spec`). Debe verse bien en los 7 temas
  (`core/designsystem/src/main/assets/themes/*.json`), incluidos Mono (sin blur, riel lateral) y Tinta (mate, bento, iconos a mano).
- Sin spinners: estados de carga con `Skeleton`. Animaciones con los springs de `OndaraTheme.motion` y respetando
  `motion.reduced`; lo que cambia por frame se lee en `graphicsLayer {}` / `drawBehind {}`.
- Accesibilidad: `contentDescription` en todo botón de icono, objetivos táctiles ≥ 48 dp.
- `LazyColumn`/`LazyRow` siempre con `key` y `contentType`. Listas: padding inferior con `LocalBottomInset` (lo hace `ScreenScaffold`
  e `ImmersiveHeroScaffold`); el contenido nunca debe quedar tapado por el mini-reproductor ni la barra.
- Textos de UI, comentarios y KDoc en español. Documenta la complejidad (Big O) de los algoritmos nuevos en su KDoc.
- Nada de `TODO`, pantallas vacías ni datos simulados: todo debe funcionar con los repositorios reales
  (`core/domain/.../Repositories.kt`, `PlayerController.kt`). Las implementaciones de los repositorios nuevos
  (`PersonalRepository`, `AccountRepository`, `SyncRepository`, métodos v2 de `LibraryRepository`/`CatalogRepository`) las
  escribe otra persona: programa contra las interfaces.
- Navegación: los features no se conocen entre sí; emiten `Nav` (`core/ui/.../Mvi.kt`) y `:app` lo traduce. Destinos nuevos v2:
  `Nav.Account` (hoja de Cuenta, la abre el avatar), `Nav.Profile`, `Nav.Search`, `Nav.Radio`, `Nav.SpotifyConnect`, `Nav.Unmatched`,
  `Nav.Downloads`, `Nav.About`, `Nav.PlayerSettings`, `Nav.LibraryCategory(category)`, además de los existentes
  (`Album`, `Artist`, `Playlist(id)`, `Mood`, `Settings`, `Themes`, `Login`…).
- Playlists virtuales: ids `mix_*`, `favorites` ("Tus favoritas") y `station_<artistId>` (ver `MixIds` en `core/model/.../Personal.kt`);
  se abren con `Nav.Playlist(id)` y sus canciones salen de `PersonalRepository.virtualPlaylist(id)`.
- Encabezado de las pestañas: `ScreenScaffold(title = "…", actions = { AvatarButton { onNavigate(Nav.Account) } })`.
  Los botones de paleta y ajustes ya no van en el encabezado.
- Al reproducir, pasa el contexto: `player.play(songs, index, shuffle, PlaybackContext("Playlist", nombre))`.
- Líneas ≤ ~160 caracteres; debe pasar `detekt` (`config/detekt/detekt.yml`).

## Informe final (breve)
Archivos creados/modificados, decisiones de diseño relevantes, cosas de core que echaste en falta, y qué quedó sin verificar.
