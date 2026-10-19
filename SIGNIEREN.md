# Bonsai-Release signieren

Version 0.4.0 verwendet auf ausdrücklichen Wunsch einen **neuen dauerhaften Schlüssel**. Sie ist deshalb kein direktes Update der mit dem früheren Zertifikat signierten 0.3.0/0.3.1-Installation. Eine Deinstallation löscht private Chats, Einstellungen und die Modellkopie.

Aktuelles Zertifikat, SHA256:

`933bc216a40682ce964cb0a2db03fb7ab511ae1916a7ea3bd0d0328c153bc462`

## Schlüssel für künftige Updates sichern

Die private Datei `bonsai-release.keystore` und die zugehörige Passwortdatei werden separat im privaten Arbeitsbereich bereitgestellt. Sie gehören nicht in Git, GitHub-Release-Assets oder CI-Artefakte. Beide auf dem eigenen Laptop sichern. Alias: `bonsai-release`, Format: PKCS12, RSA 4096 Bit, Gültigkeit 10.000 Tage ab Erstellung.

Für spätere Updates diesen Schlüssel erneut verwenden. In Android Studio **Generate Signed Bundle / APK → APK → Choose existing** auswählen und die gesicherte Keystore-Datei mit Alias und Passwort verwenden.

Alternativ unter Windows / PowerShell mit Android SDK Build-Tools 35.0.0:

```powershell
$signer = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat"
& $signer sign --ks "C:\Pfad\bonsai-release.keystore" --ks-key-alias "bonsai-release" --out "Bonsai-Offline-signed.apk" "Bonsai-Offline-unsigned.apk"
& $signer verify --verbose --print-certs "Bonsai-Offline-signed.apk"
```

Das Passwort wird interaktiv abgefragt. Vor Veröffentlichung den Zertifikatsfingerabdruck vergleichen und die Prüfsumme der signierten Datei neu erstellen. Die korrekte Signatur garantiert keine unterdrückte Warnung von Samsung Auto Blocker oder Google Play Protect.
