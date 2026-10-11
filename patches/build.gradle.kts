group = "de.muellpatch"

patches {
    about {
        name = "Müllkalender Patches"
        description = "Patches for the Müllkalender app by sector27"
        source = "https://github.com/Pilotkosinus/Muellpatch"
        author = "Pilotkosinus"
        contact = "https://github.com/Pilotkosinus/Muellpatch/issues"
        website = "https://github.com/Pilotkosinus/Muellpatch"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
