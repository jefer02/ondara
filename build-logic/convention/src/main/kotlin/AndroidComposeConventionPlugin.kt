import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Activa Compose en un módulo de librería Android y añade las dependencias base. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ondara.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<LibraryExtension> { buildFeatures { compose = true } }
        dependencies {
            add("implementation", platform(catalog.lib("compose-bom")))
            add("implementation", catalog.lib("compose-ui"))
            add("implementation", catalog.lib("compose-ui-graphics"))
            add("implementation", catalog.lib("compose-foundation"))
            add("implementation", catalog.lib("compose-animation"))
            add("implementation", catalog.lib("compose-ui-tooling-preview"))
            add("debugImplementation", catalog.lib("compose-ui-tooling"))
        }
    }
}
