plugins {
    id("ondara.android.compose")
    id("ondara.hilt")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

dependencies {
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.haze)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
