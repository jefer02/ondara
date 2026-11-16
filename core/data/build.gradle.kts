plugins {
    id("ondara.android.library")
    id("ondara.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    api(project(":core:network"))
    api(project(":source:innertube"))
    api(project(":source:resolver"))
    api(project(":source:lyrics"))
    api(project(":source:spotify"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
