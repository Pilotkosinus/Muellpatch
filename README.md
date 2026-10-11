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

Beim Kalender-Export steht im Termin nicht mehr nur, *dass* Müll rausgestellt werden muss, sondern *welche Tonne*:

| | Termintitel |
|---|---|
| Original | `Müllabholung (Hervester Str. 70-208)` |
| Gepatcht | `Müllabholung – Bioabfall, Restabfall (Hervester Str. 70-208)` |

In den Einstellungen der App gibt es unter „Müllkalender bewerten“ den neuen Punkt
**Extra Einstellungen (patched)** mit dem Schalter **Tonnenart im Kalender anzeigen** (standardmäßig an).

**Keine doppelten Termine:** Beim Export löscht der Patch zuerst die Müllkalender-Termine, die für diese Straße
im gewählten Kalender schon vorhanden sind (ab heute bis zum Ende des Export-Zeitraums). Das gilt auch für
Termine, die noch mit der ungepatchten App exportiert wurden. Vergangene Termine, andere Kalender und
eigene Termine bleiben unverändert.

## 📥 Woher bekomme ich die APK?

Morphe verweist zum Download auf APKMirror, dort gibt es den Müllkalender aber nicht. Die APK (Version 2.0.81,
als normale „APK“, nicht „XAPK“) gibt es z. B. bei
[APKPure](https://apkpure.com/de/m%C3%BCllkalender/de.sector27.muellkalender), oder du ziehst sie mit einer App
wie „APK Extractor“ aus der installierten Play-Store-Version. In Morphe wählst du die Datei dann aus dem Speicher aus.

Vor dem Installieren der gepatchten App muss die Original-App deinstalliert werden (andere Signatur).

---

## 🧑‍💻 Mitentwickeln

Pull Requests und Issues sind willkommen.

### Wie der Patch funktioniert

Der Müllkalender ist eine Capacitor/Ionic-App (Angular). Oberfläche und Kalenderexport stecken komplett im
JavaScript unter `assets/public` der APK, die Java-Seite hostet nur die WebView und das
[cordova-plugin-calendar](https://github.com/EddyVerbruggen/Calendar-PhoneGap-Plugin). Der Patch ist deshalb ein
`rawResourcePatch`: kein Smali, kein Bytecode, nur Web-Assets.

| Datei in der APK | Änderung |
|---|---|
| `assets/public/index.html` | lädt `morphe-calendar-waste-type.js` |
| `assets/public/main.<hash>.js` | `ReminderDatesFactory.buildByOption`: hängt den `PickupDay` (Datum + Touren) an jede Erinnerung |
| `assets/public/main.<hash>.js` | `CalendarService.add`: Titel für `createEventWithOptions` kommt vom Helfer-Script |
| `assets/public/main.<hash>.js` | `CalendarService.addByStreet`: Helfer löscht vor dem Export die vorhandenen Termine der Straße im Ziel-Kalender |
| `assets/public/morphe-calendar-waste-type.js` | neu: Titel-Logik, Ersetzen alter Termine, Einstellungsseite |

Die Stellen im minifizierten Bundle werden per Regex über Code-Muster gefunden (nicht über feste Offsets oder
Variablennamen), damit der Patch auch neue App-Versionen übersteht. Findet der Patch ein Muster nicht, bricht er
mit einer `PatchException` ab, statt eine kaputte App zu bauen.

### Projektaufbau

```
patches/src/main/kotlin/de/muellpatch/patches/muellkalender/
  calendar/CalendarWasteTypePatch.kt     Patch: sucht die Stellen im Bundle und baut die Hooks ein
  shared/Constants.kt                    Paketname und unterstützte App-Versionen
patches/src/main/resources/muellkalender/
  morphe-calendar-waste-type.js          Helfer-Script, das in die App kopiert wird
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
automatisch nach `tools/` heruntergeladen (oder per `-PmorpheJar=<pfad>` angegeben).

Alternativ der offizielle Build mit dem Morphe-Gradle-Plugin. Der braucht einen GitHub-Token mit `read:packages`
(siehe [Morphe-Setup](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md)):

```sh
./gradlew buildAndroid
# -> patches/build/libs/patches-<version>.mpp
```

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
