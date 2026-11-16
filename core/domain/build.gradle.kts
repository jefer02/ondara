plugins { id("ondara.jvm.library") }

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.paging.common)
    implementation(libs.javax.inject)
}
