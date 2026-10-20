# Bonsai Offline — Android-App

Eigene native Android-App für das Samsung Galaxy A55 (ARM64), mit einer neu gestalteten nativen Oberfläche und lokalem Kernel-Autotuner. Keine OpenAI-Anbindung. Die App fordert **keine Internetberechtigung** an.

[CPU/Vulkan-Releases herunterladen](https://github.com/interfurz/bonsai-offline-a55/releases). Der integrierte Quellstand 0.4.4 verbindet die Funktionszweige mit dem neuen UI. Er verwendet denselben neuen Signierschlüssel wie 0.4.3 und ist nicht direkt über 0.4.0/0.4.1 installierbar. Die alte App nicht vorschnell deinstallieren: dabei gehen lokale Daten verloren. Build-/Geräteverifikation siehe jeweilige Release-Notizen.

## UI-Überarbeitung im aktuellen Quellcode

Die Oberfläche verwendet Burgunderrot und Pflaume, folgt dem System-Theme und bietet drei feste Tabs: **Chat**, **Modell**, **Leistung**. Modellimport und Einstellungen stehen unter **Modell**, Benchmark und Optimierung unter **Leistung**. Der Chatverlauf ist über das Menü links oben erreichbar. [Design-System, Abläufe und Prüfung](docs/UI-REDESIGN.md).

Kurze native Übergänge berücksichtigen abgeschaltete Systemanimationen. Es gibt keine dekorative Dauerschleife. „Neu generieren“ speichert eine Antwortvariante als eigenen Chat und erhält den ursprünglichen Verlauf.

## Stand

Der integrierte Quellstand **0.4.4** kombiniert Burgunder-/Pflaume-UI, Hintergrundgenerierung, dateigebundene GGUF-Freigabe, Chat-Archiv und Langdruckaktionen, striktes UTF-8 sowie zusätzliche skalare PQ-/Hadamard-Prüfungen. Vorherige Releasezweige bleiben als Historie erhalten. Die Appversion allein belegt keine erfolgreiche Geräte-/Modellprüfung.

Version **0.4.1** ergänzt eine dateigebundene Option **Trotzdem fortfahren** bei abweichender Prüfsumme und einen allgemein formulierten GGUF-Import. Andere von der Runtime unterstützte GGUF-Modelle benötigen keine Bonsai-Prüfsumme. Details: [UPDATE-0.4.1.md](UPDATE-0.4.1.md).

Version **0.4.0** ergänzt Hintergrundverarbeitung mit Android-Vordergrunddienst, Abbrechen-Benachrichtigung und CPU-Wachhaltung bei ausgeschaltetem Bildschirm. Bekannte Bonsai-2-4B-Dateien werden beim Import und Laden per SHA256 verglichen. Bei einer Abweichung kann die genaue Datei ausdrücklich freigegeben werden. Optimierte CPU-/Vulkan-Profile werden gegen skalare Quantisierungskernel geprüft; ein CPU-Kompatibilitätsmodus mit Microbatch 1 steht als Rückfall zur Verfügung. Details: [UPDATE-0.4.0.md](UPDATE-0.4.0.md).

Die App bleibt vollständig offline. Das A55-Protokoll zeigt für die Q2_0-Kopie mit unverständlichen Antworten eine andere SHA256 als die getestete offizielle Datei, obwohl die Dateigröße stimmt. Die geprüfte Datei antwortet im Host-Test korrekt. Unter **Modell und Einstellungen → Beispielmodell: Bonsai 2 4B herunterladen** lässt sie sich über den Browser beziehen. Die App ist nicht auf dieses Modell beschränkt. Danach neu importieren und einen neuen Chat starten. Ein physischer A55-Nachtest bleibt erforderlich. Über **Modelltest ohne Chatverlauf** lassen sich Antworten, Modellmetadaten und SHA256 lokal prüfen und kopieren. Zur NPU auf dem A55: [NPU-STATUS.md](NPU-STATUS.md).

Implementiert:

- Chatansicht mit Antwortstreaming, Abbrechen, seitlicher Chatliste und lokalem SQLite-Verlauf.
- Einen Chat im Seitenmenü lange drücken: **Löschen**, **Archivieren**, **Abbrechen**. Tippen außerhalb schließt das Menü ebenfalls. Archivierte Verläufe bleiben unter **Archivierte Chats** erreichbar und lassen sich dort per langem Drücken wiederherstellen. Löschen erfordert eine Bestätigung.
- Import einer lokalen GGUF-Datei über den Android-Dateiauswahldialog. Die App erstellt eine private Kopie; ein abgebrochener Import ersetzt das alte Modell nicht.
- JNI-Anbindung an den originalen PrismML-Fork mit PQ2_0/PTQ1_0 und Bonsai-2-Transformationen.
- CPU als Ausgangspunkt, 4 Threads, 4.096 Tokens Kontext, maximal 512 Antworttokens. Thinking deaktiviert.
- Modell laden/entladen, Kontext-/Thread-Einstellungen und Anzeige des Prozess-RAM.
- Benchmark mit einem Aufwärmdurchlauf, drei Messläufen und JSON-Export.
- Kernel-Optimierung ohne app-eigene Temperatursperre: Start und Fortsetzung bei jedem Android-Thermalstatus. Androids Geräteschutz bleibt aktiv; Temperaturstatus vor/nach der Messung und pro Profil stehen im JSON-Bericht. Ergebnisse sind temperaturabhängig, Rechen- und Stabilitätsprüfungen bleiben aktiv.

Die Wissensdatenbanken, RAG, Tool Calling, Python, SymPy, STT und TTS sind noch nicht Teil dieser Version. Der Chatverlauf ist kein semantisches Langzeitgedächtnis. Generierung läuft beim App-Wechsel und bei ausgeschaltetem Bildschirm weiter. Ein Android-Zwangsstopp oder Prozessabbruch beendet die Berechnung; sie wird nicht automatisch erneut gestartet. Die App benötigt Berechtigungen für Vordergrunddienst und CPU-Wachhaltung sowie optional sichtbare Benachrichtigungen, weiterhin keine Internetberechtigung.

## Quellcode aus GitHub

```bash
git clone --recurse-submodules https://github.com/interfurz/bonsai-offline-a55.git
```

Die PrismML-Runtime ist als Submodul auf den exakten Release-Commit gebunden. Alternativ lädt `python3 scripts/fetch_runtime.py` den fehlenden Quellcode nach. Die APK gehört zu den Release-Dateien; Modellgewichte, SDK/NDK, Build-Caches und Signierschlüssel werden nicht im Repository gespeichert.

## APK auf dem XMG-Laptop bauen

Du brauchst Android Studio mit JDK 17 oder neuer, Git und Python 3. Die Downloads erfolgen beim Bauen auf dem Laptop. Der Betrieb der installierten App ist davon unabhängig und offline.

1. Dieses Projekt entpacken. Im Projektordner ein Terminal öffnen.
2. Den passenden Runtime-Quellcode herunterladen:

   ```bash
   python3 scripts/fetch_runtime.py
   ```

   Unter Windows gegebenenfalls `py scripts/fetch_runtime.py` verwenden. Das Skript lädt den PrismML-Tag `prism-b10777-e3de715` und prüft den vollständigen Commit `e3de715222b2667c54a267441159a5729f11155e`.

3. Den Projektordner in Android Studio öffnen. Im SDK Manager installieren:

   - Android SDK Platform 35
   - Android SDK Build-Tools 35.0.0
   - NDK (Side by side) **28.2.13676358**
   - CMake **3.22.1**

4. Gradle synchronisieren. Android Studio soll das SDK über seine lokale `local.properties` finden. Alternativ `ANDROID_HOME` korrekt auf den lokalen SDK-Ordner setzen.
5. APK bauen:

   ```bash
   ./gradlew :app:assembleDebug :app:lintDebug
   ```

   Unter Windows:

   ```powershell
   .\gradlew.bat :app:assembleDebug :app:lintDebug
   ```

6. Bei erfolgreichem Build liegt die installierbare, mit einem lokalen Debug-Schlüssel signierte Test-APK hier:

   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

7. Auf das A55 kopieren und installieren oder per USB aus Android Studio starten. Die Modellgewichte werden separat importiert und sind nicht in der APK enthalten.

GitHub Actions bietet **Android APK** für CPU-Debug-Builds und **Android CPU + Vulkan unsigned release** für vollständige CPU/Vulkan-Release-Builds. Beide lassen sich unter **Actions → Run workflow** starten. Der Release-Workflow installiert die festgelegte Vulkan-Toolchain und stellt ein unsigniertes APK für die lokale Signierung bereit. Ein Update einer vorhandenen Installation benötigt deren bisherigen Signierschlüssel; ein neuer Debug-Schlüssel ist dafür nicht geeignet. Nur der Build benötigt Internet; die App läuft weiterhin lokal.

## GGUF-Modell auswählen

Andere GGUF-Textmodelle können über die Dateiauswahl importiert werden. Architektur und Quantisierung müssen von der eingebauten Runtime unterstützt werden; der RAM muss für das Modell und seinen Kontext ausreichen. Es gibt keine Bonsai-Namenspflicht oder Liste ausschließlich erlaubter Modelle.

Als bereits geprüftes Beispiel kann aus dem [geprüften Stand des offiziellen Bonsai-2-4B-Repositorys](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/tree/8d6557168168f91ea6697a4a49ed2ef622b53adb) zunächst nur diese Datei herunterladen:

```text
Ternary-Bonsai-2-4B-PQ2_0.gguf
```

Die Model Card nennt etwa 1,15 GB. **Nicht** mit dem älteren `Ternary-Bonsai-4B` ohne `-2-` verwechseln. Vision-Projektor, MTP- und DFlash-Dateien werden für diesen ersten Textchat nicht benötigt.

Im aktuellen Quellcode **Modell importieren** auf dem Startscreen oder im Tab **Modell** benutzen. Während des Imports werden Modell- und App-Speicher zeitweise beide benötigt. Die Originaldatei kann nach erfolgreichem Import außerhalb der App gelöscht werden. Eine Deinstallation löscht die private Modellkopie und die Chats.

Falls der Ladevorgang scheitert: andere Apps schließen und **Modell → Kontextlänge → 2.048 Tokens → Einstellungen speichern**, danach erneut laden. Die Größe der GGUF-Datei ist nicht der gesamte RAM-Bedarf. Android kann die App bei Speichermangel ohne auffangbare Fehlermeldung beenden.

## Benchmark

Über **Leistung → Benchmark öffnen** starten. Die Messung verwendet keinen Chatverlauf und speichert die Testausgabe nicht als Chat.

- Ein nicht gewerteter Aufwärmdurchlauf mit bis zu 32 Tokens.
- Drei Messläufe mit identischem deutschem Prompt, Seed 42, deaktiviertem Thinking und jeweils maximal 128 Ausgabetokens.
- Vor jedem Durchlauf wird der Kontext vollständig geleert. Prompt-Verarbeitung findet also jedes Mal statt; dies misst keine Folgeturns mit wiederverwendetem KV-Cache.
- Gemessen werden Prompt-Verarbeitung, Zeit bis zum ersten Token, Ausgabetokens und Tokens/s. Vorzeitiges EOS ist erlaubt; tatsächliche Tokenzahlen stehen pro Lauf im Export.
- Die Übersicht zeigt die Mediane der vollständigen Messläufe. Abgebrochene oder fehlgeschlagene Messungen sind ausdrücklich unvollständig.
- `VmRSS` aus `/proc/self/status` wird alle 250 ms abgetastet. Das ist Prozess-RAM, kein exakter Modell-RAM und kein vollständiger GPU-Speicherwert. Kurze Spitzen können fehlen. Der Ausgangswert liegt nach dem Laden des Modells.
- Der Export enthält Modellname/-größe, SHA256, Modellmetadaten, Gerätemodell, Android-Version, Backend, Runtime-Commit, Kontext, Threads, Prompt, Einzelmessungen einschließlich erzeugter Antwort und Akkutemperatur-/Thermalstatus vor und nach der Messung. Die Prüfsumme wird vor dem Aufwärmen außerhalb der Zeitmessung berechnet. Ein abgeschlossener Geschwindigkeitsbenchmark beweist keine korrekten Antworten.
- Die letzte Messung bleibt lokal als `benchmark.json` gespeichert. Über **JSON exportieren** kann sie in eine eigene Datei geschrieben werden.

Für vergleichbare Messungen dieselbe Modelldatei und dieselben Einstellungen verwenden, das Gerät abkühlen lassen und den Energiesparmodus ausschalten. Der Benchmark erzeugt erst beim Ausführen auf dem Handy echte Werte. Es sind keine erfundenen Beispielresultate hinterlegt.

## Vulkan

Der Standardbuild enthält nur CPU-Inferenz. Die Codeanbindung für einen experimentellen Vulkan-Build ist vorbereitet:

```bash
./gradlew -PbonsaiVulkan=true :app:assembleDebug
```

**Der bisherige Release 0.3.1 enthält Vulkan.** Zum lokalen Bauen mit Vulkan benötigt dieser Pfad zusätzlich eine zum PrismML-Fork passende Host-Vulkan-Toolchain mit `glslc`, Vulkan-Headers und SPIRV-Headers/CMake-Paket. Gradle installiert diese nicht automatisch. Ohne diese Pakete schlägt die CMake-Konfiguration absichtlich fehl. Der separate CPU/Vulkan-Release-Workflow richtet diese Toolchain ein; der Standardworkflow **Android APK** baut CPU.

In einem erfolgreich gebauten Vulkan-Build erscheint die Backend-Auswahl. Eine erkannte GPU oder unterstützte PQ2_0-Kernel garantieren keine korrekte und stabile Ausführung auf der Xclipse 530. Der CPU-Pfad kann danach mit demselben Benchmark verglichen werden. Ein Vulkan-Abbruch kann erst nach dem laufenden GPU-Aufruf wirksam werden.

## Entwicklung und Prüfungen

```bash
python3 scripts/check_project.py
java scripts/ParseJava.java
```

Der erste Aufruf prüft XML/Manifest, JNI-Namen, Versionsbindung und UTF-8-Streaming über sämtliche Zeichengrenzen von Umlauten, Emoji und anderen mehrbyteigen Zeichen. Dafür muss ein C++17-Compiler installiert sein. Der zweite Aufruf prüft die Java-Syntax mit einem JDK, aber ohne Android-Typauflösung.

Die Prüfungen für 0.4.0 einschließlich Gradle-/NDK-Build, Android-Tests und echten 4B-Modelltests stehen in [VALIDATION-0.4.0.md](VALIDATION-0.4.0.md). Ein physischer A55-Nachtest bleibt erforderlich. `VALIDATION.md` dokumentiert den älteren Projektstand.

## Technische Quellen

- [Bonsai 2 4B: Model Card und Dateiformate](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf)
- [PrismML-Fork, fest verwendeter Release](https://github.com/PrismML-Eng/llama.cpp/releases/tag/prism-b10777-e3de715)
- [Offizielle Backend-/Formatübersicht](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/BACKEND-SUPPORT.md)

Die App ist ein eigenständiges Projekt und wird weder von OpenAI noch von PrismML herausgegeben.
