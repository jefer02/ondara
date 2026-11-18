plugins {
    id("ondara.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.client.mock)
}
