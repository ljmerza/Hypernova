import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The data layer shared by the Android app and a later desktop app (docs/PLATFORMS.md, "The shared
// module"): Android and desktop JVM only, so commonMain sees the JDK, OkHttp and org.json as they are.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    // Keeps the stability the app's Compose compiler reads off these classes, as when they were in the app.
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidLibrary {
        namespace = "com.prudnikweb.hypernova.shared"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTestBuilder { }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // JetBrains' runtime: on Android it is androidx.compose.runtime itself, on desktop its own build.
            api(libs.compose.multiplatform.runtime)
            api(libs.kotlinx.coroutines.core)
            api(libs.okhttp)
        }
        // Android has org.json in the platform; on the desktop JVM it's the library.
        getByName("desktopMain").dependencies {
            api(libs.org.json)
        }
        // The protocol's contract fixtures, shared with the daemon's tests (docs/PROTOCOL.md, "Contract fixtures").
        commonTest.get().resources.srcDir("../../protocol")
        // Tests of the real hub against a fake daemon, run here off Android and in the app on Robolectric.
        commonTest.get().kotlin.srcDir("src/hubTest/kotlin")
        commonTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.okhttp.tls)
        }
        // android.jar only has stubs of org.json: its unit tests run the real one, as the app's do.
        getByName("androidHostTest").dependencies {
            implementation(libs.org.json)
        }
    }
}
