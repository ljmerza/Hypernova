import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop app: a thin shell over :shared (docs/PLATFORMS.md, "The desktop app"). JVM, Compose for Desktop.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The app's version as a local build of the phone app says it (app/build.gradle.kts): `-PappVersion`, else `git describe`.
fun gitVersion(): String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").takeIf { Regex("""\d+\.\d+\.\d+[\w.+-]*""").matches(it) }
}.getOrNull()
val appVersion: String = providers.gradleProperty("appVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: gitVersion() ?: "0.0.0-dev"

val appMain = rootProject.layout.projectDirectory.dir("app/src/main")

/**
 * What this app takes from the phone's, as it is, so there's one copy: the strings (read at run time,
 * DesktopStrings.kt) with a list of their files, since a jar can't list a folder, the two fonts, and this build's version.
 */
val desktopResources = tasks.register<Sync>("desktopResources") {
    val out = layout.buildDirectory.dir("generated/desktopResources")
    val version = appVersion
    inputs.property("version", version)
    into(out)
    from(appMain.dir("res/values")) {
        include("strings*.xml")
        into("res/values")
    }
    from(appMain.dir("res/font")) {
        include("*.ttf")
        into("fonts")
    }
    doLast {
        val root = out.get().asFile
        val values = root.resolve("res/values")
        values.resolve("index.txt").writeText(values.list().orEmpty().filter { it.endsWith(".xml") }.sorted().joinToString("\n") + "\n")
        root.resolve("desktop").mkdirs()
        root.resolve("desktop/build.properties").writeText("version=$version\n")
    }
}

/** The app's Atlas marks, compiled here as they are: plain Compose drawing, with nothing of Android or `R` in them. */
val appMarks = tasks.register<Sync>("appMarks") {
    from(appMain.dir("java/com/prudnikweb/hypernova/ui/components")) { include("Marks.kt") }
    into(layout.buildDirectory.dir("generated/appMarks/com/prudnikweb/hypernova/ui/components"))
}

/** The hub tests' scripted daemon and the XML reader they check against (shared/src/hubTest), without the tests. */
val hubRig = tasks.register<Sync>("hubRig") {
    from(rootProject.layout.projectDirectory.dir("shared/src/hubTest/kotlin/com/prudnikweb/hypernova/data/live")) {
        include("FakeDaemon.kt", "XmlStrings.kt")
    }
    into(layout.buildDirectory.dir("generated/hubRig/com/prudnikweb/hypernova/data/live"))
}

sourceSets.named("main") { resources.srcDir(desktopResources) }
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/appMarks")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/hubRig")) }
tasks.named("compileKotlin") { dependsOn(appMarks) }
tasks.named("compileTestKotlin") { dependsOn(hubRig) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

// `./gradlew :desktop:run` (docs/DEV-SETUP.md, "The desktop app"). `:desktop:packageDeb` and
// `:desktop:createDistributable` build a Linux package and a folder to run, each with its own Java,
// by hand for now; CI builds nothing yet (docs/LATER.md, L105).
compose.desktop {
    application {
        mainClass = "com.prudnikweb.hypernova.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "hypernova-client"
            packageVersion = providers.gradleProperty("desktopVersion").getOrElse("0.22.0")
            description = "A desktop client for Hypernova servers"
            vendor = "Leonardo Merza"
            licenseFile.set(rootProject.file("../LICENSE"))
            // The whole runtime, not a guessed list of modules: pairing needs TLS and its EC keys.
            includeAllModules = true
            linux {
                iconFile.set(project.file("icons/hypernova-client.png"))
                packageName = "hypernova-client"
                debMaintainer = "ljmerza@users.noreply.github.com"
                menuGroup = "Development"
                shortcut = true
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // No display on CI or here: the screens render with no window (Compose's own test scene).
    systemProperty("java.awt.headless", "true")
    // Core is one object per process, as it is on a phone: a new process per test class.
    forkEvery = 1
    maxParallelForks = 1
    // Where the screen tests leave their pictures for someone to look at.
    systemProperty("hypernova.renders", layout.buildDirectory.dir("renders").get().asFile.path)
}
