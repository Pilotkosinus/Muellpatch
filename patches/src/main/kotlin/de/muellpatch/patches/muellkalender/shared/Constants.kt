package de.muellpatch.patches.muellkalender.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_MUELLKALENDER = Compatibility(
        name = "Müllkalender",
        packageName = "de.sector27.muellkalender",
        apkFileType = ApkFileType.APK,
        // Primary color of the app (--ion-color-primary).
        appIconColor = 0x4D8E00,
        targets = listOf(
            // Version the patch was developed and tested with.
            AppTarget(version = "2.0.81"),
            // The patch locates its targets by code patterns instead of fixed offsets,
            // so newer versions are likely to work as well.
            AppTarget(version = null, isExperimental = true),
        ),
    )
}
