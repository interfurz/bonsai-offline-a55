# Bonsai Offline — Android-Prototyp

Eigene native Android-App für das Samsung Galaxy A55 (ARM64), mit einer neu gestalteten nativen Oberfläche und lokalem Kernel-Autotuner. Keine OpenAI-Anbindung. Die App fordert **keine Internetberechtigung** an.

[APK und Projektarchive herunterladen](https://github.com/interfurz/bonsai-offline-a55/releases/tag/v0.3.0)

## Stand

Version **0.3.0** enthält CPU/Vulkan-Auswahl, Animationen, Leistungsbereich, Kernel-Autotuner und eine differentielle Rechenprüfung gegen einen konservativen CPU-Pfad. Details: [UPDATE-0.3.0.md](UPDATE-0.3.0.md). Die App bleibt vollständig offline. Das zuvor gemeldete Zeichensalat-Problem auf dem A55 ist auf der x86-Build-CPU mit der offiziellen PQ2_0-Datei bisher nicht reproduziert; eine erfolgreiche Desktop-Prüfung ist kein A55-Nachweis. Über **Modelltest ohne Chatverlauf** lassen sich unabhängige Antworten, Modellmetadaten und SHA256 lokal prüfen und kopieren.

Implementiert:

- Chatansicht mit Antwortstreaming, Abbrechen, seitlicher Chatliste und lokalem SQLite-Verlauf.
- Einen Chat im Seitenmenü lange drücken: **Löschen**, **Archivieren**, **Abbrechen**. Tippen außerhalb schließt das Menü ebenfalls. Archivierte Verläufe bleiben unter **Archivierte Chats** erreichbar und lassen sich dort per langem Drücken wiederherstellen. Löschen erfordert eine Bestätigung.
- Import einer lokalen GGUF-Datei über den Android-Dateiauswahldialog. Die App erstellt eine private Kopie; ein abgebrochener Import ersetzt das alte Modell nicht.
- JNI-Anbindung an den originalen PrismML-Fork mit PQ2_0/PTQ1_0 und Bonsai-2-Transformationen.
- CPU als Ausgangspunkt, 4 Threads, 4.096 Tokens Kontext, maximal 512 Antworttokens. Thinking deaktiviert.
- Modell laden/entladen, Kontext-/Thread-Einstellungen und Anzeige des Prozess-RAM.
- Benchmark mit einem Aufwärmdurchlauf, drei Messläufen und JSON-Export.

Die Wissensdatenbanken, RAG, Tool Calling, Python, SymPy, STT und TTS sind noch nicht Teil dieser Version. Der Chatverlauf ist kein semantisches Langzeitgedächtnis. Generierung stoppt beim Verlassen der App; ein Hintergrunddienst ist noch nicht enthalten.

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

Alternativ enthält `.github/workflows/android.yml` einen vorbereiteten GitHub-Actions-Build. Nach Upload des Projekts in ein eigenes Repository kann der Workflow unter **Actions → Android APK → Run workflow** gestartet werden. Diese Sitzung hat kein Repository veröffentlicht und keinen Workflow ausgeführt. Nur der Build benötigt dabei Internet; die App läuft weiterhin lokal.

## Erstes Modell

Aus dem [offiziellen Bonsai-2-4B-Repository](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/tree/main) zunächst nur diese Datei herunterladen:

```text
Ternary-Bonsai-2-4B-PQ2_0.gguf
```

Die Model Card nennt etwa 1,15 GB. **Nicht** mit dem älteren `Ternary-Bonsai-4B` ohne `-2-` verwechseln. Vision-Projektor, MTP- und DFlash-Dateien werden für diesen ersten Textchat nicht benötigt.

In der App **Bonsai-Modell auswählen** oder **+ → GGUF-Modell importieren** benutzen. Während des Imports werden Modell- und App-Speicher zeitweise beide benötigt. Die Originaldatei kann nach erfolgreichem Import außerhalb der App gelöscht werden. Eine Deinstallation löscht die private Modellkopie und die Chats.

Falls der Ladevorgang scheitert: andere Apps schließen und **Modell und Einstellungen → 2.048 Tokens → Übernehmen**, danach erneut laden. Die Größe der GGUF-Datei ist nicht der gesamte RAM-Bedarf. Android kann die App bei Speichermangel ohne auffangbare Fehlermeldung beenden.

## Benchmark

Über **Seitenmenü → Benchmark** starten. Die Messung verwendet keinen Chatverlauf und speichert die Testausgabe nicht als Chat.

- Ein nicht gewerteter Aufwärmdurchlauf mit bis zu 32 Tokens.
- Drei Messläufe mit identischem deutschem Prompt, Seed 42, deaktiviertem Thinking und jeweils maximal 128 Ausgabetokens.
- Vor jedem Durchlauf wird der Kontext vollständig geleert. Prompt-Verarbeitung findet also jedes Mal statt; dies misst keine Folgeturns mit wiederverwendetem KV-Cache.
- Gemessen werden Prompt-Verarbeitung, Zeit bis zum ersten Token, Ausgabetokens und Tokens/s. Vorzeitiges EOS ist erlaubt; tatsächliche Tokenzahlen stehen pro Lauf im Export.
- Die Übersicht zeigt die Mediane der vollständigen Messläufe. Abgebrochene oder fehlgeschlagene Messungen sind ausdrücklich unvollständig.
- `VmRSS` aus `/proc/self/status` wird alle 250 ms abgetastet. Das ist Prozess-RAM, kein exakter Modell-RAM und kein vollständiger GPU-Speicherwert. Kurze Spitzen können fehlen. Der Ausgangswert liegt nach dem Laden des Modells.
- Der Export enthält Modellname/-größe, Gerätemodell, Android-Version, Backend, Runtime-Commit, Kontext, Threads, Prompt, Einzelmessungen und Akkutemperatur-/Thermalstatus vor und nach der Messung. Dateiname und Bytezahl ersetzen keine Modell-Prüfsumme.
- Die letzte Messung bleibt lokal als `benchmark.json` gespeichert. Über **JSON exportieren** kann sie in eine eigene Datei geschrieben werden.

Für vergleichbare Messungen dieselbe Modelldatei und dieselben Einstellungen verwenden, das Gerät abkühlen lassen und den Energiesparmodus ausschalten. Der Benchmark erzeugt erst beim Ausführen auf dem Handy echte Werte. Es sind keine erfundenen Beispielresultate hinterlegt.

## Vulkan

Der Standardbuild enthält nur CPU-Inferenz. Die Codeanbindung für einen experimentellen Vulkan-Build ist vorbereitet:

```bash
./gradlew -PbonsaiVulkan=true :app:assembleDebug
```

**Der ausgelieferte Build enthält Vulkan.** Zum eigenen Bauen benötigt dieser Pfad zusätzlich eine zum PrismML-Fork passende Host-Vulkan-Toolchain mit `glslc`, Vulkan-Headers und SPIRV-Headers/CMake-Paket. Gradle installiert diese nicht automatisch. Ohne diese Pakete schlägt die CMake-Konfiguration absichtlich fehl. Für CPU-Builds ist diese zusätzliche Toolchain nicht erforderlich.

In einem erfolgreich gebauten Vulkan-Build erscheint die Backend-Auswahl. Eine erkannte GPU oder unterstützte PQ2_0-Kernel garantieren keine korrekte und stabile Ausführung auf der Xclipse 530. Der CPU-Pfad kann danach mit demselben Benchmark verglichen werden. Ein Vulkan-Abbruch kann erst nach dem laufenden GPU-Aufruf wirksam werden.

## Entwicklung und Prüfungen

```bash
python3 scripts/check_project.py
java scripts/ParseJava.java
```

Der erste Aufruf prüft XML/Manifest, JNI-Namen, Versionsbindung und UTF-8-Streaming über sämtliche Zeichengrenzen von Umlauten, Emoji und anderen mehrbyteigen Zeichen. Dafür muss ein C++17-Compiler installiert sein. Der zweite Aufruf prüft die Java-Syntax mit einem JDK, aber ohne Android-Typauflösung.

Die hier durchgeführten Prüfungen stehen in `VALIDATION.md`. Ein echter Gradle-/NDK-Build und ein physischer A55-Test bleiben erforderlich.

## Technische Quellen

- [Bonsai 2 4B: Model Card und Dateiformate](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf)
- [PrismML-Fork, fest verwendeter Release](https://github.com/PrismML-Eng/llama.cpp/releases/tag/prism-b10777-e3de715)
- [Offizielle Backend-/Formatübersicht](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/BACKEND-SUPPORT.md)

Die App ist ein eigenständiges Projekt und wird weder von OpenAI noch von PrismML herausgegeben.
