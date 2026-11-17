plugins {
    id("ondara.android.feature")
    alias(libs.plugins.roborazzi)
}

dependencies {
    implementation(libs.androidx.paging.compose)
}

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
