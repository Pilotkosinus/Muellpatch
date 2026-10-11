package de.muellpatch.patches.muellkalender.announcements

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import de.muellpatch.patches.muellkalender.shared.Constants.COMPATIBILITY_MUELLKALENDER
import de.muellpatch.patches.muellkalender.shared.bundleContaining
import de.muellpatch.patches.muellkalender.shared.copyScript
import de.muellpatch.patches.muellkalender.shared.injectScript
import de.muellpatch.patches.muellkalender.shared.settingsPagePatch

/*
 * Push notifications for the app's announcements ("Sondermeldungen", the red "!" on the home page).
 *
 * The WebView does not run while the app is closed, so the background check is native code
 * (extensions/extension: NewsNotifications.java), merged into the app as an extension:
 *
 * 1. MessageHandler.<init> (Capacitor)      -> NewsNotifications.attach(webView): adds the JS interface
 *                                              "morpheNewsBridge" (before the page is loaded) and
 *                                              (re)schedules the 30 minute alarm.
 * 2. TimedNotificationPublisher.onReceive   -> NewsNotifications.onAlarm(...): the alarm is sent to this
 *    (Capacitor local notifications)           existing, not exported receiver, so no manifest change
 *                                              (and no resource decoding) is needed.
 * 3. LocalNotificationRestoreReceiver       -> NewsNotifications.onBoot(context): reschedules after reboots.
 *    .onReceive (BOOT_COMPLETED)
 * 4. NewsService.requestNews(streets) (JS)  -> passes the street ids to the native side.
 * 5. morphe-announcement-notifications.js   -> "Sondermeldungen" section on the settings page.
 */

private const val EXTENSION_CLASS = "Lde/muellpatch/extension/muellkalender/NewsNotifications;"
private const val HELPER_SCRIPT = "morphe-announcement-notifications.js"
private const val HELPER_GLOBAL = "window.morpheNews"

// NewsService.requestNews(streets) { return this.http.post(`${server}api/v1/news`, {streets: ...}) ... }
private val requestNewsRegex = Regex("""requestNews\(([\w$]+)\)\{""")

private val announcementWebAssetsPatch = rawResourcePatch {
    execute {
        val chunk = bundleContaining("api/v1/news")
        var js = chunk.readText()

        if (!js.contains(HELPER_GLOBAL)) {
            val requestNews = requestNewsRegex.findAll(js).toList().singleOrNull()
                ?: throw PatchException("Could not find NewsService.requestNews in ${chunk.name}")
            val streets = requestNews.groupValues[1]
            js = js.replaceRange(
                requestNews.range,
                "${requestNews.value}$HELPER_GLOBAL&&$HELPER_GLOBAL.setStreets($streets);",
            )
            chunk.writeText(js)
        }

        copyScript(HELPER_SCRIPT)
        injectScript(HELPER_SCRIPT)
    }
}

@Suppress("unused")
val announcementNotificationsPatch = bytecodePatch(
    name = "Announcement notifications",
    description = "Adds a switch to the \"Extra Einstellungen (patched)\" page to get a push notification " +
        "once for every new announcement of the waste collection (cancellations, delays). " +
        "The app checks every 30 minutes in the background.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MUELLKALENDER)

    dependsOn(settingsPagePatch, announcementWebAssetsPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // 1. JS interface + alarm when the app starts. p2 is the WebView, `this` is not initialized yet.
        method(
            "Lcom/getcapacitor/MessageHandler;", "<init>",
            "Lcom/getcapacitor/Bridge;", "Landroid/webkit/WebView;", "Lorg/apache/cordova/PluginManager;",
        ).addInstructions(0, "invoke-static { p2 }, $EXTENSION_CLASS->attach(Landroid/webkit/WebView;)V")

        // 2. Our alarm, handled before the original receiver logic.
        method(
            "Lcom/capacitorjs/plugins/localnotifications/TimedNotificationPublisher;", "onReceive",
            "Landroid/content/Context;", "Landroid/content/Intent;",
        ).addInstructions(
            0,
            """
                invoke-static { p0, p1, p2 }, $EXTENSION_CLASS->onAlarm(Landroid/content/BroadcastReceiver;Landroid/content/Context;Landroid/content/Intent;)Z
                move-result v0
                if-eqz v0, :original
                return-void
                :original
                nop
            """,
        )

        // 3. Reschedule after reboots.
        method(
            "Lcom/capacitorjs/plugins/localnotifications/LocalNotificationRestoreReceiver;", "onReceive",
            "Landroid/content/Context;", "Landroid/content/Intent;",
        ).addInstructions(0, "invoke-static { p1 }, $EXTENSION_CLASS->onBoot(Landroid/content/Context;)V")
    }
}

private fun BytecodePatchContext.method(definingClass: String, name: String, vararg parameters: String): MutableMethod =
    mutableClassDefByOrNull(definingClass)?.methods?.singleOrNull {
        it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters.toList()
    } ?: throw PatchException("Could not find $definingClass->$name(${parameters.joinToString("")})")
