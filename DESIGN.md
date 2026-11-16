# DESIGN.md — Sistema de diseño de Ondara

Todo vive en `:core:designsystem`. Ninguna pantalla usa valores sueltos ni componentes de Material 3.

## Principios

1. **Identidad propia**: tipografía con carácter, mucho aire, jerarquía fuerte.
2. **La portada manda**: `LocalArtworkPalette` lleva los colores de la carátula a fondos y, en el tema Dinámico, a toda la paleta.
3. **Profundidad con materiales**: capas de vidrio con blur y tinte; nunca sombras grises.
4. **Movimiento físico**: springs, no `tween` lineal; todo respeta "reducir animaciones".
5. **Prohibido**: `TopAppBar`, `Card` de M3, FAB, spinners, ripple.

## Tokens

Acceso: `OndaraTheme.colors / type / shapes / spacing / motion / materials / spec / artwork`.

| Grupo | Tokens |
|---|---|
| `OndaraColors` | `background`, `surface`, `surfaceHigh`, `onBackground`, `onMuted`, `accent`, `onAccent`, `outline`, `error`, `isDark`, `scrim`; bloques *bento*: `blockPrimary`, `blockLight`, `blockMuted`, `onBlock`, `onBlockMuted` (derivados de la paleta si el tema no los define; el texto se fuerza a AA) |
| `OndaraTypography` | `hero` 40 · `display` 30 · `title` 21 · `headline` 17 · `body` 15 · `bodyStrong` · `caption` 13 · `label` 12 · `mono` 12 · `monoLarge` 24 · `lyric` 26 · `largeTitle` 34 (título de pestaña) · `code` 14 (hex, siempre monoespaciada) (sp, × `scale`) |
| `OndaraShapes` | `small`, `medium`, `large`, `artwork`, `control` (píldora o esquina), opción *squircle* |
| `OndaraSpacing` | rejilla de 4 dp: `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 20 · xxl 28 · huge 40`, `screen 20`, `touch 48` |
| `OndaraMotion` | `stiffness`, `dampingRatio`, `fastMs`, `mediumMs`, `slowMs`, `pressScale`, `staggerMs`, `reduced` |
| `OndaraMaterials` | `blur`, `surfaceOpacity`, `saturation`, `hairline`, `noise` |

El espaciado es constante entre temas para que los layouts no salten al cambiar de tema.

## Motor de temas

`ThemeSpec` (`theme/ThemeSpec.kt`) es Kotlin puro y serializable:

```
id, name, description
colors      { mode: DARK|LIGHT|SYSTEM, dynamic, dark: Palette, light: Palette? }
typography  { display, body, mono: GROTESK|INTER|MONO|DOT|SERIF|WIDE|ROUNDED|NUNITO, pesos, scale, displayTracking, uppercaseLabels }
shapes      { small, medium, large, artwork, squircle, pillControls }
materials   { blur, surfaceOpacity, saturation, hairline, noise }
motion      { stiffness, dampingRatio, fastMs, mediumMs, slowMs, pressScale, staggerMs }
playerLayout  ARTWORK_HERO | IMMERSIVE | MINIMAL | VINYL | BENTO | CINEMATIC
icons         STROKE | HAND_DRAWN
ditherArtwork (portadas tramadas a dos tonos)
navigation    FLOATING_GLASS_PILL | BOTTOM_BAR | SIDE_RAIL
background    SOLID | MESH_GRADIENT | BLURRED_ART
list          PLAIN | SEPARATORS | CARDS
largeTitles, accentPulse, grain
```

- Los temas incluidos están en `src/main/assets/themes/<id>.json`; los del usuario en `files/themes/`.
  **Añadir un tema = añadir un JSON.** `ThemeStore` los carga, valida, guarda, importa y exporta.
- `OndaraTheme(spec, artwork)` resuelve los tokens y los expone por `CompositionLocal`.
- Paleta dinámica: `ColorMath.paletteFrom(dominant, vibrant)` deriva los 8 colores y fuerza AA.
- **Contraste**: `ThemeSpecTest` falla si cualquier paleta de cualquier tema baja de 4.5:1, y prueba
  2000 portadas aleatorias contra la derivación dinámica.

## Temas incluidos

