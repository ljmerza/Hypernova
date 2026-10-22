plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Haze 2 and Compose 1.12 are built with Kotlin 2.4; AGP 9's built-in Kotlin uses the KGP found here.
    alias(libs.plugins.kotlin.android) apply false
}
