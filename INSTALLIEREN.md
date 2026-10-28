# Bonsai auf dem Galaxy A55 installieren

1. Die signierte **Bonsai-Offline-0.5.5-arm64-CPU.apk** oder
   **Bonsai-Offline-0.5.5-arm64-CPU-Vulkan.apk** aus den
   [Release-Downloadlinks](https://github.com/interfurz/bonsai-offline-a55/releases/tag/v0.5.5)
   auf das A55 laden. Bei privatem Repository ist ein berechtigtes GitHub-Login nötig.
2. **Vor einem Update die Signieridentität beachten:** 0.5.5 verwendet denselben
   Release-Schlüssel und dasselbe Paket wie 0.5.4, mit höherem Versionscode 21.
   Paket, Zertifikat und Versionscode werden an den tatsächlichen APKs geprüft;
   eine Geräteinstallation wird damit nicht behauptet. 0.4.5 und 0.3.0 verwenden
   einen anderen Schlüssel und sind nicht direkt aktualisierbar.
   Details: [SIGNIEREN.md](SIGNIEREN.md). Die vorhandene App nicht vorschnell
   deinstallieren; dadurch werden lokale Daten gelöscht.
3. **Eigene Dateien → Downloads → APK** öffnen. Falls Android fragt, dieser
   Dateien-App das Installieren erlauben; eine konkrete Samsung-Blockierung prüfen.
4. Eine unterstützte lokale GGUF-Datei unter **Modell** importieren und laden.
   Beispiel: [Ternary-Bonsai-2-4B-PQ2_0.gguf](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/tree/main).
   Die App erstellt eine private Kopie; zusätzlich zur Download-Datei muss genug
   freier Speicher vorhanden sein. Modellgewichte sind nicht Bestandteil der APK.
5. Mit **CPU, 4 Threads und 4096 Tokens Kontext** beginnen. Bei Speichermangel
   andere Apps schließen und einen kleineren Kontext prüfen. Vulkan ist enthalten,
   seine Eignung für die konkrete Modell-/Treiberkombination muss auf dem Gerät
   geprüft werden. **Leistung** enthält Benchmark und Optimierung.
6. Eine kurze Frage stellen und den Betrieb anschließend ohne WLAN/mobile Daten
   prüfen. Die App fordert keine Internetberechtigung an.

Die lokale Suche `suche` benötigt eine separat bereitgestellte Datenbank und einen
passenden Index/Query-Encoder; diese Dateien werden nicht mit der APK ausgeliefert.
Einrichtung: [rag/README.md](rag/README.md), IVF und Gerätebenchmark:
[rag/IVF.md](rag/IVF.md). Eine fehlende Datenbank führt zu einer Tool-Fehlermeldung.

Der Name `.debug` im Paket ist historisch; die veröffentlichte APK ist ein
Release-Build mit ausgeschaltetem Debugging. Kein physischer A55-Test wird
behauptet. Eine Deinstallation löscht private Chats, Erinnerungen, Einstellungen
und Modellkopien; die separat heruntergeladene GGUF-Datei bleibt bestehen.
