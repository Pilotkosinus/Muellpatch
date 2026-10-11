package de.muellpatch.patches.muellkalender.calendar

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import de.muellpatch.patches.muellkalender.shared.Constants.COMPATIBILITY_MUELLKALENDER
import de.muellpatch.patches.muellkalender.shared.copyScript
import de.muellpatch.patches.muellkalender.shared.findMatchingBrace
import de.muellpatch.patches.muellkalender.shared.injectScript
import de.muellpatch.patches.muellkalender.shared.mainBundle
import de.muellpatch.patches.muellkalender.shared.settingsPagePatch
import java.io.File

/*
 * Müllkalender is a Capacitor/Ionic app: the whole UI and the calendar export are
 * implemented in the Angular bundle under assets/public, the Java side only hosts the
 * WebView and the cordova-plugin-calendar bridge. Therefore this patch only touches web assets:
 *
 * 1. index.html          -> loads the patch helper script (title logic, settings section).
 * 2. main.<hash>.js      -> ReminderDatesFactory.buildByOption(street, option):
 *                           attaches the PickupDay (date + tours) to each created reminder.
 * 3. main.<hash>.js      -> CalendarService.add(calendar, street, reminder):
 *                           the title passed to window.plugins.calendar.createEventWithOptions
 *                           is computed by the helper (falls back to reminder.title).
 * 4. main.<hash>.js      -> CalendarService.addByStreet(calendar, street, option):
 *                           before exporting, the helper deletes the events the app (unpatched
 *                           or patched) already exported for the street to that calendar,
 *                           so re-exporting never creates duplicates.
 *
 * The reminder title itself is left untouched, so local notifications built by the same
 * factory are not affected. With the switch turned off the helper returns reminder.title
 * unchanged, which is exactly the original behavior.
 */

private const val HELPER_SCRIPT = "morphe-calendar-waste-type.js"
private const val HELPER_GLOBAL = "window.morpheWasteType"

// ReminderDatesFactory.buildByOption(street, option) { return street.nextPickupDaysUntil(option.endTime).map(pickupDay => { ... }) }
private val buildByOptionRegex = Regex(
    """buildByOption\(([\w$]+),[\w$]+\)\{return\s*\1\.nextPickupDaysUntil\([^)]*\)\.map\(\(?([\w$]+)\)?=>\{"""
)

// CalendarService.add(calendar, street, reminder) { ... createEventWithOptions(reminder.title, ...) }
private val createEventTitleRegex = Regex("""createEventWithOptions\(([\w$]+)\.title,""")

// CalendarService.addByStreet(calendar, street, option) { return of(this.reminderDatesFactory.buildByOption(street, option)).pipe(...) }
private val addByStreetRegex = Regex("""addByStreet\(([\w$]+),([\w$]+),([\w$]+)\)\{""")

@Suppress("unused")
val calendarWasteTypePatch = rawResourcePatch(
    name = "Calendar waste type",
    description = "Adds the waste type(s) and bin color(s) of the pickup day to the title of exported calendar events " +
        "(e.g. \"Müllabholung – Wertstoffe 🟡 (Street)\") and adds an \"Extra Einstellungen (patched)\" " +
        "page with a switch to turn this off. Events already exported for the street are replaced " +
        "instead of duplicated.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MUELLKALENDER)

    dependsOn(settingsPagePatch)

    execute {
        patchMainBundle(mainBundle())
        copyScript(HELPER_SCRIPT)
        injectScript(HELPER_SCRIPT)
    }
}

private fun patchMainBundle(file: File) {
    val path = file.name
    var js = file.readText()

    if (js.contains(HELPER_GLOBAL)) return // Already patched.

    // region Attach the PickupDay to each reminder.

    val buildByOption = buildByOptionRegex.findAll(js).toList().singleOrNull()
        ?: throw PatchException("Could not find ReminderDatesFactory.buildByOption in $path")

    val streetVariable = buildByOption.groupValues[1]
    val pickupDayVariable = buildByOption.groupValues[2]
    val bodyStart = buildByOption.range.last // The opening brace of the map callback.
    val bodyEnd = findMatchingBrace(js, bodyStart)
    val body = js.substring(bodyStart + 1, bodyEnd)

    if (!body.contains("\"LABELS.PICKUP\"")) {
        throw PatchException("ReminderDatesFactory.buildByOption does not build the pickup title anymore")
    }

    val taggedBody = "{const r=(()=>{$body})();" +
        "return $HELPER_GLOBAL?$HELPER_GLOBAL.tag(r,$pickupDayVariable,$streetVariable):r}"
    js = js.substring(0, bodyStart) + taggedBody + js.substring(bodyEnd + 1)

    // endregion

    // region Use the helper to build the calendar event title.

    val createEvent = createEventTitleRegex.findAll(js).toList().singleOrNull()
        ?: throw PatchException("Could not find the createEventWithOptions call of CalendarService.add in $path")

    val reminderVariable = createEvent.groupValues[1]
    js = js.replaceRange(
        createEvent.range,
        "createEventWithOptions($HELPER_GLOBAL?$HELPER_GLOBAL.calendarTitle($reminderVariable)" +
            ":$reminderVariable.title,",
    )

    // endregion

    // region Replace already exported events instead of duplicating them.

    val addByStreet = addByStreetRegex.findAll(js).toList().singleOrNull()
        ?: throw PatchException("Could not find CalendarService.addByStreet in $path")

    val (calendarVariable, addStreetVariable, optionVariable) = addByStreet.destructured
    val addBodyStart = addByStreet.range.last
    val addBodyEnd = findMatchingBrace(js, addBodyStart)
    val addBody = js.substring(addBodyStart + 1, addBodyEnd)

    if (!addBody.contains("buildByOption(")) {
        throw PatchException("CalendarService.addByStreet does not use buildByOption anymore")
    }

    // Arrow function, so `this` still is the CalendarService.
    val replacingBody = "{const morpheAddEvents=()=>{$addBody};" +
        "return $HELPER_GLOBAL?$HELPER_GLOBAL.replaceEvents(" +
        "$calendarVariable,$addStreetVariable,$optionVariable,morpheAddEvents):morpheAddEvents()}"
    js = js.substring(0, addBodyStart) + replacingBody + js.substring(addBodyEnd + 1)

    // endregion

    file.writeText(js)
}