| Tema | Color | Tipografía | Formas | Navegación | Reproductor | Fondo | Rasgo |
|---|---|---|---|---|---|---|---|
| **Ondara** | petróleo `#071A20` + coral `#FF6B57` | Space Grotesk / Inter | redondas 12–30, píldoras | píldora de vidrio flotante | `ARTWORK_HERO` | mesh gradient | por defecto |
| **Cupertino** | blanco/negro según sistema + rojo-rosado | Inter 800 | *squircle* | barra inferior de vidrio | `IMMERSIVE` | portada difuminada | títulos grandes que colapsan, separadores finos |
| **Mono** | negro/blanco + rojo `#FF3B30` | Doto (matriz de puntos) | 2–6 dp, sin blur | riel lateral | `MINIMAL` | sólido | etiquetas en mayúsculas, springs secos |
| **Vinilo** | marrón cálido + ámbar | Fraunces (serif) | 8–22 | barra inferior | `VINYL` (disco que gira) | sólido + grano | filas en tarjetas |
| **Neón** | negro AMOLED + cian | Unbounded | 6–12 | píldora | `ARTWORK_HERO` con halo | sólido | acentos que laten con el audio |
| **Dinámico** | toda la paleta sale de la portada | Space Grotesk | *squircle* 14–34 | píldora | `IMMERSIVE` | mesh gradient | transición de paleta en 800 ms |
| **Tinta** | azul tinta `#06080F` + lavanda `#B4B7C9` / `#E9EAFF`, sin colores saturados | Fredoka / Nunito / JetBrains Mono | 14–28, bloques bento | píldora **sólida** con borde 1 dp | `BENTO` | sólido | mate (sin vidrio ni blur), iconos dibujados a mano, portadas tramadas Bayer 4×4 |

### Tema Tinta

| Token | Valor |
|---|---|
| Fondo | `#06080F` |
| Superficie / contenedores | `#1A1A1F` (`surfaceHigh` `#2B2D3A`) |
| Bloque primario / claro / apagado | `#B4B7C9` / `#E9EAFF` / `#50556B` |
| Texto sobre bloques claros | `#0D1030`; sobre el bloque apagado, `#E9EAFF` |
| Texto sobre oscuro | `#B4B7C9`; secundario `#8E93AB` |
| Acento / borde | `#E9EAFF` / `#50556B` |

El secundario pedido (`#6B7088`) da 4,1:1 sobre el fondo y 3,5:1 sobre la superficie: no llega a AA, así que
se aclaró a `#8E93AB` (5,7:1 sobre la superficie). El texto tinta sobre el bloque apagado tampoco llegaba
(2,5:1) y por eso ese bloque usa texto claro. `ThemeSpecTest` verifica todas las combinaciones.

- **Materiales**: `blur = 0` → `GlassSurface` es opaca con borde fino; barra y mini-reproductor son píldoras `#1A1A1F` con borde `#50556B`.
- **Iconos**: `icons = HAND_DRAWN`; `Icon()` sustituye por nombre los de navegación y reproducción por `HandDrawnIcons` (trazo 2,6 px, curvas a pulso).
- **Portadas**: `ditherArtwork = true`; `Artwork` aplica `Modifier.twoToneDither` (tinta + lavanda) y la portada que suena vuelve a color en 300 ms.
- **Movimiento** "de papel": `dampingRatio 0.95`, `pressScale 0.98`, stagger 40 ms, sin escalados grandes.

## Componentes

`component/Basics.kt` — `Text`, `Label`, `Surface`, `GlassSurface`, `SquircleShape`, `Modifier.pressable`, `Haptics`.
`component/Controls.kt` — `OndaraButton` (FILLED/TONAL/GHOST), `OndaraIconButton`, `OndaraChip`, `OndaraSwitch`,
`OndaraSlider`, `OndaraValueSlider`, `OndaraTextField`, `Skeleton`, `Hairline`, `SettingRow`, `SectionTitle`, `Modifier.staggeredEntry`.
`component/Layout.kt` — `ScreenScaffold` (título grande que colapsa + velo de vidrio), `ChromeState`, `OndaraNavigation` (3 estilos), `OndaraSheet`, `GlassOverlaySheet`, `OndaraDialog`, `OndaraBackground`, `EmptyState`.
`component/Immersive.kt` — `ImmersiveHeroScaffold`, `GlassIconButton`, `GlassPillButton`, `GlassCard`, `BottomFade`.
`component/Effects.kt` — `PlayPauseButton` (morphing), `MeshGradientBackground` (AGSL), `Modifier.grain`, `Modifier.twoToneDither` (Bayer 4×4), `ThemeTransitionHost`.
`icon/OndaraIcons.kt` — 55 iconos propios de trazo. `icon/HandDrawnIcons.kt` — set dibujado a mano + caritas de estado de ánimo.
`Showcase.kt` — catálogo vivo; base de los screenshot tests (`build/outputs/roborazzi/showcase_<tema>.png`).

