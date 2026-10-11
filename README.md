# 🗑️ Müllkalender Morphe Patches

Morphe-Patch für die App **Müllkalender** (`de.sector27.muellkalender`, getestet mit Version 2.0.81).

## ➕ Zu Morphe hinzufügen

**[👉 Zu Morphe hinzufügen](https://morphe.software/add-source?github=Pilotkosinus/Muellpatch)**

Link auf dem Handy öffnen (Morphe muss installiert sein) und bestätigen. Danach taucht die Patch-Quelle
in Morphe auf und du kannst die Müllkalender-App patchen.

Manuell geht es auch: in Morphe unter Patch-Quellen diese URL hinzufügen:

```
https://github.com/Pilotkosinus/Muellpatch
```

## 🩹 Patches

### Calendar waste type

Beim Kalender-Export steht im Termin nicht mehr nur, *dass* Müll rausgestellt werden muss, sondern *welche Tonne*,
inklusive Tonnenfarbe:

| | Termintitel |
|---|---|
| Original | `Müllabholung (Musterstraße 1)` |
| Gepatcht | `Müllabholung – Wertstoffe 🟡 (Musterstraße 1)` |
| Gepatcht, mehrere Tonnen | `Müllabholung – Bioabfall 🟤, Restabfall ⚫ (Musterstraße 1)` |

Farben: ⚫ schwarz, 🔵 blau, 🟤 braun, 🟡 gelb (so wie die App die Tonnen selbst einfärbt).

In den Einstellungen der App gibt es unter „Müllkalender bewerten“ den neuen Punkt
**Extra Einstellungen (patched)** mit dem Schalter **Tonnenart im Kalender anzeigen** (standardmäßig an).

**Keine doppelten Termine:** Beim Export löscht der Patch zuerst die Müllkalender-Termine, die für diese Straße
im gewählten Kalender schon vorhanden sind (ab heute bis zum Ende des Export-Zeitraums). Das gilt auch für
Termine, die noch mit der ungepatchten App exportiert wurden. Vergangene Termine, andere Kalender und
eigene Termine bleiben unverändert.

### Announcement notifications

Push-Benachrichtigung bei **Sondermeldungen** (Ausfälle, Verzögerungen), also den Meldungen, die die App sonst nur
als rotes Ausrufezeichen auf der Startseite zeigt. Einschalten unter **Extra Einstellungen (patched)** →
**Push-Benachrichtigung bei Sondermeldungen**.

- Die App prüft alle 30 Minuten im Hintergrund, auch wenn sie geschlossen ist, und benachrichtigt **einmal** pro
  neuer Meldung. Eine Meldung, die beim Einschalten schon da ist, löst keine Benachrichtigung aus.
- Sind Benachrichtigungen nicht erlaubt oder ist die Akku-Optimierung für die App an, zeigt die Einstellungsseite
  einen Hinweis mit Button direkt in die passenden Android-Einstellungen. Für zuverlässige Prüfungen im
  Hintergrund: **Akkunutzung der App** → **Hintergrundnutzung zulassen** → **Uneingeschränkt**.
- **Jetzt prüfen und Testbenachrichtigung senden** zeigt die aktuelle Meldung sofort als Benachrichtigung.
- Wird die App über „Beenden erzwingen“ gestoppt, löscht Android ihre Alarme. Beim nächsten Öffnen der App
  wird wieder geprüft.

## 📥 Woher bekomme ich die APK?

Morphe verweist zum Download auf APKMirror, dort gibt es den Müllkalender aber nicht. Die APK (Version 2.0.81,
als normale „APK“, nicht „XAPK“) gibt es z. B. bei
[APKPure](https://apkpure.com/de/m%C3%BCllkalender/de.sector27.muellkalender), oder du ziehst sie mit einer App
wie „APK Extractor“ aus der installierten Play-Store-Version. In Morphe wählst du die Datei dann aus dem Speicher aus.

Vor dem Installieren der gepatchten App muss die Original-App deinstalliert werden (andere Signatur).

---

## 🧑‍💻 Mitentwickeln

Pull Requests und Issues sind willkommen.

### Wie die Patches funktionieren

Der Müllkalender ist eine Capacitor/Ionic-App (Angular). Oberfläche und Kalenderexport stecken komplett im
JavaScript unter `assets/public` der APK, die Java-Seite hostet nur die WebView und Plugins wie das
[cordova-plugin-calendar](https://github.com/EddyVerbruggen/Calendar-PhoneGap-Plugin).

Die Seite **Extra Einstellungen (patched)** kommt aus `morphe-settings.js` (internes `settingsPagePatch`), jeder
Patch hängt dort seinen eigenen Abschnitt an (`window.morpheSettingsSections`).

**Calendar waste type** ist ein `rawResourcePatch`: kein Smali, kein Bytecode, nur Web-Assets.

| Datei in der APK | Änderung |
|---|---|
| `assets/public/index.html` | lädt `morphe-calendar-waste-type.js` |
| `assets/public/main.<hash>.js` | `ReminderDatesFactory.buildByOption`: hängt den `PickupDay` (Datum + Touren) an jede Erinnerung |
| `assets/public/main.<hash>.js` | `CalendarService.add`: Titel für `createEventWithOptions` kommt vom Helfer-Script (Tonnenart aus `Tour.label`, Farbe aus `Tour.color`) |
| `assets/public/main.<hash>.js` | `CalendarService.addByStreet`: Helfer löscht vor dem Export die vorhandenen Termine der Straße im Ziel-Kalender |
| `assets/public/morphe-calendar-waste-type.js` | neu: Titel-Logik, Ersetzen alter Termine, Abschnitt „Kalenderexport“ |

**Announcement notifications** braucht nativen Code, weil die WebView nicht läuft, wenn die App geschlossen ist.
Der Java-Code liegt in `extensions/extension` und wird als Extension (`extensions/extension.mpe`, eine dex-Datei)
in die App gemischt. Das Manifest wird nicht verändert (deshalb reicht der schnelle Raw-Ressourcen-Modus):

| Stelle in der App | Änderung |
|---|---|
| `com.getcapacitor.MessageHandler.<init>` | `NewsNotifications.attach(webView)`: JS-Schnittstelle `morpheNewsBridge`, Alarm neu planen |
| `TimedNotificationPublisher.onReceive` (Local Notifications Plugin) | unser 30-Minuten-Alarm geht an diesen vorhandenen, nicht exportierten Receiver und wird dort abgefangen (`onAlarm`) |
| `LocalNotificationRestoreReceiver.onReceive` (BOOT_COMPLETED) | Alarm nach Neustart neu planen (`onBoot`) |
| `NewsService.requestNews` (JS-Chunk mit `api/v1/news`) | gibt die Straßen-IDs an den nativen Teil weiter |
| `assets/public/morphe-announcement-notifications.js` | neu: Abschnitt „Sondermeldungen“ mit Hinweisen und Test-Button |

Die Meldungen kommen von `POST https://muellkalender-v2.sector27.de/api/v1/news` mit `{"streets": [ids]}`, genau wie
in der App. Die Antwort enthält die aktuelle Meldung (`title`, `content`, `date`) oder `[]`.

Die Stellen im minifizierten Bundle werden per Regex über Code-Muster gefunden (nicht über feste Offsets oder
Variablennamen), damit der Patch auch neue App-Versionen übersteht. Findet der Patch ein Muster nicht, bricht er
mit einer `PatchException` ab, statt eine kaputte App zu bauen.

### Projektaufbau

```
patches/src/main/kotlin/de/muellpatch/patches/muellkalender/
  calendar/CalendarWasteTypePatch.kt     Patch: Tonnenart im Kalender, keine doppelten Termine
  announcements/AnnouncementNotificationsPatch.kt
                                         Patch: Push bei Sondermeldungen (Bytecode-Hooks + JS)
  shared/Constants.kt                    Paketname und unterstützte App-Versionen
  shared/WebAssets.kt                    Einstellungsseite, Skripte einbinden, Hilfsfunktionen
patches/src/main/resources/muellkalender/
  morphe-settings.js                     Seite „Extra Einstellungen (patched)“
  morphe-calendar-waste-type.js          Helfer-Script für den Kalender-Patch
  morphe-announcement-notifications.js   Helfer-Script für die Sondermeldungen
extensions/extension/src/main/java/      Nativer Code (NewsNotifications.java), wird zu extension.mpe
local-build/                             Build ohne GitHub-Token (siehe unten)
patches-bundle.json                      Wird von Morphe gelesen (Version + Download-Link)
patches-<version>.mpp                    Fertig gebauter Patch, den Morphe herunterlädt
```

### Bauen

Voraussetzungen: JDK 17 oder neuer, Android SDK (für `d8`, um `classes.dex` für Morphe auf dem Handy zu erzeugen;
wird über `ANDROID_HOME` oder `local.properties` mit `sdk.dir=...` gefunden).

```sh
./gradlew -p local-build buildAndroid
# -> local-build/build/libs/patches-<version>.mpp
```

Dieser Build braucht keinen GitHub-Token. Er kompiliert gegen den morphe-patcher, der im
[Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)-JAR steckt. Das JAR wird beim ersten Build
automatisch nach `tools/` heruntergeladen (oder per `-PmorpheJar=<pfad>` angegeben). Die Extension wird gegen das
`android.jar` des SDK kompiliert und mit `d8` zu `extensions/extension.mpe` gemacht.

Alternativ der offizielle Build mit dem Morphe-Gradle-Plugin. Der braucht einen GitHub-Token mit `read:packages`
(siehe [Morphe-Setup](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md)):

```sh
./gradlew buildAndroid
# -> patches/build/libs/patches-<version>.mpp
```

(Der offizielle Build ist mit dem aktuellen Stand nicht getestet, getestet wird mit `local-build`.)

### Testen

Eine APK der App patchen (die APK selbst liegt nicht im Repo):

```sh
java -jar tools/morphe-desktop-1.18.1-all.jar patch \
  -p local-build/build/libs/patches-<version>.mpp \
  -o muellkalender-patched.apk \
  muellkalender-2.0.81.apk
adb install -r muellkalender-patched.apk
```

Mit `--keystore <datei>` wird immer mit demselben Schlüssel signiert, dann lassen sich neue Builds über die alte
Installation installieren, ohne dass die App-Daten verloren gehen.

Zum Prüfen der Kalendereinträge auf dem Gerät/Emulator:

```sh
adb shell "content query --uri content://com.android.calendar/events \
  --projection _id:calendar_id:title:dtstart --where \"description LIKE '%sector27%' AND deleted=0\""
```

Tipp: Zum Testen einen lokalen Kalender ohne Sync verwenden, nicht den eigenen Google-Kalender.

Sondermeldungen im Hintergrund testen: App schließen, Prozess beenden (nicht „Beenden erzwingen“, das löscht den
Alarm) und die Uhr des Emulators vorstellen, dann löst der Alarm aus:

```sh
adb shell am kill de.sector27.muellkalender
adb shell cmd alarm set-time $(( ($(adb shell date +%s) + 31*60) * 1000 ))
adb logcat -s MorpheNews      # "check (notifyNew=true, ...)"
adb shell dumpsys alarm | grep -A3 CHECK_NEWS
```

### Neue Version veröffentlichen

1. Version in `gradle.properties` und `local-build/gradle.properties` hochsetzen.
2. `./gradlew -p local-build buildAndroid`
3. Neue `.mpp` aus `local-build/build/libs/` ins Repo-Hauptverzeichnis kopieren, alte löschen.
4. In `patches-bundle.json` `version`, `created_at` und `download_url` anpassen.
5. Committen und auf `main` pushen. Morphe erkennt das Update an der neuen `version`.

## 📄 Lizenz

GPLv3, siehe [LICENSE](LICENSE) und [NOTICE](NOTICE). Basiert auf dem
[Morphe-Patches-Template](https://github.com/MorpheApp/morphe-patches-template). Dieses Projekt steht in keiner
Verbindung zu Morphe oder sector27.
