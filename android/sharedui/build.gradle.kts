import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the phone and the desktop app both draw: Atlas and the chat (docs/PLATFORMS.md, "The shared drawing"). Android and
// the desktop JVM only, so commonMain sees the JDK, as :shared's does.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.prudnikweb.hypernova.sharedui"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.compose.multiplatform.ui)
            api(libs.compose.multiplatform.runtime.saveable)
            // compileOnly, with each target's own below: on Android JetBrains' foundation and animation are only pointers to
            // AndroidX's, and this way they don't appear on :app's classpath at all (docs/DECISIONS.md, "The chat on the desktop").
            compileOnly(libs.compose.multiplatform.foundation)
            compileOnly(libs.compose.multiplatform.animation)
        }
        androidMain.dependencies {
            // At the version the app's own BOM says, as the app uses them.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            api(libs.androidx.compose.foundation)
            api(libs.androidx.compose.animation)
        }
        getByName("desktopMain").dependencies {
            api(libs.compose.multiplatform.foundation)
            api(libs.compose.multiplatform.animation)
        }
    }
}

// :shared's its own code in another module, built with it, as for :app: a nullable property checked on one line is still known
// non-null on the next.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val shared = project(":shared").layout.buildDirectory.get().asFile
    friendPaths.from(libraries.filter { it.startsWith(shared) })
}
