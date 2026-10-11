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

## 📄 Lizenz

GPLv3, siehe [LICENSE](LICENSE).
