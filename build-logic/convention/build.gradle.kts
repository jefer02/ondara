plugins { `kotlin-dsl` }

group = "app.ondara.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") { id = "ondara.jvm.library"; implementationClass = "JvmLibraryConventionPlugin" }
        register("androidLibrary") { id = "ondara.android.library"; implementationClass = "AndroidLibraryConventionPlugin" }
        register("androidApplication") { id = "ondara.android.application"; implementationClass = "AndroidApplicationConventionPlugin" }
        register("androidCompose") { id = "ondara.android.compose"; implementationClass = "AndroidComposeConventionPlugin" }
        register("androidFeature") { id = "ondara.android.feature"; implementationClass = "AndroidFeatureConventionPlugin" }
        register("hilt") { id = "ondara.hilt"; implementationClass = "HiltConventionPlugin" }
    }
}