`:core:ui` añade los compuestos: `Artwork`, `SongRow`, `MusicCard`, `Shelf`, `MusicItemRow`,
`ParallaxHeader`, `DetailScaffold`, `SongMenuSheet`, `PlaylistPickerSheet`, `PlayingBars`, y en v2
`ImmersiveDetail`, `TopPickCard`, `MixCover`, `PlaylistCover`, `SourceBadge`, `Avatar`, `AvatarButton`, `Carousel`.

## Estructura de navegación (v2)

- **Barra inferior** (`FLOATING_GLASS_PILL`): una píldora de vidrio con 4 pestañas —Inicio, Novedades, Radio,
  Biblioteca— y un círculo de vidrio aparte para Buscar. El mini-reproductor es una tercera píldora, más
  delgada (56 dp), encima. Al bajar más de 28 dp (`ChromeState`), la píldora se encoge al icono de la pestaña
  activa y el mini-reproductor baja a su lado; al subir o tocarla, se expande. Un resaltado redondeado se desliza
  entre pestañas. En `BOTTOM_BAR` y `SIDE_RAIL`, Buscar es un quinto destino.
- **Encabezado**: título grande en negrita que colapsa a una barra pequeña de vidrio; a la derecha solo el
  avatar (punto de acento si no hay cuentas), que abre la **hoja de Cuenta** (`GlassOverlaySheet`: perfil, cuentas
  conectadas, Temas, Ajustes, Descargas, Acerca de).
- **Velos**: bajo la barra de estado, vidrio con desenfoque progresivo en cuanto hay scroll; abajo, `BottomFade`
  detrás de la barra y el mini-reproductor. Las listas reservan `LocalBottomInset` (barra + mini + márgenes).
- **Inicio**: saludo → banner "Conecta tus cuentas" → Top Picks → Escuchado recientemente → Hecho para ti →
  Tus playlists de YouTube y Spotify → Estados de ánimo → Lanzamientos recientes → Estaciones → Tu Replay.
- **Novedades**: lanzamientos, listas de éxitos, vídeos populares, géneros. **Radio**: Ondara Flow + radios.
- **Biblioteca**: fijados 3×2 → categorías → filtro de origen → agregado recientemente.
- **Buscar**: campo de vidrio + cuadrícula de categorías de color; sugerencias y resultados por tipo.

## Páginas inmersivas (Artista, Álbum, Playlist)

`ImmersiveHeroScaffold`: imagen a sangre bajo la barra de estado (55 % del alto); mitad inferior con
`HazeProgressive.verticalGradient` (0 → blur de 44 dp) fundida con el color dominante, que es el fondo de toda la
página (`rememberImmersiveColors`: dominante mezclado con el fondo del tema, texto forzado a AA); nombre
centrado, etiqueta de vidrio opcional, fila de `GlassIconButton` (ℹ, ▶ blanco, ☆) o píldoras Reproducir/Aleatorio;
botones flotantes de vidrio arriba; contenido en `GlassCard`. En temas mate la página conserva el fondo del tema.

## Reproductor

| Layout | Descripción |
|---|---|
| `CINEMATIC` ("Cine", por defecto en instalación nueva) | vídeo de la canción a pantalla completa (center crop, mudo, sincronizado con el audio); velos arriba y abajo; línea actual de la letra; píldora "Cambiar a vídeo"; fila de canción con ✕ y ⊕; progreso fino; controles con play en círculo blanco; fila de dispositivos/compartir/cola; tarjeta de letra, créditos y artista al hacer scroll. Sin vídeo: portada con Ken Burns sobre mesh gradient |
| `BENTO` (Tinta) | bloques: portada, título/artista (claro), progreso (primario), controles (apagado) |
| `ARTWORK_HERO`, `IMMERSIVE`, `MINIMAL`, `VINYL` | los de la v1 |

