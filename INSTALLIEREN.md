# Bonsai auf deinem Samsung Galaxy A55 installieren

Für die bereitgestellte CPU-Test-APK brauchst du auf dem Laptop weder Android Studio noch Git oder Python.

1. **Bonsai-Offline-0.1.0-arm64-CPU.apk** auf dem A55 herunterladen oder per USB in den Ordner **Downloads** kopieren.
2. **Eigene Dateien → Downloads → APK** öffnen und installieren. Falls Android fragt, dieser Dateien-App das Installieren erlauben. Falls Samsungs Auto Blocker die Installation verhindert, die konkrete angezeigte Blockierung prüfen.
3. Das [offizielle Bonsai-2-4B-Repository](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/tree/main) öffnen und diese Datei herunterladen:

   ```text
   Ternary-Bonsai-2-4B-PQ2_0.gguf
   ```

   Die Datei ist etwa 1,15 GB groß. Es wird keine weitere Modelldatei für den Textchat gebraucht.

4. **Bonsai** öffnen → **Bonsai-Modell auswählen** → die lokale GGUF-Datei auswählen. Import und Laden abwarten. Die App legt eine private Kopie an; dafür muss zusätzlich zur Originaldatei mindestens etwa 1,2 GB frei sein.
5. Eine kurze Frage stellen. Danach WLAN und mobile Daten ausschalten und eine weitere Frage stellen, um den Offline-Betrieb zu prüfen.
6. **Seitenmenü oben links → Benchmark → Benchmark starten**. Die App geöffnet lassen. Nach einem Aufwärmdurchlauf und drei Messläufen das Benchmark-Menü erneut öffnen. Mit **JSON exportieren** kannst du das Ergebnis speichern und hier anhängen.

Starte mit **CPU, 4 Threads und 4.096 Tokens Kontext**. Falls Android die App beendet oder das Modell nicht lädt, zuerst andere Apps schließen und unter **Bonsai ⌄ → Modell und Einstellungen** den Kontext auf **2.048 Tokens** reduzieren.

Das ist eine signierte Testversion mit optimiertem nativen CPU-Code. Vulkan, RAG, die Wissensdatenbanken und externe Tools sind noch nicht aktiviert. Die APK enthält keine Modellgewichte und fordert keine Internetberechtigung an. Ein Test auf deinem konkreten A55 steht noch aus.

Deinstallation löscht die privaten Chats, Einstellungen und die importierte Modellkopie. Die separat heruntergeladene Original-GGUF im Downloads-Ordner bleibt erhalten.
