# Bonsai 0.4.0 lokal signieren

Dieses vollständige CPU/Vulkan-Release-APK ist noch unsigniert und damit nicht installierbar. Für ein direktes Update der bisherigen CPU/Vulkan-Version muss der ursprüngliche private Signierschlüssel verwendet werden. Das öffentliche Zertifikat in einer vorhandenen APK reicht dafür nicht aus.

Erwarteter SHA256-Fingerabdruck des bisherigen Zertifikats:

`ce1a585cbd3b51dcb446c50814122d5c68832a6a052f2c6f139cf2e0c44208db`

## Android Studio

Die vorhandene Keystore-Datei und den Alias des bisherigen Schlüssels verwenden. Ein neues Keystore erzeugt keine update-kompatible Signatur. Keystore und Passwörter privat halten und nicht als GitHub-Assets hochladen.

## Windows / PowerShell mit Android SDK Build-Tools 35.0.0

Im Ordner mit der heruntergeladenen APK ausführen. Keystore-Pfad und Alias anpassen; Passwörter werden interaktiv abgefragt.

```powershell
$signer = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat"
& $signer sign --ks "C:\Pfad\bisheriger-schluessel.jks" --ks-key-alias "DEIN_ALIAS" --out "Bonsai-Offline-0.4.0-arm64-CPU-Vulkan.apk" "Bonsai-Offline-0.4.0-arm64-CPU-Vulkan-unsigned.apk"
& $signer verify --verbose --print-certs "Bonsai-Offline-0.4.0-arm64-CPU-Vulkan.apk"
```

Das ausgerichtete APK anschließend nicht mehr verändern. Vor Veröffentlichung den Fingerabdruck mit dem oben angegebenen Original vergleichen und eine neue SHA256-Prüfsumme für die signierte Datei erstellen. Die bestehende App nicht deinstallieren, um einen Schlüsselwechsel zu erzwingen: Das würde ihre privaten Chats und Modellkopie löschen.

Die korrekte Signatur garantiert nicht, dass Samsung Auto Blocker oder Google Play Protect eine außerhalb eines Stores installierte App ohne Rückfrage akzeptieren.