El usuario elige el layout en Ajustes › Reproductor (vista previa en vivo); "Según el tema" usa el del `ThemeSpec`.

## Perfil

Composición *bento* con los tokens de bloque del tema: nombre + carita del estado de ánimo, canción del día,
minutos del mes y género top, "Tu paleta" (hex en monoespaciada), artista top tramado y pie con "Escuchando
desde". Se comparte como imagen (el bento se graba en un `GraphicsLayer`).

## Curvas de animación

| Animación | Especificación |
|---|---|
| Presión de cualquier elemento táctil | escala a `motion.pressScale`; vuelta con spring `damping 0.55, stiffness 700` |
| Spring del tema (`motion.spring()`) | `dampingRatio` / `stiffness` del JSON (Ondara 0.80/380 · Mono 1.0/900 · Vinilo 0.74/200 · Neón 0.60/520) |
| Mini → reproductor completo | `SharedTransitionLayout` + `SeekableTransitionState`; el arrastre hace `seekTo(fracción)`; al soltar, spring `damping ≥ 0.8, stiffness × 0.7`; umbral 22 % |
| Portada que respira | escala 1.0 ↔ 0.82, spring `damping 0.7, stiffness 260` |
| Play/pausa | interpolación de vértices barras ↔ triángulo, spring `damping 0.75, stiffness 500` |
| Mesh gradient | 4 focos en órbita, ciclo de 20 s (`t · 2π/20`); cambio de paleta `tween 800 ms` |
| Transición de tema | *circular reveal* desde el toque, spring `damping 1.0, stiffness 110` (~400 ms) |
| Letra: línea activa | scroll con `motion.spring()`; énfasis (escala 0.94→1, alfa 0.34→1) con spring; barrido de palabra con borde difuminado de 28 dp |
| Listas | entrada escalonada `staggerMs` (30 ms) por elemento, máx. 12, solo la primera vez |
| Large title collapse (todas las pestañas) | el título escala 1→0.8 y se desvanece en 72 dp de scroll; el velo de vidrio aparece en los primeros 20 dp y el título pequeño en el último 40 % |
| Barra inferior | encogido/expansión con spring `dampingRatio 0.8`; el ancho se interpola en la fase de layout; el resaltado de pestaña se desliza con el mismo spring |
| Héroe inmersivo | parallax 0.5×; *stretch*: el sobre-desplazamiento (resistencia 0.5) escala la imagen anclada arriba y vuelve con el spring del tema; la barra de vidrio entra con fundido + 10 dp |
| Hoja de Cuenta | escala 0.72→1 desde la esquina del avatar + alfa, spring del tema |
| Tramado → color | mezcla del shader 1→0 en `tween(300)` |
| Perfil | bloques en entrada escalonada de 40 ms; inclinación 3D al mantener presionado |
| Cine | crossfade vídeo/portada 400 ms; controles 250 ms; Ken Burns 24 s; ondas de doble toque |
| Vinilo | 200°/s; al pausar frena con `motion.gentle()` |
| Hápticos | `tick` en play/pausa, saltos, chips e interruptores; `edge` al tope de un deslizamiento; `confirm` en pulsación larga y al coger un elemento de la cola |

**Reducir animaciones** (escala de animación del sistema = 0): los springs pasan a `tween(150)`, el
reveal de tema a un fundido de 150 ms, el mesh y el vinilo quedan estáticos, y desaparecen el
shimmer, el stagger y el blur de las líneas de letra. El layout Cine usa la portada estática en lugar del vídeo.

## Rendimiento

- Lo que cambia cada frame (progreso del mini-reproductor, uniforms del shader, barrido de la letra,
  pulso de Neón) se lee en la fase de dibujo: cero recomposiciones.
- `LazyColumn` siempre con `key` y `contentType`.
- Miniaturas pedidas al tamaño justo (`String.thumbnail(px)`).
- Presupuesto: 60 fps mínimo en gama media; se mide con `:benchmark` (`ScrollBenchmark`, `StartupBenchmark`).
