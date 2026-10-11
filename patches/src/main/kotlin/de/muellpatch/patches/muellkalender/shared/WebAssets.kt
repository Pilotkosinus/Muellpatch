package de.muellpatch.patches.muellkalender.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.rawResourcePatch
import java.io.File

/*
 * Müllkalender is a Capacitor/Ionic app: the whole UI is the Angular bundle under assets/public.
 * Helpers shared by the patches that change these web assets.
 */

internal const val WEB_ROOT = "assets/public"

private const val SETTINGS_SCRIPT = "morphe-settings.js"

/**
 * Shared "Extra Einstellungen (patched)" entry and page in the app settings.
 * The patches add their own sections to it (see morphe-settings.js).
 */
internal val settingsPagePatch = rawResourcePatch {
    execute {
        copyScript(SETTINGS_SCRIPT)
        injectScript(SETTINGS_SCRIPT)
    }
}

/** Copies the bundled resource /muellkalender/[name] to assets/public/[name]. */
internal fun ResourcePatchContext.copyScript(name: String) {
    val resource = "/muellkalender/$name"
    val script = object {}.javaClass.getResourceAsStream(resource)
        ?.use { it.readBytes() }
        ?: throw PatchException("Missing bundled resource $resource")

    get("$WEB_ROOT/$name").apply {
        parentFile.mkdirs()
        writeBytes(script)
    }
}

/**
 * Loads assets/public/[name] in index.html as a classic script before the app scripts,
 * so it runs before the deferred Angular module scripts.
 */
internal fun ResourcePatchContext.injectScript(name: String) {
    val indexFile = get("$WEB_ROOT/index.html")
    val html = indexFile.readText()
    if (html.contains("\"$name\"")) return

    val tag = """<script src="$name"></script>"""
    val bodyStart = html.indexOf("<body")
    val firstScript = html.indexOf("<script", maxOf(bodyStart, 0))
    val patched = when {
        bodyStart >= 0 && firstScript >= 0 -> html.substring(0, firstScript) + tag + html.substring(firstScript)
        html.contains("</body>") -> html.replace("</body>", "$tag</body>")
        else -> throw PatchException("Could not find a place to inject $name into index.html")
    }
    indexFile.writeText(patched)
}

/** The main bundle (main.<hash>.js) referenced by index.html. */
internal fun ResourcePatchContext.mainBundle(): File {
    val html = get("$WEB_ROOT/index.html").readText()
    val name = Regex("""<script[^>]*\ssrc="(main[^"]*\.js)"""").find(html)?.groupValues?.get(1)
        ?: throw PatchException("Could not find the main bundle script in index.html")
    return get("$WEB_ROOT/$name")
}

/** The lazy loaded bundle chunk of assets/public containing [marker]. */
internal fun ResourcePatchContext.bundleContaining(marker: String): File {
    val files = get(WEB_ROOT).listFiles { file -> file.isFile && file.name.endsWith(".js") }.orEmpty()
        .filter { it.readText().contains(marker) }
    return files.singleOrNull()
        ?: throw PatchException("Expected exactly one script in $WEB_ROOT containing $marker, found ${files.size}")
}

/**
 * Returns the index of the brace closing the one at [openIndex].
 * String and template literals are skipped, template substitutions are followed.
 */
internal fun findMatchingBrace(js: String, openIndex: Int): Int {
    // Each entry is either '{' (code block / template substitution) or '`' (template literal).
    val stack = ArrayDeque<Char>()
    var i = openIndex

    while (i < js.length) {
        val c = js[i]
        if (stack.lastOrNull() == '`') {
            when {
                c == '\\' -> i++
                c == '`' -> stack.removeLast()
                c == '$' && js.getOrNull(i + 1) == '{' -> {
                    stack.addLast('{')
                    i++
                }
            }
        } else when (c) {
            '"', '\'' -> {
                i++
                while (i < js.length && js[i] != c) {
                    if (js[i] == '\\') i++
                    i++
                }
            }
            '`' -> stack.addLast('`')
            '{' -> stack.addLast('{')
            '}' -> {
                stack.removeLast()
                if (stack.isEmpty()) return i
            }
        }
        i++
    }

    throw PatchException("Unbalanced braces after index $openIndex")
}
