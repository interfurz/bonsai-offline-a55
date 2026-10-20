## Bonsai Offline 0.4.0 – CPU und Vulkan

**Installierbares ARM64-Release mit CPU und Vulkan, mit neuem Signierschlüssel.**

**Kein direktes Update von 0.3.0/0.3.1:** Auf ausdrücklichen Wunsch wurde ein neuer dauerhafter Schlüssel erstellt. Android akzeptiert diese APK nicht als Update einer mit dem bisherigen Schlüssel signierten Installation. Eine Deinstallation löscht private Chats, Einstellungen und die Modellkopie. Die bestehende App daher nicht vorschnell entfernen. Der neue Schlüssel beseitigt keine Samsung-/Play-Protect-Installationswarnung garantiert.

Unter **Assets** `Bonsai-Offline-0.4.0-arm64-CPU-Vulkan.apk` herunterladen. Die APK ist signiert und installierbar; Modellgewichte werden separat importiert. Für alle späteren Updates muss derselbe neue Schlüssel verwendet werden. Private Keystore und Passwortdatei werden dem Eigentümer separat zur Sicherung bereitgestellt und sind nicht Teil dieser Veröffentlichung.

### Änderungen

- Hintergrundgenerierung beim App-Wechsel und bei ausgeschaltetem Bildschirm: Vordergrunddienst, CPU-Wachhaltung und Abbrechen-Benachrichtigung.
- Prüfung der bekannten offiziellen Bonsai-2-4B-Q2_0-/PQ2_0-Dateien anhand von SHA256 und Größe beim Import und Laden. Die auf dem A55 gemeldete Q2_0-Datei weicht trotz gleicher Größe von der geprüften Fassung ab.
- Link auf den geprüften Modelldownload; Benchmarkexport mit Prüfsumme, Metadaten und Antworttexten.
- Skalare CPU-Referenz, automatischer Rückfall bei Rechenabweichungen und optionaler Kompatibilitätsmodus.
- Änderungen aus 0.3.1 integriert: Entwürfe bleiben bei Ablehnung erhalten; Kernel-Optimierung mit Temperaturbeobachtung ohne zusätzliche App-Sperre.

### Prüfnachweis

- Vollständiger CPU/Vulkan-Build, 26 Release-Tests, Lint und Backendprüfung bestanden: https://github.com/interfurz/bonsai-offline-a55/actions/runs/39196937446
- Geprüfte Original-Q2_0-Datei antwortet im Host-JNI-Test optimiert und skalar korrekt auf 1+1 und 2+2. Vollständiger emulierter ARM64-Modelltest liefert ebenfalls 4.
- Paket `de.bonsai.offline.debug`, Version 0.4.0, Versionscode 7; nicht debuggable und 16-KB-Zipalignment geprüft.
- APK-Signatur (v3), neuer RSA-4096-Schlüssel und unveränderte Inhalte aller APK-Einträge nach Signierung geprüft. Zertifikat-SHA256: `933bc216a40682ce964cb0a2db03fb7ab511ae1916a7ea3bd0d0328c153bc462`.
- APK-SHA256: `6b46e54cf6349dda37352cc62b119f29de394335abe3df22d261b1ad86a5ff2f`.
- APK-Quellcommit: `2bb08b43ccb068460e8c9f1a00d0194748d6c32d`. Build-Provenienz und APK-Prüfsumme liegen unter Assets.

Kein physischer A55-Nachtest durchgeführt. Die Behebung der Antworten nach Neuimport ist am Gerät noch zu bestätigen; auch Samsung-Energieverwaltung und Vulkan-Treiberverhalten sind nicht physisch geprüft. Android-Zwangsstopp und Prozessbeendigung können die Hintergrundverarbeitung beenden. Ein echter Exynos-1480-NPU-Modus ist mangels unterstütztem Runtime-Pfad nicht enthalten. Modellgewichte werden separat importiert.
