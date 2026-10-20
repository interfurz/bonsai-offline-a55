# Bonsai-Release signieren

Seit Version **0.4.3** verwendet die App nach gemeldetem Verlust des vorherigen Keystores auf ausdrücklichen Wunsch einen **neuen dauerhaften Schlüssel**. Version 0.4.4 und künftige Updates müssen denselben neuen Schlüssel verwenden. Sie sind kein direktes Update der mit älteren Zertifikaten signierten 0.4.0/0.4.1- oder 0.3.x-Installation. Der gleiche Paketname mit anderem Zertifikat erlaubt auch keine parallele Installation. Eine Deinstallation löscht private Chats, Einstellungen und die Modellkopie; die alte App nicht vorschnell entfernen.

Aktuelles Zertifikat, SHA256:

`d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d`

Historisches Zertifikat von 0.4.0/0.4.1 (nicht mehr der aktuelle Schlüssel): `933bc216a40682ce964cb0a2db03fb7ab511ae1916a7ea3bd0d0328c153bc462`.

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
