plugins { id("ondara.jvm.library") }

dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":source:innertube"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.newpipe.extractor)
    testImplementation(libs.ktor.client.mock)
}
