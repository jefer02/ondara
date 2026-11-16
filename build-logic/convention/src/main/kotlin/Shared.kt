import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

const val COMPILE_SDK = 37
const val MIN_SDK = 26
const val TARGET_SDK = 36
val JAVA_VERSION = JavaVersion.VERSION_21

internal val Project.catalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Opciones de Kotlin comunes a todos los módulos (JVM y Android). */
fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
            )
        }
    }
}

/** JUnit 5 como plataforma; el motor vintage permite tests JUnit 4 (Robolectric/Roborazzi). */
fun Project.configureTests(configuration: String = "testImplementation") {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Varios módulos aún no tienen tests propios; Gradle 9 lo trataría como error.
        failOnNoDiscoveredTests.set(false)
        maxHeapSize = "1536m"
        // Robolectric: los tests de pantalla necesitan gráficos nativos y red real opcional.
        systemProperty("robolectric.logging.enabled", "false")
    }
    dependencies {
        add(configuration, platform(catalog.lib("junit-bom")))
        add(configuration, catalog.lib("junit-jupiter"))
        add(configuration, catalog.lib("turbine"))
        add(configuration, catalog.lib("mockk"))
        add(configuration, catalog.lib("kotlinx-coroutines-test"))
        add("testRuntimeOnly", platform(catalog.lib("junit-bom")))
        add("testRuntimeOnly", catalog.lib("junit-launcher"))
    }
}

fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin")
    }
    dependencies { add("detektPlugins", catalog.lib("detekt-formatting")) }
}
