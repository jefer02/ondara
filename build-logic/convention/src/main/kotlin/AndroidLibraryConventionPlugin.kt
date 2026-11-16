import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK
            namespace = "app.ondara" + path.replace(":", ".")
            defaultConfig {
                minSdk = MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                if (file("consumer-rules.pro").exists()) consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }
            testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
            lint { abortOnError = true; warningsAsErrors = false; checkReleaseBuilds = false }
        }
        configureKotlin()
        configureTests()
        dependencies {
            add("testImplementation", catalog.lib("junit4"))
            add("testRuntimeOnly", catalog.lib("junit-vintage"))
        }
        configureDetekt()
    }
}
