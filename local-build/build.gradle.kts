import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.20"
}

group = "de.muellpatch"

// Morphe desktop/CLI jar providing morphe-patcher (compile only, provided at runtime by Morphe).
val morpheDesktopVersion = "1.18.1"
val morpheJar: String = providers.gradleProperty("morpheJar")
    .getOrElse(rootDir.resolve("../tools/morphe-desktop-$morpheDesktopVersion-all.jar").path)

// Download the jar on the first build if it is missing (tools/ is git-ignored).
file(morpheJar).let { jar ->
    if (!jar.exists()) {
        val url = "https://github.com/MorpheApp/morphe-desktop/releases/download/" +
            "v$morpheDesktopVersion/morphe-desktop-$morpheDesktopVersion-all.jar"
        logger.lifecycle("Downloading $url")
        jar.parentFile.mkdirs()
        val partial = File(jar.path + ".part")
        URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { input.copyTo(it) }
        }
        partial.renameTo(jar)
    }
}

val patchesSources = rootDir.resolve("../patches/src/main")
val extensionSources = rootDir.resolve("../extensions/extension/src/main/java")
// The extension dex is bundled into the patches as resource extensions/extension.mpe.
val extensionResources = layout.buildDirectory.dir("extension/resources")

sourceSets.main {
    kotlin.setSrcDirs(listOf(patchesSources.resolve("kotlin")))
    // util/PatchListGenerator.kt needs gson and is only used by the release workflow.
    kotlin.exclude("util/**")
    resources.setSrcDirs(listOf(patchesSources.resolve("resources"), extensionResources))
}

dependencies {
    compileOnly(files(morpheJar))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

java {
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.jar {
    archiveBaseName.set("patches")
    archiveExtension.set("mpp")
    manifest.attributes(
        "Name" to "Müllkalender Patches",
        "Description" to "Patches for the Müllkalender app by sector27",
        "Version" to project.version.toString(),
        "Timestamp" to System.currentTimeMillis().toString(),
        "Source" to "https://github.com/Pilotkosinus/Muellpatch",
        "Author" to "Pilotkosinus",
        "Contact" to "https://github.com/Pilotkosinus/Muellpatch/issues",
        "Website" to "https://github.com/Pilotkosinus/Muellpatch",
        "License" to "GPLv3",
    )
}

fun androidSdkDir(): File? {
    val local = rootDir.resolve("../local.properties")
    if (local.exists()) {
        Properties().apply { local.inputStream().use(::load) }.getProperty("sdk.dir")?.let { return File(it) }
    }
    (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let { return File(it) }
    return listOf(
        File(System.getProperty("user.home"), "AppData/Local/Android/Sdk"),
        File(System.getProperty("user.home"), "Library/Android/sdk"),
        File(System.getProperty("user.home"), "Android/Sdk"),
    ).firstOrNull { it.exists() }
}

val androidSdk by lazy { androidSdkDir() ?: throw GradleException("Android SDK not found (set ANDROID_HOME)") }
val d8Jar by lazy {
    androidSdk.resolve("build-tools").listFiles()!!.filter { it.isDirectory }.maxBy { it.name }.resolve("lib/d8.jar")
}
val androidJar by lazy {
    androidSdk.resolve("platforms").listFiles()!!
        .filter { it.resolve("android.jar").exists() }.maxBy { it.name }.resolve("android.jar")
}

// region Extension (native code merged into the app, see extensions/extension)

val compileExtension by tasks.registering(JavaCompile::class) {
    source(extensionSources)
    classpath = files({ androidJar })
    destinationDirectory.set(layout.buildDirectory.dir("extension/classes"))
    options.release.set(11)
    options.encoding = "UTF-8"
}

val dexExtension by tasks.registering {
    description = "Compiles the extension classes to extensions/extension.mpe (a dex file)."
    dependsOn(compileExtension)
    val classesDir = compileExtension.flatMap { it.destinationDirectory }
    inputs.dir(classesDir)
    outputs.dir(extensionResources)

    doLast {
        val classes = classesDir.get().asFile.walk().filter { it.isFile && it.name.endsWith(".class") }.toList()
        val dexDir = layout.buildDirectory.dir("extension/dex").get().asFile.apply { deleteRecursively(); mkdirs() }
        providers.javaexec {
            classpath(d8Jar)
            mainClass.set("com.android.tools.r8.D8")
            args(listOf("--release", "--min-api", "24", "--lib", androidJar.path, "--output", dexDir.path) +
                classes.map { it.path })
        }.result.get().assertNormalExitValue()

        val mpe = extensionResources.get().asFile.resolve("extensions/extension.mpe")
        mpe.parentFile.mkdirs()
        dexDir.resolve("classes.dex").copyTo(mpe, overwrite = true)
    }
}

tasks.processResources {
    dependsOn(dexExtension)
}

// endregion

// Same as the Morphe Gradle plugin: compile the jar to DEX (for Morphe Manager on Android)
// and merge classes.dex into the .mpp file.
val buildAndroid by tasks.registering {
    group = "build"
    description = "Builds the .mpp patches file including classes.dex."
    dependsOn(tasks.jar)

    doLast {
        val mpp = tasks.jar.get().archiveFile.get().asFile
        val work = layout.buildDirectory.dir("morphe").get().asFile.apply { deleteRecursively(); mkdirs() }
        // D8 only accepts .jar/.zip inputs.
        val input = work.resolve("patches.jar").also { mpp.copyTo(it) }
        val dexDir = work.resolve("dex").apply { mkdirs() }

        providers.javaexec {
            classpath(d8Jar)
            mainClass.set("com.android.tools.r8.D8")
            args(
                "--release", "--min-api", "26",
                "--lib", androidJar.path, "--classpath", morpheJar,
                "--output", dexDir.path, input.path,
            )
        }.result.get().assertNormalExitValue()

        // Add classes.dex to the .mpp (zip) file.
        ant.withGroovyBuilder {
            "zip"("destfile" to mpp.path, "update" to true) {
                "fileset"("dir" to dexDir.path, "includes" to "classes*.dex")
            }
        }
        logger.lifecycle("Built ${mpp.path}")
    }
}
