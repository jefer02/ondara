package app.ondara.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "app.ondara"
private const val TIMEOUT = 8_000L

/** Recorre el camino crítico: arranque → inicio con contenido → scroll → búsqueda → biblioteca. */
private fun MacrobenchmarkScope.criticalJourney() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.textContains("Tu música")), TIMEOUT)
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) { list.fling(Direction.DOWN) }
        repeat(2) { list.fling(Direction.UP) }
    }
    device.findObject(By.desc("Buscar"))?.click()
    device.wait(Until.hasObject(By.text("Buscar")), TIMEOUT)
    device.findObject(By.desc("Biblioteca"))?.click()
    device.waitForIdle()
}

/**
 * Arranque en frío. Objetivo: `timeToInitialDisplayMs` < 600 ms con Baseline Profile.
 * Ejecutar: `./gradlew :benchmark:connectedBenchmarkAndroidTest` en un dispositivo físico.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartNoProfile() = startup(CompilationMode.None())

    @Test fun coldStartBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 8,
    ) {
        pressHome()
        startActivityAndWait()
    }
}

/**
 * Fluidez del scroll del inicio. Objetivo: `frameOverrunMs` P99 ≤ 0 (frames lentos < 1 %)
 * y `frameDurationCpuMs` P90 por debajo de 8.3 ms en pantallas de 120 Hz.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun homeScroll() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = 6,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.scrollable(true)), TIMEOUT)
        },
    ) {
        val list = device.findObject(By.scrollable(true)) ?: return@measureRepeated
        list.setGestureMargin(device.displayWidth / 5)
        repeat(4) { list.fling(Direction.DOWN) }
        repeat(4) { list.fling(Direction.UP) }
    }
}

/**
 * Genera el Baseline Profile real recorriendo el camino crítico. El resultado sustituye al perfil
 * escrito a mano en `app/src/main/baseline-prof.txt`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) { criticalJourney() }
}
