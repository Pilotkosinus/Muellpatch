/*
 * Token-free build of the patches in ../patches.
 *
 * The official build (root project, `./gradlew buildAndroid`) uses the Morphe patches Gradle plugin,
 * which is only available from GitHub Packages and therefore needs a GitHub token (gpr.user/gpr.key).
 * This build compiles the same sources against the morphe-patcher classes bundled in the
 * Morphe desktop/CLI jar and produces the same .mpp layout as the plugin
 * (JVM classes + classes.dex + manifest attributes).
 *
 *   ./gradlew -p local-build buildAndroid
 */
rootProject.name = "muellkalender-morphe-patches-local"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
