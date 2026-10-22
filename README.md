# Bonsai Offline — Android-App

Eigene native Android-App für das Samsung Galaxy A55 (ARM64), mit einer neu gestalteten nativen Oberfläche und lokalem Kernel-Autotuner. Keine OpenAI-Anbindung. Die App fordert **keine Internetberechtigung** an.

[CPU/Vulkan-Releases herunterladen](https://github.com/interfurz/bonsai-offline-a55/releases). Version **0.5.1** ergänzt den anpassbaren Systemprompt mit geschütztem Tool-Bereich und die einzeilige Thinking-/Prefill-Anzeige. Sie enthält außerdem die Funktionen von 0.5: EmbeddingGemma 2, Wikipedia-/Wikidata-Pipelines, Hybrid-RAG, Cache-Diagnosen sowie die neue Chatoberfläche mit nativen Lichtzuständen und Icon-Familie. Der bereitgestellte Release-Keystore entspricht der Signieridentität von 0.4.3/0.4.4; er unterscheidet sich von 0.4.5/0.3.0. Signieridentität und Datenhinweise: [SIGNIEREN.md](SIGNIEREN.md). [Releasehinweise](release/0.5.1/RELEASE-NOTES.md), [technische Roadmap](ROADMAP.md) und [A55-Echtwelttestplan](A55-ECHTWELT-TESTPLAN.md).

## UI-Überarbeitung im aktuellen Quellcode

Die Oberfläche verwendet ruhige neutrale Flächen, einen nahezu schwarzen Dark Mode und violette Aktionen. Der Chat ist die Hauptansicht, ohne Chat/Work-Umschalter. Modell, Leistung und Verlauf sind über das Menü links oben erreichbar. Eine native Java-Umsetzung des gewünschten Pulsing-Border-Effekts beleuchtet Chatbar, Bildschirmränder, neueste Antwort, Seitenleiste sowie Modell-/Leistungskarten und Hauptaktionen: Blau/Violett im Leerlauf, mehrfarbig beim Generieren und kurz Grün nach erfolgreichem Abschluss. [Design-System, Abläufe und Prüfung](docs/UI-REDESIGN.md).

Während Berechnungen bleiben die Arbeitsfarben statisch, damit die Lichtanimationen nicht mit Inferenz und Benchmarks konkurrieren. Im Leerlauf und nach Abschluss berücksichtigen Animationen Systemanimationen, Energiesparmodus und den Activity-Lebenszyklus. „Neu generieren“ speichert eine Antwortvariante als eigenen Chat und erhält den ursprünglichen Verlauf. [Performance-Korrektur und Prüfnachweise](docs/validation/PERFORMANCE-UI.md).

## Stand

Der integrierte Quellstand **0.5.1** erweitert die bisherige Basis um lokale Tools einschließlich `suche`, abschnittsbasierte RAG-Pipeline, IVF-INT8-Index mit mmap, Quellenanzeige sowie Cache-/Prefill-Diagnosen und einen Fünf-Turn-Benchmark. Datenbank, Vektorindex und Modellgewichte werden separat bereitgestellt. Der Android-Query-Encoder führt jetzt auch **EmbeddingGemma 2** als lokales Text-GGUF aus; Einrichtung und Validierungsgrenzen stehen in [rag/README.md](rag/README.md). Details zum Index: [rag/IVF.md](rag/IVF.md), zum Chat-Benchmark: [benchmark/README.md](benchmark/README.md). Die Basis kombiniert eine native Java-Oberfläche, Hintergrundgenerierung, dateigebundene GGUF-Freigabe, Chat-Archiv und Langdruckaktionen, striktes UTF-8 sowie zusätzliche skalare PQ-/Hadamard-Prüfungen. Vorherige Releasezweige bleiben als Historie erhalten. Die Appversion allein belegt keine erfolgreiche Geräte-/Modellprüfung.

Version **0.4.1** ergänzt eine dateigebundene Option **Trotzdem fortfahren** bei abweichender Prüfsumme und einen allgemein formulierten GGUF-Import. Andere von der Runtime unterstützte GGUF-Modelle benötigen keine Bonsai-Prüfsumme. Details: [UPDATE-0.4.1.md](UPDATE-0.4.1.md).

Version **0.4.0** ergänzt Hintergrundverarbeitung mit Android-Vordergrunddienst, Abbrechen-Benachrichtigung und CPU-Wachhaltung bei ausgeschaltetem Bildschirm. Bekannte Bonsai-2-4B-Dateien werden beim Import und Laden per SHA256 verglichen. Bei einer Abweichung kann die genaue Datei ausdrücklich freigegeben werden. Optimierte CPU-/Vulkan-Profile werden gegen skalare Quantisierungskernel geprüft; ein CPU-Kompatibilitätsmodus mit Microbatch 1 steht als Rückfall zur Verfügung. Details: [UPDATE-0.4.0.md](UPDATE-0.4.0.md).

Die App bleibt vollständig offline. Das A55-Protokoll zeigt für die Q2_0-Kopie mit unverständlichen Antworten eine andere SHA256 als die getestete offizielle Datei, obwohl die Dateigröße stimmt. Die geprüfte Datei antwortet im Host-Test korrekt. Unter **Modell und Einstellungen → Beispielmodell: Bonsai 2 4B herunterladen** lässt sie sich über den Browser beziehen. Die App ist nicht auf dieses Modell beschränkt. Danach neu importieren und einen neuen Chat starten. Ein physischer A55-Nachtest bleibt erforderlich. Über **Modelltest ohne Chatverlauf** lassen sich Antworten, Modellmetadaten und SHA256 lokal prüfen und kopieren. Zur NPU auf dem A55: [NPU-STATUS.md](NPU-STATUS.md).

Implementiert:

- Chatansicht mit Antwortstreaming, Abbrechen, seitlicher Chatliste und lokalem SQLite-Verlauf. Tool-Runden bleiben intern; aktuelles Thinking erscheint vorübergehend in einer Zeile.
- Einen Chat im Seitenmenü lange drücken: **Löschen**, **Archivieren**, **Abbrechen**. Tippen außerhalb schließt das Menü ebenfalls. Archivierte Verläufe bleiben unter **Archivierte Chats** erreichbar und lassen sich dort per langem Drücken wiederherstellen. Löschen erfordert eine Bestätigung.
- Import einer lokalen GGUF-Datei über den Android-Dateiauswahldialog. Die App erstellt eine private Kopie; ein abgebrochener Import ersetzt das alte Modell nicht.
- JNI-Anbindung an den originalen PrismML-Fork mit PQ2_0/PTQ1_0 und Bonsai-2-Transformationen.
- CPU als Ausgangspunkt, 4 Threads, 4.096 Tokens Kontext, maximal 512 Antworttokens. Thinking deaktiviert.
- Modell laden/entladen, Kontext-/Thread-Einstellungen und Anzeige des Prozess-RAM.
- Benchmark mit einem Aufwärmdurchlauf, drei Messläufen und JSON-Export.
- Kernel-Optimierung ohne app-eigene Temperatursperre: Start und Fortsetzung bei jedem Android-Thermalstatus. Androids Geräteschutz bleibt aktiv; Temperaturstatus vor/nach der Messung und pro Profil stehen im JSON-Bericht. Ergebnisse sind temperaturabhängig, Rechen- und Stabilitätsprüfungen bleiben aktiv.

Lokale Tool-Aufrufe für Rechner und ein schlüsselbasiertes Langzeitgedächtnis sind im Java-Chatpfad implementiert (siehe unten). Die RAG-/IVF-Suchschnittstelle ist enthalten; Wissensdatenbank und Index werden nicht mit der APK ausgeliefert. Python läuft für die Vorbereitung auf dem Laptop, nicht in der App. SymPy, STT und TTS sind nicht enthalten. Das Gedächtnis ist keine semantische Suche. Generierung läuft beim App-Wechsel und bei ausgeschaltetem Bildschirm weiter. Ein Android-Zwangsstopp oder Prozessabbruch beendet die Berechnung; sie wird nicht automatisch erneut gestartet. Die App benötigt Berechtigungen für Vordergrunddienst und CPU-Wachhaltung sowie optional sichtbare Benachrichtigungen, weiterhin keine Internetberechtigung.

## Inkrementeller Kontext und Reasoning

Der normale Chat behält denselben KV-Cache über mehrere Turns. Bei jeder Generierung wird der Prompt neu gerendert und tokenisiert, das gemeinsame Token-Präfix mit dem tatsächlich dekodierten Cache bestimmt und nur der abweichende Suffix entfernt und neu dekodiert. Bei einem vollständig vorhandenen Prompt wird nötigenfalls das letzte Token wiederholt, um passende Logits zu erhalten. Der System-Prompt bleibt zwischen Turns unverändert, bis im Prompt-Editor neue Vorgaben gespeichert werden. Datum/Uhrzeit, falls das Modell-Template sie verwendet, werden pro Modellladung fixiert und ändern den Prompt nicht bei jeder Nachricht.

`<think>…</think>` bleibt nur während der aktuellen Generierung im Kontext. Die native Streaming-Filterung verarbeitet auch über Token-/Chunkgrenzen geteilte Tags. Die endgültige Antwort enthält ausschließlich Text außerhalb des Reasonings. Ab v0.5.1 zeigt die bisherige Zeile „Bonsai denkt nach …“ den aktuellen Thinking-Text separat und einzeilig; sichtbar ist das Ende eines auf 240 Zeichen begrenzten Ausschnitts. Er wird weder als Antwort gespeichert noch in Logs oder Benchmarkantworten übernommen. Wenn das Modell kein Thinking ausgibt, wird keines erfunden. Während Prompt-Prefill und aktivem Prefix-Vorwärmen zeigt dieselbe Zeile Fortschritt in Prozent und gemessene neue Prompt-Tokens pro Sekunde. Der Fortschritt umfasst wiederverwendete Cache-Tokens; die Rate zählt nur frisch dekodierte Tokens und wird nach jedem abgeschlossenen Decode-Batch aktualisiert. Bis zur ersten Messung steht dort „… Tok/s“. Sobald Antworttext eintrifft, wechselt die Anzeige zum normalen mehrzeiligen Antworttext. Für eine Nutzerfrage stehen über alle Tool-Runden zusammen ungefähr **600 Reasoning-Tokens** zur Verfügung. Beim Erreichen des Budgets wird `</think>` in den laufenden Kontext eingefügt und mit der Endantwort fortgefahren. Die eingestellte maximale Antwortlänge begrenzt den sichtbaren Antwortteil; Platz für Reasoning und Abschluss wird zusätzlich innerhalb des bestehenden `n_ctx` reserviert. Thinking bleibt im Template standardmäßig deaktiviert; das Budget begrenzt trotzdem auftretendes Reasoning. GBNF-Tool-Grammatiken erlauben keine Thinking-Blöcke und benötigen dafür keine Reserve.

Nach Ende der Generierung wird der Cache auf den kanonisch gerenderten Verlauf mit ausschließlich finalem Assistant-Text abgeglichen. Dadurch muss nach einem Thinking-Block dessen ersetzter Suffix einmal mit dem finalen Text dekodiert werden; das unveränderte Präfix bleibt erhalten. Nach Tool-Runden entfernt ein abschließender Abgleich auch die internen Aufrufe/Ergebnisse aus dem Cache des sichtbaren Chats. Neue Assistant-Nachrichten werden ohne Reasoning gespeichert; ältere Assistant-Zeilen werden beim Lesen bereinigt. Wörtliche Tags in User-Nachrichten werden erhalten.

Passt der Verlauf einschließlich Antwortbudget nicht mehr in den Kontext, werden die ältesten vollständigen Turns aus dem **Modellkontext** entfernt. System-Prompt, aktuelle Frage und ihre Tool-Runden bleiben erhalten. SQLite-Verläufe werden dabei nicht gelöscht. Nach dem einmaligen Neuaufbau des geänderten Suffixes arbeitet das verbleibende Fenster wieder inkrementell. Eine einzelne zu große aktuelle Frage wird mit einer Fehlermeldung abgewiesen. **Neuer Chat**, Chatwechsel und neue Antwortvarianten leeren den Cache auf dem Inferenz-Worker. Abbruch und Decode-Fehler verwerfen den Cache ebenfalls, damit keine unvollständigen Reasoning-Zustände weiterverwendet werden. Benchmarks und Modellprüfungen starten weiterhin ausdrücklich mit kaltem Cache.

Es gibt weiterhin nur einen Modellkontext/KV-Cache mit unveränderter Standard-Kontextlänge und standardmäßig F16-K/V. Die neu ergänzten FA-/Q8-Optionen sind ausdrücklich optional und geräteseitig zu prüfen. Zusätzlich wird eine auf die Kontextlänge begrenzte Liste von Token-IDs geführt (bei 4096 Tokens etwa 16 KiB); es werden keine KV-Snapshots und keine Reasoning-Transkripte gespeichert. Native Statistiken enthalten unter anderem `reused_prompt_tokens`, `decoded_prompt_tokens`, `dropped_turns`, `reasoning_tokens`, `reasoning_capped`, `cached_tokens`, `kv_positions` und `cleanup_ms`. Die UI verwendet die Zeit bis zum ersten sichtbaren Texttoken.

[Prüfungen und Grenzen der KV-Änderung](VALIDATION-KV-CACHE.md). Cache-Wiederverwendung reduziert erneutes Prefill; gleichbleibende Tokens/s auf dem A55 sind damit nicht zugesichert. Längere Aufmerksamkeit und thermische Drosselung bleiben mögliche Ursachen für langsameren Decode.

## Lokale Tools im Chat

Ab **v0.5.1** lässt sich
unter **Menü → Modell → Antworten anpassen → Systemprompt bearbeiten** der Prompt
für alle Chats anpassen. **Eigene Vorgaben** enthalten Sprache und Antwortstil.
Die getrennten **Tool-Anweisungen** enthalten Tool-Namen, Argumente, Beispiele
und Ergebnisregeln. Sie sind zunächst schreibgeschützt; erst **Tool-Anweisungen
entsperren → Trotzdem bearbeiten** erlaubt Änderungen nach einer Warnung.
Bei jedem Öffnen wird der Bereich wieder gesperrt. Beide Bereiche lassen sich
im Editor auf Standard setzen; der Tool-Reset benötigt ebenfalls das Entsperren.

**Prompt speichern** übernimmt die Vorgaben lokal ab der nächsten Nachricht,
leert den bisherigen Modellcache und wärmt den geänderten Systemprompt bei
aktivem Vorwärmen erneut vor. Bestehende Chats und das Gedächtnis bleiben erhalten.
**Abbrechen** verwirft den Entwurf; bei Activity-Neuerstellung bleibt er erhalten,
der Tool-Bereich benötigt erneut die Warnbestätigung. Während einer Berechnung
ist Speichern gesperrt. Eigene Vorgaben sind auf 4.000, Tool-Anweisungen auf
16.000 Zeichen begrenzt; ein vollständig leerer Prompt wird abgewiesen. Längere
Prompts verbrauchen Kontext. Ein neuer Tool-Name im Text registriert keine
Funktion: Ausführbare Tools, Argumentprüfung und GBNF bleiben im Java-Code
definiert. Die aktive GBNF-Formatanweisung hängt die App automatisch an.

Der System-Prompt beschreibt ein einzelnes, vollständiges Tool-Format:

```text
<tool>{"name":"calc","args":{"expr":"2*(3+4)"}}</tool>
<tool>{"name":"merken","args":{"key":"name","value":"Alex"}}</tool>
<tool>{"name":"abrufen","args":{"key":"name"}}</tool>
<tool>{"name":"suche","args":{"query":"Photosynthese"}}</tool>
<tool>{"name":"wikidata","args":{"op":"facts","entity":"Japan","property":"P36"}}</tool>
```

Nach jeder Generierung prüft Java die komplette Antwort per Regex und validiert Name und Argumente. Ein eigener Java-Parser berechnet `+ - * / % ^`, Klammern, Dezimalzahlen, wissenschaftliche Schreibweise, `pi`, `e` und die Funktionen `sqrt`, `abs`, `sin`, `cos`, `tan`, `log` und `log10`. Winkel sind in Radiant; Division durch null, nicht endliche Ergebnisse und übermäßig lange oder tiefe Ausdrücke werden abgewiesen. Es gibt keine zusätzliche Bibliothek oder Netzwerkverbindung.

`merken` speichert auf Wunsch der Person einen Text unter einem Schlüssel oder ersetzt dessen bisherigen Wert. `abrufen` liest genau diesen Schlüssel und meldet fehlende Einträge ausdrücklich. Beispiel: „Merke dir unter dem Schlüssel name: Ich heiße Alex.“ In einem neuen Chat: „Rufe den Schlüssel name ab. Wie heiße ich?“ Die Tabelle `memory` ist chatübergreifend und bleibt beim Löschen eines Chats erhalten. Schlüssel sind auf 128, Werte auf 4000 Zeichen begrenzt. Es gibt keine automatische Suche nach unbekannten Schlüsseln. Die SQLite-Migration von Version 1 oder 2 auf 3 erhält bestehende Chats und Archive. Eine Deinstallation löscht auch das Gedächtnis.

Eine Antwort verwendet maximal **drei Modellgenerierungen**, also höchstens zwei Tool-Ausführungen und eine abschließende Antwort. Tool-Aufruf und JSON-Ergebnis werden nur für diesen Durchlauf intern an den Modellverlauf angehängt; Ergebnisse verwenden für kompatible Chat-Templates die Rolle `user`. Im UI und gespeicherten Chat stehen nur Frage und Endantwort. Die Endantwort wird live gestreamt, sobald ihr Format erkannt ist; Tool-Aufrufe und interne GBNF-Markierungen erscheinen dabei nicht im UI. Fehler der Tools gehen an das Modell zurück; gemischte oder unvollständige Tool-Tags, abgeschnittene Aufrufe und weitere Aufrufe nach dem Limit führen zu einer sichtbaren Fehlermeldung statt einer Tool-Ausgabe im Chat. Abbrechen verhindert weitere Runden; bereits ausgeführte Speicheroperationen bleiben bestehen.

Unter **Modell → Antworten anpassen → GBNF für Tool-Aufrufe (experimentell)** lässt sich die Formatvorgabe zusätzlich im nativen Sampler erzwingen. Standardmäßig ist die Option ausgeschaltet. Mit **Einstellungen speichern** gilt sie ab der nächsten Chatnachricht; ein Neuladen des Modells ist dafür nicht nötig. Während einer laufenden Berechnung ist der Schalter gesperrt. Einzelprompt-Benchmark, Optimierung und Modellprüfung verwenden keine Grammatik; der Fünf-Turn-Chatbenchmark nutzt dieselbe Einstellung wie der Chat.

Bei aktivem GBNF erlaubt die Grammatik entweder genau einen der fünf Tool-Aufrufe mit JSON-Stringargumenten oder eine intern mit `<final>…</final>` markierte Textantwort. Die App entfernt diese Markierungen vor Anzeige und Speicherung. In der dritten Runde ist nur noch eine Endantwort zulässig. Abgeschnittene Markierungen werden als Fehler gemeldet. Der optionale UTF-8-Grammatikparameter wird an `NativeEngine.generate` übergeben; `llama_sampler_init_grammar` steht vor den bisherigen Samplern. Eine fehlerhafte Grammatik führt zu einem Fehler statt zum stillen Abschalten der Vorgabe. Ausdrucks- und Speichergrenzen werden weiterhin in Java geprüft.

Die Formatbefolgung und Antwortqualität des echten Bonsai-Modells müssen noch auf dem Gerät geprüft werden, etwa mit „Berechne 173 * 249 mit calc“ und den obigen Gedächtnisbeispielen, jeweils mit aus- und eingeschaltetem GBNF.

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

Der native GBNF-Test prüft die tatsächlich aus Java exportierten Grammatiken mit dem gebundenen PrismML-Parser sowie die Reihenfolge der Sampler. Er benötigt keine Modellgewichte:

```bash
mkdir -p build/grammar-export
javac -d build/grammar-export app/src/main/java/de/bonsai/offline/ToolGrammar.java tests/ExportToolGrammar.java
java -cp build/grammar-export de.bonsai.offline.ExportToolGrammar build/grammar-export
cmake -S app/src/main/cpp -B build/grammar-check -DBONSAI_GRAMMAR_TESTS=ON -DBONSAI_HOST_SMOKE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/grammar-check --target bonsai-grammar-test --parallel 2
./build/grammar-check/bonsai-grammar-test build/grammar-export
```

## Technische Quellen

- [Bonsai 2 4B: Model Card und Dateiformate](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf)
- [PrismML-Fork, fest verwendeter Release](https://github.com/PrismML-Eng/llama.cpp/releases/tag/prism-b10777-e3de715)
- [Offizielle Backend-/Formatübersicht](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/BACKEND-SUPPORT.md)

Die App ist ein eigenständiges Projekt und wird weder von OpenAI noch von PrismML herausgegeben.
