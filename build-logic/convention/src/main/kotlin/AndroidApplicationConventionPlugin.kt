import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<ApplicationExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
                isCoreLibraryDesugaringEnabled = true
            }
            buildFeatures { compose = true; buildConfig = true }
            testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
            lint {
                abortOnError = true
                checkDependencies = true
                checkReleaseBuilds = false
                // Haze se fija en 1.7.3 a propósito (la 2.x cambió toda la API; ver AGENT.md).
                disable += "NewerVersionAvailable"
            }
        }
        configureKotlin()
        configureTests()
        dependencies {
            add("coreLibraryDesugaring", catalog.lib("desugar"))
            add("implementation", platform(catalog.lib("compose-bom")))
            add("testImplementation", catalog.lib("junit4"))
            add("testRuntimeOnly", catalog.lib("junit-vintage"))
        }
        configureDetekt()
    }
}
