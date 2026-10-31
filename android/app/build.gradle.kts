import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Minutes since 2026-01-01: every build gets a higher versionCode, so Android installs a newer
 * one as an update and the Mac can tell the phone it has a newer app. (A value source is read
 * again on every build, even with the configuration cache.)
 */
abstract class BuildMinute : ValueSource<Int, ValueSourceParameters.None> {
    override fun obtain(): Int = ((System.currentTimeMillis() / 60_000L) - 29_453_760L).toInt()
}
val buildMinute: Int = providers.of(BuildMinute::class) {}.get()
/**
 * The versionName. A release passes its own, `-PappVersion=0.3.1` (scripts/next-version.sh, from
 * the commits since the last release; docs/RELEASING.md, "Versions"). A local build (`hnctl apk`, a
 * dev build) says where it stands from git: the last release's tag, and after it the commits since,
 * the commit and whether it had changes (`git describe`: "0.5.0", "0.5.0-3-g1a2b3c4-dirty"), so
 * Settings → About names the version it really is. Without git or a tag: "0.0.0-dev".
 */
fun gitVersion(): String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").takeIf { Regex("""\d+\.\d+\.\d+[\w.+-]*""").matches(it) }
}.getOrNull()
val appVersion: String = providers.gradleProperty("appVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: gitVersion() ?: "0.0.0-dev"

/**
 * The release key (docs/RELEASING.md): a properties file outside the repo (storeFile, relative to
 * the file's folder or absolute, storePassword, keyAlias, keyPassword), at HYPERNOVA_KEYSTORE_PROPS
 * or ~/.hypernova-signing/keystore.properties. Without it a release build still works (a
 * contributor's, signed with their debug key, with a warning), but `distRelease`, which makes the
 * APK people install, refuses to run: the first public key is the only one that can update it.
 */
val keystoreProps: File = providers.environmentVariable("HYPERNOVA_KEYSTORE_PROPS").orNull?.takeIf { it.isNotBlank() }?.let(::File)
    ?: File(System.getProperty("user.home"), ".hypernova-signing/keystore.properties")
val releaseKey: Properties? = keystoreProps.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
/** The keystore file the properties name (relative to their folder, or absolute). */
val releaseStore: File? = releaseKey?.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }?.let { keystoreProps.absoluteFile.parentFile.resolve(it) }

android {
    namespace = "com.prudnikweb.hypernova"
    // Compose 1.12 and Haze 2 require compiling against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.prudnikweb.hypernova"
        minSdk = 29
        targetSdk = 36
        versionCode = buildMinute
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            fun prop(name: String): String = releaseKey.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("$keystoreProps has no $name (see docs/RELEASING.md)")
            storeFile = releaseStore ?: File(prop("storeFile"))
            storePassword = prop("storePassword")
            keyAlias = prop("keyAlias")
            keyPassword = prop("keyPassword")
            // minSdk 29: no v1 (JAR) signature; v2, and v3 (the scheme that can rotate the key later).
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // Not debuggable (no `run-as`, so adb can't read the pairing tokens), no Compose tooling,
            // shrunk and renamed by R8 (rules: proguard-rules.pro; the mapping goes with the release).
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release key when this machine has it; else the debug key, so a contributor's
            // release build still installs (packageRelease warns, distRelease refuses).
            signingConfig = if (releaseKey != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    // REST and the live WebSocket to the Mac's daemon. JSON is parsed with the platform's org.json.
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // JVM unit tests: `./gradlew :app:testDebugUnitTest` (voice's logic and gesture, app/src/test).
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

// `./gradlew :app:dist`: the debug APK in ../dist, with its version in a note next to it. The Mac
// app bundles both and serves them; the phone compares that version with its own.
// Plain values, copied into each task's own val: the configuration cache can't keep a reference to
// the build script itself (a script-level function in doFirst broke :app:dist).
val hasReleaseKey = releaseKey != null
val releaseKeyPath: String = keystoreProps.path
tasks.matching { it.name.startsWith("package") && it.name.contains("Release") }.configureEach {
    // Said once, by the task that signs the APK (packageReleaseResources matches too).
    val signed = hasReleaseKey || name != "packageRelease"
    val props = releaseKeyPath
    doFirst {
        if (!signed) logger.warn(
            "\nWARNING: no release key at $props (or HYPERNOVA_KEYSTORE_PROPS): this release APK is signed with " +
                "the DEBUG key. Fine for testing; never hand it out (distRelease refuses). See docs/RELEASING.md.\n",
        )
    }
}

tasks.register("dist") {
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val out = rootProject.layout.projectDirectory.dir("../dist").asFile
    val note = """{"versionCode": $buildMinute, "versionName": "$appVersion"}""" + "\n"
    inputs.file(apk)
    outputs.files(File(out, "hypernova-debug.apk"), File(out, "hypernova-debug.json"))
    doLast {
        out.mkdirs()
        apk.get().asFile.copyTo(File(out, "hypernova-debug.apk"), overwrite = true)
        File(out, "hypernova-debug.json").writeText(note)
    }
}

/**
 * `./gradlew :app:distRelease`: the APK people install (docs/RELEASING.md). The release build
 * signed with the release key, in ../dist as hypernova.apk with its version note (hypernova.json:
 * the daemon serves both) and R8's mapping (hypernova-mapping.txt: keep it with the release, it turns crash
 * traces back into names). Refuses to start without the release key, and checks the signed APK:
 * v2 and v3 signatures, and the release key's certificate (never Android's debug one).
 */
val checkReleaseKey = tasks.register("checkReleaseKey") {
    val signed = hasReleaseKey
    val props = releaseKeyPath
    doFirst {
        if (!signed) throw GradleException("distRelease needs the release key, and there is no $props (HYPERNOVA_KEYSTORE_PROPS names another keystore.properties). It never publishes a debug-signed APK: the first public key is the only one that can update the app. See docs/RELEASING.md.")
    }
}
// Fail before the long build, not after it.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { mustRunAfter(checkReleaseKey) }

tasks.register("distRelease") {
    dependsOn(checkReleaseKey, "assembleRelease")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    val out = rootProject.layout.projectDirectory.dir("../dist").asFile
    val note = """{"versionCode": $buildMinute, "versionName": "$appVersion"}""" + "\n"
    // The certificate the APK must carry: the release keystore's own, read when the task runs.
    val store = releaseStore
    val storePass = releaseKey?.getProperty("storePassword")?.trim()
    val alias = releaseKey?.getProperty("keyAlias")?.trim()
    inputs.file(apk)
    inputs.file(mapping)
    outputs.files(File(out, "hypernova.apk"), File(out, "hypernova.json"), File(out, "hypernova-mapping.txt"))
    doLast {
        val file = apk.get().asFile
        // Checked as for Android 7 and up: with minSdk 29 alone a verifier looks at v3 only, and the
        // v2 block (for any tool or store that reads just v2) would go unchecked.
        val result = com.android.apksig.ApkVerifier.Builder(file).setMinCheckedPlatformVersion(24).build().verify()
        if (!result.isVerified) throw GradleException("$file does not verify: ${result.errors}")
        if (!result.isVerifiedUsingV2Scheme || !result.isVerifiedUsingV3Scheme) {
            throw GradleException("$file must carry v2 and v3 signatures (v2 ${result.isVerifiedUsingV2Scheme}, v3 ${result.isVerifiedUsingV3Scheme})")
        }
        val cert = result.signerCertificates.singleOrNull() ?: throw GradleException("$file has ${result.signerCertificates.size} signers, expected one")
        if (cert.subjectX500Principal.name.contains("CN=Android Debug")) throw GradleException("$file is signed with an Android debug key")
        val ks = KeyStore.getInstance("PKCS12")
        store!!.inputStream().use { ks.load(it, storePass!!.toCharArray()) }
        val sha = MessageDigest.getInstance("SHA-256")
        val expected = sha.digest(ks.getCertificate(alias!!)?.encoded ?: throw GradleException("$store has no certificate for $alias")).joinToString(":") { "%02X".format(it) }
        val actual = sha.digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        if (actual != expected) throw GradleException("$file is signed with $actual, not the release key ($expected)")
        out.mkdirs()
        file.copyTo(File(out, "hypernova.apk"), overwrite = true)
        File(out, "hypernova.json").writeText(note)
        mapping.get().asFile.copyTo(File(out, "hypernova-mapping.txt"), overwrite = true)
        logger.lifecycle("distRelease: ${File(out, "hypernova.apk")} (${file.length() / 1024} KB), ${note.trim()}, certificate SHA-256 $actual")
    }
}
