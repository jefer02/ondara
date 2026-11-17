import java.util.Properties

plugins {
    id("ondara.android.application")
    id("ondara.hilt")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val signingPropertiesFile = providers.environmentVariable("ONDARA_SIGNING_PROPERTIES")
    .orElse("${System.getProperty("user.home")}/.config/ondara/signing/signing.properties")
val releaseSigning = Properties().apply {
    val source = file(signingPropertiesFile.get())
    if (source.exists()) source.inputStream().use { load(it) }
}

android {
    namespace = "app.ondara"
    defaultConfig {
        applicationId = "app.ondara"
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    }
    signingConfigs {
        create("release") {
            if (releaseSigning.isNotEmpty()) {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            // R8 queda desactivado hasta poder validar las reglas keep (NewPipe/Rhino, Ktor) en un dispositivo.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // Variante que mide :benchmark (release no depurable + perfilable).
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module") } }
}

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":source:innertube"))
    implementation(project(":source:resolver"))
    implementation(project(":source:lyrics"))
    implementation(project(":playback"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:album"))
    implementation(project(":feature:artist"))
    implementation(project(":feature:playlist"))
    implementation(project(":feature:lyrics"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:themes"))
    implementation(project(":feature:news"))
    implementation(project(":feature:radio"))
    implementation(project(":feature:account"))
    implementation(project(":source:spotify"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.acra.core)
}
