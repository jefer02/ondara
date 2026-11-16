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
| `OndaraColors` | `background`, `surface`, `surfaceHigh`, `onBackground`, `onMuted`, `accent`, `onAccent`, `outline`, `error`, `isDark`, `scrim` |
| `OndaraTypography` | `hero` 40 · `display` 30 · `title` 21 · `headline` 17 · `body` 15 · `bodyStrong` · `caption` 13 · `label` 12 · `mono` 12 · `monoLarge` 24 · `lyric` 26 (sp, × `scale`) |
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
typography  { display, body, mono: GROTESK|INTER|MONO|DOT|SERIF|WIDE, pesos, scale, displayTracking, uppercaseLabels }
shapes      { small, medium, large, artwork, squircle, pillControls }
materials   { blur, surfaceOpacity, saturation, hairline, noise }
motion      { stiffness, dampingRatio, fastMs, mediumMs, slowMs, pressScale, staggerMs }
playerLayout  ARTWORK_HERO | IMMERSIVE | MINIMAL | VINYL
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

## Componentes

`component/Basics.kt` — `Text`, `Label`, `Surface`, `GlassSurface`, `SquircleShape`, `Modifier.pressable`, `Haptics`.
`component/Controls.kt` — `OndaraButton` (FILLED/TONAL/GHOST), `OndaraIconButton`, `OndaraChip`, `OndaraSwitch`,
`OndaraSlider`, `OndaraValueSlider`, `OndaraTextField`, `Skeleton`, `Hairline`, `SettingRow`, `SectionTitle`, `Modifier.staggeredEntry`.
`component/Layout.kt` — `ScreenScaffold`, `OndaraNavigation` (3 estilos), `OndaraSheet`, `OndaraDialog`, `OndaraBackground`, `EmptyState`.
`component/Effects.kt` — `PlayPauseButton` (morphing), `MeshGradientBackground` (AGSL), `Modifier.grain`, `ThemeTransitionHost`.
`icon/OndaraIcons.kt` — 40 iconos propios de trazo.
`Showcase.kt` — catálogo vivo; base de los screenshot tests (`build/outputs/roborazzi/showcase_<tema>.png`).

`:core:ui` añade los compuestos: `Artwork`, `SongRow`, `MusicCard`, `Shelf`, `MusicItemRow`,
`ParallaxHeader`, `DetailScaffold`, `SongMenuSheet`, `PlaylistPickerSheet`, `PlayingBars`.

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
| Large title collapse (Cupertino) | el título escala 1→0.78 y se desvanece en 160 px de scroll; aparece la barra de vidrio |
| Parallax (álbum/artista) | la imagen se desplaza a 0.5× y crece al sobre-desplazar |
| Vinilo | 200°/s; al pausar frena con `motion.gentle()` |
| Hápticos | `tick` en play/pausa, saltos, chips e interruptores; `edge` al tope de un deslizamiento; `confirm` en pulsación larga y al coger un elemento de la cola |

**Reducir animaciones** (escala de animación del sistema = 0): los springs pasan a `tween(150)`, el
reveal de tema a un fundido de 150 ms, el mesh y el vinilo quedan estáticos, y desaparecen el
shimmer, el stagger y el blur de las líneas de letra.

## Rendimiento

- Lo que cambia cada frame (progreso del mini-reproductor, uniforms del shader, barrido de la letra,
  pulso de Neón) se lee en la fase de dibujo: cero recomposiciones.
- `LazyColumn` siempre con `key` y `contentType`.
- Miniaturas pedidas al tamaño justo (`String.thumbnail(px)`).
- Presupuesto: 60 fps mínimo en gama media; se mide con `:benchmark` (`ScrollBenchmark`, `StartupBenchmark`).
