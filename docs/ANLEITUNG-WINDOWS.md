# Deine Bonsai-App auf dem XMG-Laptop bauen

**Ergebnis:** eine eigene Android-Test-App mit ChatGPT-ähnlicher dunkler Oberfläche, lokalem Bonsai-Chat und Benchmark. Der erste Build nutzt die CPU. Die App benötigt später kein Internet; der Laptop braucht zum Einrichten und Bauen eine Verbindung.

**Aktueller Stand:** Eine CPU-Test-APK wurde hier erfolgreich gebaut. Wenn du nur die App benutzen möchtest, folge `INSTALLIEREN.md`. Diese Windows-Anleitung ist für das eigene Bauen und Weiterentwickeln gedacht. Die RTX 4060 wird dafür nicht benötigt. Der Funktionstest auf deinem A55 steht noch aus.

## 1. Programme installieren

Auf deinem Windows-Laptop:

1. [Android Studio](https://developer.android.com/studio) installieren. Im Einrichtungsassistenten **Standard** wählen. Den vorgeschlagenen Android-SDK-Ordner merken.
2. [Git für Windows](https://git-scm.com/downloads/win) installieren. Die Standardeinstellungen genügen; Git muss danach in PowerShell erreichbar sein.
3. [Python 3](https://www.python.org/downloads/windows/) installieren. Im Installer **Add python.exe to PATH** aktivieren.

Danach PowerShell neu öffnen. Diese Befehle müssen funktionieren:

```powershell
git --version
py --version
```

## 2. Projekt entpacken und Runtime herunterladen

Die bereitgestellte Datei `bonsai-offline-projekt.zip` herunterladen. Rechtsklick → **Alle extrahieren**. Den Ordner `bonsai-offline` zum Beispiel nach `C:\Bonsai\bonsai-offline` legen. Dort müssen `settings.gradle`, `gradlew.bat`, `app` und `scripts` liegen.

In diesem Ordner Rechtsklick auf eine freie Fläche → **Im Terminal öffnen**. Alternativ in PowerShell:

```powershell
cd C:\Bonsai\bonsai-offline
py scripts\fetch_runtime.py
```

Das Skript lädt genau den PrismML-Unterbau, der das PQ2_0-Format und die nötigen Bonsai-2-Transformationen unterstützt. Erfolgreich ist dieser Schritt, wenn am Ende `PrismML ... verified` erscheint. Der neue Ordner `vendor\llama.cpp` enthält dann den Runtime-Quellcode.

## 3. Projekt in Android Studio öffnen

Android Studio → **Open** → den Ordner `C:\Bonsai\bonsai-offline` auswählen. Nicht nur den Unterordner `app` öffnen.

Falls Android Studio nach Vertrauen für das lokale Projekt fragt, das selbst heruntergeladene Projekt öffnen. Warten, bis Gradle synchronisiert ist. Studio darf die im Projekt festgelegten Werkzeugversionen herunterladen. Ein vorgeschlagenes automatisches Upgrade von Gradle/Android Gradle Plugin für diesen ersten Versuch überspringen.

Falls die Synchronisation wegen fehlender SDK-Pakete stoppt: **Tools → SDK Manager** öffnen.

Unter **SDK Platforms**:

- **Android API 35 / Android 15** installieren.

Unter **SDK Tools** die Option **Show Package Details** aktivieren und installieren:

- **Android SDK Build-Tools 35.0.0**
- **NDK (Side by side) 28.2.13676358**
- **CMake 3.22.1**

Außerdem **Android SDK Platform-Tools** installiert lassen. Danach **File → Sync Project with Gradle Files**.

## 4. APK bauen

Im Terminal von Android Studio, im Projekt-Hauptordner:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Beim ersten Mal werden Gradle und weitere Abhängigkeiten geladen; anschließend wird der native C++-Unterbau kompiliert. Das kann mehrere Minuten dauern. Währenddessen den Laptop am Strom lassen.

Falls `JAVA_HOME is not set` erscheint: In Android Studio unter **Settings → Build, Execution, Deployment → Build Tools → Gradle** das **Embedded JDK / jbr** wählen. Für das Terminal derselben Sitzung den tatsächlichen JDK-Ordner verwenden. Bei der Standardinstallation ist das meist:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

JDK 17 oder neuer ist erforderlich. Diese Projektversion verwendet Gradle 8.11.1. Falls Studio ein neueres JDK verwendet, das dazu nicht passt, in den Gradle-Einstellungen JDK 17 oder 21 auswählen und denselben JDK-Ordner im Terminal setzen.

Bei Erfolg steht am Ende `BUILD SUCCESSFUL`. Die Test-APK findest du unter:

```text
C:\Bonsai\bonsai-offline\app\build\outputs\apk\debug\app-debug.apk
```

Wenn `lintDebug` nach einem erfolgreichen `assembleDebug` Fehler meldet, ist das ein Prüfungsfehler und muss separat beurteilt werden. Die Fehlermeldung nicht einfach unterdrücken.

## 5. Auf dem Galaxy A55 installieren

1. Das A55 per USB anschließen und die APK in **Downloads** auf dem Handy kopieren. Alternativ Android Studios **Run** mit aktivierter USB-Debugging-Verbindung verwenden.
2. Auf dem Handy **Eigene Dateien → Downloads → app-debug.apk** öffnen.
3. Falls Android es verlangt, der verwendeten Dateien-App das Installieren dieser APK erlauben. Bei Samsung kann der **Automatische Sperrmodus / Auto Blocker** eine manuelle Installation verhindern; die angezeigte konkrete Blockierung prüfen. Die Einstellung nicht pauschal dauerhaft deaktivieren.
4. Die App **Bonsai** öffnen.

Dies ist eine mit dem lokalen Debug-Schlüssel signierte Test-App. Für spätere dauerhafte Veröffentlichungen wird ein eigener stabiler Release-Schlüssel benötigt.

## 6. Bonsai 2 4B importieren

Auf dem Laptop oder Handy das [offizielle Modell-Repository](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/tree/main) öffnen. Ausschließlich diese Datei für den ersten Versuch laden:

```text
Ternary-Bonsai-2-4B-PQ2_0.gguf
```

Laut Model Card etwa **1,15 GB**. Falls der Download auf dem Laptop erfolgt, die Datei auf das A55 kopieren. Nicht die 27B-Version, keinen MTP-Drafter und keinen Vision-Projektor herunterladen.

In der App **Bonsai-Modell auswählen** antippen, die lokale GGUF-Datei auswählen und den Import abwarten. Die App legt eine eigene Kopie an. Dafür müssen zusätzlich zur Originaldatei mindestens etwa 1,2 GB frei sein; besser deutlich mehr.

Wenn oben **Lokal · CPU** erscheint, zunächst eine kurze Frage stellen, etwa:

```text
Erkläre in drei kurzen Sätzen, warum der Himmel blau ist.
```

Danach WLAN und mobile Daten ausschalten und eine weitere Frage stellen. So prüfst du den Offline-Betrieb direkt.

## 7. Benchmark starten

1. Andere Apps schließen und das Handy abkühlen lassen.
2. In Bonsai oben links das Seitenmenü öffnen.
3. **Benchmark → Benchmark starten** wählen.
4. Die App geöffnet lassen. Sie führt einen Aufwärmdurchlauf und drei Messläufe aus.
5. Danach das Menü **Benchmark** erneut öffnen.
6. **JSON exportieren** wählen und die Datei speichern.

Das Ergebnis zeigt Tokens/s, Zeit bis zum ersten Token, Prompt-Verarbeitungszeit und den erfassten Spitzenwert des Prozess-RAM. Der RAM-Wert wird alle 250 ms abgetastet; sehr kurze Spitzen können fehlen. Es ist keine vollständige GPU-Speichermessung.

Starte mit **4 Threads und 4.096 Tokens Kontext**. Danach kannst du unter **Bonsai ⌄ → Modell und Einstellungen** 6 Threads einstellen und denselben Benchmark wiederholen. Falls das Laden scheitert oder Android die App beendet, zuerst 2.048 Tokens Kontext testen. Die tatsächlichen Ergebnisse entscheiden, welche Einstellung besser ist.

## Wenn es nicht klappt

- **`git` oder `py` nicht gefunden:** Installation und PATH prüfen, danach PowerShell neu öffnen.
- **`PrismML source missing`:** Schritt 2 im richtigen Projektordner ausführen.
- **`SDK location not found`:** Projekt in Android Studio öffnen; SDK-Ordner im SDK Manager prüfen. Studio kann daraus `local.properties` erzeugen.
- **NDK/CMake fehlt:** exakt die Versionen aus Schritt 3 installieren.
- **Modell lädt nicht:** Dateiname und vollständigen Download prüfen, andere Apps schließen und Kontext auf 2.048 reduzieren.
- **App wird ohne Meldung geschlossen:** möglicher nativer Fehler oder Speichermangel; das muss anhand des Android-Logs geprüft werden.

Bei einem Buildfehler hier den **ersten konkreten Fehlerblock** und den zugehörigen `Caused by`-Abschnitt aus dem Build-Fenster senden. Bei einem erfolgreichen Start die exportierte Benchmark-JSON anhängen. Die große GGUF-Datei ist für die Fehleranalyse zunächst nicht nötig.

**Vulkan folgt separat.** Der bereitgestellte Standard-Build ist CPU-only. Seine erfolgreiche Ausführung ist der erste Funktionstest; GPU-Unterstützung auf der Xclipse 530 ist damit noch nicht nachgewiesen.
