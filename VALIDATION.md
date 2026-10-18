# Prüfstand der CPU-Test-APK

Datum: 17. Oktober 2026

## Erfolgreich geprüft

- Gradle 8.11.1, JDK 17, Android SDK 35, NDK 28.2.13676358 und CMake 3.22.1: `:app:assembleDebug :app:lintDebug` erfolgreich.
- Java-App als signierte Testversion; native ARM64-Runtime mit Release-Optimierung.
- Android-Lint: 0 Fehler, 3 Warnungen. Sie betreffen das ältere Mindest-Android bei Backup-/Back-Navigation-Attributen und die bewusst fehlende ChromeOS-/x86_64-Variante. Backups sind über `allowBackup=false` deaktiviert; Android 12+ erhält zusätzlich Ausschlussregeln.
- APK-Signatur mit `apksigner verify`: gültig, Schema v2, ein Signierer.
- `zipalign -c -P 16 4`: erfolgreich. Die beiden nativen Bibliotheken sind AArch64, liegen unkomprimiert im APK und die JNI-LOAD-Segmente besitzen 16-KiB-Ausrichtung.
- Paket `de.bonsai.offline.debug`, App-Name Bonsai, Version 0.1.0, minSdk 28, targetSdk 35.
- `aapt2 dump permissions`: keine angeforderten Android-Berechtigungen, insbesondere keine Internetberechtigung.
- Manifest/XML, Java-Syntax, JNI-Namen, Runtime-Versionsbindung und Python-Setup-Skripte geprüft.
- UTF-8-Streaming über alle Byte-Schnittgrenzen von Umlauten, Emoji, japanischen und kombinierenden Zeichen sowie NUL getestet.

## Funktionstest mit echter Bonsai-GGUF

Die gleiche JNI-Implementierung wurde für den Linux-Build-Rechner kompiliert und über die echte Java-Klasse NativeEngine aufgerufen. CPU-Kernels waren optimiert; reine Graph-/Chat-Helfer wurden für diesen Funktionstest mit BONSAI_HOST_SMOKE schneller kompiliert. Das Android-APK verwendet den nativen Release-Build.

Modell: `Ternary-Bonsai-2-4B-PQ2_0.gguf`, 1.146.465.120 Bytes.
Hugging-Face-Revision: `8d6557168168f91ea6697a4a49ed2ef622b53adb`.
SHA256: `d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409`.
Dateigröße und Prüfsumme stimmen mit den veröffentlichten Metadaten überein.

Ergebnis von tests/NativeSmoke.java:

- Fehlender Modellpfad wird mit einer Java-Exception abgewiesen.
- Echte PQ2_0-Datei mit den Bonsai-2-Hadamard-Transformationen geladen.
- Abbrechen meldet korrekt stopped=true; nach Reset ist Generierung wieder möglich.
- Frage „Was ist 2 + 2?“ ergibt `4`.
- Folgefrage mit Verlauf „Addiere 3 dazu“ ergibt `7`.
- Modell und Kontext anschließend entladen.

Dies ist ein Host-Funktionstest, kein Android-/A55-Benchmark. Seine Zeitwerte dürfen nicht als Smartphone-Leistung interpretiert werden.

## Noch offen

- Installation und Ausführung auf dem Galaxy A55, Oberfläche, Dateiauswahl, SQLite-Verlauf und Activity-Lebenszyklen.
- Inferenz mit den ARM64-Kernels auf Exynos 1480: Stabilität, tatsächlicher RAM-Bedarf, Geschwindigkeit und Temperatur.
- In-App-Benchmark einschließlich RSS-Abtastung und JSON-Export auf Android.
- Vulkan und Xclipse 530. Diese APK enthält nur CPU-Inferenz.
