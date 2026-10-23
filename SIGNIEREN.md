# Bonsai-Release signieren

Release **0.5.1.1** wird auf ausdrücklichen Wunsch mit dem hochgeladenen
`bonsai-release.keystore` signiert (Alias `bonsai-release`, PKCS12).
Sein Zertifikat stimmt mit der tatsächlich heruntergeladenen und per
`apksigner` geprüften 0.4.4-APK überein. Die APK bleibt ein **nicht debuggbares
Release**; der Paket-Suffix `.debug` ändert dieses Buildflag nicht.

| Veröffentlichungen | Zertifikat SHA256 |
| --- | --- |
| 0.3.0 / 0.4.5, Original-Debug-Keystore | `ce1a585cbd3b51dcb446c50814122d5c68832a6a052f2c6f139cf2e0c44208db` |
| 0.4.3 / 0.4.4 / 0.5 / 0.5.1 / 0.5.1.1, Release-Schlüssel | `d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d` |
| 0.4.0 / 0.4.1, historischer Schlüssel | `933bc216a40682ce964cb0a2db03fb7ab511ae1916a7ea3bd0d0328c153bc462` |

**0.5.1.1 ist signaturkompatibel mit 0.5.1, 0.5 und 0.4.3/0.4.4:** Paket und Signatur bleiben
gleich; Versionscode 15 ist höher als deren Versionscodes. Die veröffentlichte
0.5.1-APK wurde erneut heruntergeladen und mit `apksigner` geprüft.
[Konkreter Updatevergleich](release/0.5.1.1/update-compatibility.json) und
[Signaturprotokoll](release/0.5.1.1/signature-verification.txt) dokumentieren die neue APK.
Ein konkreter Installations-/Updateversuch auf dem A55 wurde nicht durchgeführt.

**Kein direktes Update von 0.4.5/0.3.0 auf diese 0.5.1.1-APK.** Android
verlangt für Updates desselben Pakets dasselbe Zertifikat oder eine gültige
Signierhistorie. Hier wird keine Schlüsselrotation behauptet. Die ursprüngliche
0.3.0 und 0.4.5 verwenden den anderen Original-Debug-Schlüssel; deren
Signaturkompatibilität miteinander bleibt eine historische, separate Aussage.

Paket bleibt `de.bonsai.offline.debug`, Versionscode ist 15. Ein abweichendes
Zertifikat erlaubt keine parallele Installation desselben Pakets. Die bestehende
App nicht vorschnell deinstallieren: dabei gehen private Chats, Einstellungen
und die interne Modellkopie verloren. Es gibt keinen automatischen Datentransfer
zwischen den verschiedenen Signieridentitäten.

## Schlüssel aufbewahren und APK prüfen

Der Keystore und Zugangsdaten bleiben außerhalb von Git, CI und Release-Assets.
Für spätere Updates derselben Signieridentität genau diesen Keystore sichern und
wiederverwenden. Alias des bereitgestellten Schlüssels: `bonsai-release`, Format
PKCS12. Es wird kein neuer privater Schlüssel erzeugt.

Android SDK Build-Tools 35.0.0:

```sh
apksigner sign --ks /privater/pfad/bonsai-release.keystore \
  --ks-key-alias bonsai-release --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out Bonsai-Offline-0.5.1.1-arm64-CPU-Vulkan.apk app-release-unsigned.apk
apksigner verify --verbose --print-certs Bonsai-Offline-0.5.1.1-arm64-CPU-Vulkan.apk
zipalign -c -P 16 -v 4 Bonsai-Offline-0.5.1.1-arm64-CPU-Vulkan.apk
```

Passwörter interaktiv oder über eine geschützte Umgebungsvariable übergeben;
nicht in Repository-Dateien oder öffentliche Buildlogs schreiben. Vor einer
Veröffentlichung Zertifikatsfingerabdruck, Versionscode, Paket, Signatur und
Dateiprüfsumme kontrollieren. Die Signatur beseitigt keine Samsung-/Play-Protect-
Installationswarnungen garantiert.
