import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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

// The app's version as a local build of the phone app says it (app/build.gradle.kts): `-PappVersion`, else `-PdesktopVersion`
// (what a release builds with, so the app says the release it is), else `git describe`.
fun gitVersion(): String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").takeIf { Regex("""\d+\.\d+\.\d+[\w.+-]*""").matches(it) }
}.getOrNull()
val desktopVersionGiven: String? = providers.gradleProperty("desktopVersion").orNull?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
val appVersion: String = providers.gradleProperty("appVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: desktopVersionGiven ?: gitVersion() ?: "0.0.0-dev"
val desktopVersion: String = desktopVersionGiven ?: appVersion

val appMain = rootProject.layout.projectDirectory.dir("app/src/main")

/** The names a rename changes, shared with the workflow and the scripts. Nothing else here should say them. */
val clientNames: Map<String, String> = project.file("packaging/client-names.sh").readLines()
    .mapNotNull { Regex("""^(\w+)='(.*)'$""").find(it)?.destructured?.let { (key, value) -> key to value } }.toMap()
val clientPackage = clientNames.getValue("CLIENT_PACKAGE")
val clientTitle = clientNames.getValue("CLIENT_TITLE")
val hostOs: String = System.getProperty("os.name").orEmpty().lowercase()
val hostFormat = when {
    hostOs.startsWith("mac") -> TargetFormat.Dmg
    hostOs.startsWith("windows") -> TargetFormat.Msi
    else -> TargetFormat.Deb
}
val clientResources = "generated/clientResources"

/**
 * A package's version, each system's own way (docs/DECISIONS.md, "The desktop client's packages"). They count from the
 * numbers of the tag, as anything after them (`v0.23.0-rc1`, or `git describe`'s) is more than a Mac or Windows takes.
 *
 * - Linux: as it is.
 * - Mac: the first number one higher. jpackage refuses a first number of 0 (its CFBundleVersion.java), so 0.23.0 is 1.23.0
 *   and 1.0.0 will be 2.0.0: always ordered, and the same step up from each release. The app itself still says 0.23.0.
 * - Windows: as it is, up to 255.255.65535, which is all an .msi has. Releases count minors quickly, so from a minor of
 *   255 the minor folds into the third number, minor-255 times 64 plus the patch: 0.256.1 is 0.255.65. Still ordered,
 *   with 1024 minors more, as long as no patch reaches 64.
 */
val desktopParts: List<Int> = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(desktopVersion)?.destructured?.toList()?.map { it.toInt() } ?: listOf(0, 0, 0)
val debVersion = desktopParts.joinToString(".")
val macVersion = "${desktopParts[0] + 1}.${desktopParts[1]}.${desktopParts[2]}"
fun msiVersion(): String {
    val (major, minor, patch) = desktopParts
    fun tooBig(): Nothing = throw GradleException("$desktopVersion doesn't fit an .msi's version: docs/DECISIONS.md, \"The desktop client's packages\"")
    if (major > 255) tooBig()
    if (minor < 255) return if (patch > 65535) tooBig() else "$major.$minor.$patch"
    val folded = (minor - 255) * 64 + patch
    return if (patch > 63 || folded > 65535) tooBig() else "$major.255.$folded"
}

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

// The window's own builds: `:desktop:run` (docs/DEV-SETUP.md, "The desktop app"). The packages, each with its own Java:
// `:desktop:packageDeb` and `:desktop:createDistributable` on Linux, `:desktop:packageDmg` on a Mac, `:desktop:packageMsi`
// on Windows. A package can only be built on its own system, so CI has a runner for each
// (.github/workflows/client-packages.yml; docs/RELEASING.md, "The desktop client's packages").
compose.desktop {
    application {
        mainClass = "com.prudnikweb.hypernova.desktop.MainKt"
        nativeDistributions {
            // This system's only: Compose checks the version of every format named here, whichever system it's on.
            targetFormats(hostFormat)
            // A launcher named with a space would be a nuisance on Linux; on the other two people read this name.
            packageName = if (hostFormat == TargetFormat.Deb) clientPackage else clientTitle
            packageVersion = debVersion
            description = "A desktop client for Hypernova servers"
            vendor = "Leonardo Merza"
            licenseFile.set(rootProject.file("../LICENSE"))
            appResourcesRootDir.set(layout.buildDirectory.dir(clientResources))
            // The whole runtime, not a guessed list of modules: pairing needs TLS and its EC keys.
            includeAllModules = true
            linux {
                iconFile.set(project.file("icons/hypernova-client.png"))
                packageName = clientPackage
                debMaintainer = "ljmerza@users.noreply.github.com"
                menuGroup = "Development"
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("icons/hypernova-client.icns"))
                bundleID = "com.prudnikweb.hypernova.desktop"
                packageVersion = macVersion
            }
            windows {
                iconFile.set(project.file("icons/hypernova-client.ico"))
                // What makes a new .msi replace the one installed. Never change it, a rename included: installs would pile up.
                upgradeUuid = "5d38dc1e-25aa-4b37-a8f7-c995d4436555"
                menu = true
                menuGroup = clientTitle
                shortcut = true
                // Only worked out here, so a version it can't hold costs the .msi and nothing else.
                if (hostFormat == TargetFormat.Msi) packageVersion = msiVersion()
            }
        }
    }
}

/** LICENSE, NOTICE and the rest, in every package: `resources/legal` beside the app's jars (docs/RELEASING.md). */
val clientLegal = tasks.register<Sync>("clientLegal") {
    val repo = rootProject.layout.projectDirectory.dir("..")
    into(layout.buildDirectory.dir("$clientResources/common/legal"))
    from(repo) { include("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md") }
    from(repo.dir("third_party/fonts")) { include("OFL-*.txt") }
    from(layout.projectDirectory.dir("packaging/legal"))
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(clientLegal) }

/** Pairs an installed package from inside its own Java: PackageProbe.kt says how. In no package. */
val probeCompilation = kotlin.target.compilations.create("probe") { associateWith(kotlin.target.compilations.getByName("main")) }
tasks.register<Jar>("probeJar") {
    from(probeCompilation.output.allOutputs)
    destinationDirectory.set(layout.buildDirectory.dir("probe"))
    archiveFileName.set("package-probe.jar")
    manifest { attributes("Premain-Class" to "com.prudnikweb.hypernova.desktop.PackageProbe") }
}

/** Each system's form of a version, for a look: `./gradlew -q :desktop:packageVersions -PdesktopVersion=0.23.0`. */
tasks.register("packageVersions") {
    val lines = "deb=$debVersion mac=$macVersion msi=${runCatching { msiVersion() }.getOrElse { it.message }}"
    doLast { println(lines) }
}

// A system with no menus shouldn't fail the install: packaging/deb-menu-optional.sh says why, and why it's done to the
// finished package. Compose empties jpackage's resource folder just before it runs, so custom scripts can't go there.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) {
        val script = project.file("packaging/deb-menu-optional.sh")
        val out = destinationDir
        doLast {
            val deb = checkNotNull(out.get().asFile.listFiles { f -> f.name.endsWith(".deb") }?.singleOrNull()) { "no one .deb in ${out.get()}" }
            val done = ProcessBuilder("bash", script.path, deb.path).inheritIO().start().waitFor()
            check(done == 0) { "${script.name} failed: $done" }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Never the keychain of whoever runs these: a test that wants one is handed its own (KeychainTest).
    environment("HYPERNOVA_CLIENT_KEYCHAIN", "off")
    // No display on CI or here: the screens render with no window (Compose's own test scene).
    systemProperty("java.awt.headless", "true")
    // Core is one object per process, as it is on a phone: a new process per test class.
    forkEvery = 1
    maxParallelForks = 1
    // Where the screen tests leave their pictures for someone to look at.
    systemProperty("hypernova.renders", layout.buildDirectory.dir("renders").get().asFile.path)
}
