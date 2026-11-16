import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Un feature = librería Compose + Hilt que solo ve `:core:*`.
 * Regla: feature → domain; ningún feature depende de otro feature.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ondara.android.compose")
        pluginManager.apply("ondara.hilt")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:domain"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", catalog.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", catalog.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", catalog.lib("hilt-navigation-compose"))
            add("implementation", catalog.lib("kotlinx-coroutines-android"))
        }
    }
}
