# R8 rules for the release build (app/build.gradle.kts), on top of proguard-android-optimize.txt and
# the libraries' own consumer rules (Compose, AndroidX, Haze, OkHttp and Okio ship theirs; the tsnet
# AAR's proguard.txt keeps go.** and com.prudnikweb.hypernova.go.**). Manifest components (activities,
# the service, the widgets and other receivers, the FileProvider) are kept by AAPT2's own rules.
# Keep this list short: only what is reached by name, which R8 can't see.

# Readable crash traces: line numbers stay, file names say only "SourceFile", and
# dist/hypernova-mapping.txt (`./gradlew :app:distRelease`) turns the renamed classes back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The embedded Tailscale node (data/Tsnet.kt) is reached by reflection, by name
# (Class.forName("com.prudnikweb.hypernova.go.tsnet.Tsnet"), getMethod("start", …), a Proxy for its
# Listener), and its JNI code looks the go.Seq classes and methods up by name. The AAR's own
# proguard.txt keeps them; this says so here too, in case a hand-built AAR lacks that file.
-keep class go.** { *; }
-keep class com.prudnikweb.hypernova.go.** { *; }

# Already kept by proguard-android-optimize.txt, so not repeated here: @JavascriptInterface methods
# (the shared terminal's bridge, ui/term/TerminalScreen.kt), enums' values()/valueOf() (ConnLog and
# the widgets read stored names back), Parcelable CREATORs. Lifecycle keeps the ViewModels'
# constructors; kotlinx.coroutines its ServiceLoader'd Main dispatcher. Nothing uses Kotlin
# reflection, Gson-style reflection or getIdentifier(), so no Kotlin metadata or resources are kept.
