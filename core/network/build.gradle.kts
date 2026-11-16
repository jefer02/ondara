plugins { id("ondara.jvm.library") }

dependencies {
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
}
