plugins {
    id("ondara.android.library")
    id("ondara.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines.android)
}
