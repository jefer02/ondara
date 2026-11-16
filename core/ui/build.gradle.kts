plugins {
    id("ondara.android.compose")
    id("ondara.hilt")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:designsystem"))
    implementation(project(":core:domain"))
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
}
