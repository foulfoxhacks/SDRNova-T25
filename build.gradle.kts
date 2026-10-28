plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Windows caps paths at 260 characters and aapt2 fails when a checkout sits deep inside a user profile.
// Pass -Pt25.buildRoot=<short folder> to move every module's build output somewhere shorter.
providers.gradleProperty("t25.buildRoot").orNull?.let { root ->
    subprojects {
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}
