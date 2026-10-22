# Bonsai-Release signieren

Release **0.4.5** wird auf ausdrücklichen Wunsch mit dem bereitgestellten
`Bonsai-original-debug.keystore` signiert. Die APK bleibt ein **nicht debuggable
Release**; der Name des Zertifikats oder der Paket-Suffix `.debug` ändern dieses
Buildflag nicht.

| Veröffentlichungen | Zertifikat SHA256 |
| --- | --- |
| 0.3.0 / 0.4.5, Original-Debug-Keystore | `ce1a585cbd3b51dcb446c50814122d5c68832a6a052f2c6f139cf2e0c44208db` |
| 0.4.3 / 0.4.4, damaliger neuer Release-Schlüssel | `d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d` |
| 0.4.0 / 0.4.1, historischer Schlüssel | `933bc216a40682ce964cb0a2db03fb7ab511ae1916a7ea3bd0d0328c153bc462` |

**Signaturkompatibel mit der ursprünglichen 0.3.0:** deren tatsächlich veröffentlichte APK wurde mit `apksigner` geprüft; Zertifikat und Paket stimmen überein, Versionscode 12 ist höher als 4. Ein konkreter Updateversuch auf dem A55 wurde nicht durchgeführt.

**Kein direktes Update von den veröffentlichten 0.4.3-/0.4.4-APKs.** Android
verlangt für Updates desselben Pakets dasselbe Zertifikat oder eine gültige
Signierhistorie. Hier wird keine Schlüsselrotation behauptet. Eine vorhandene
Installation mit dem Zertifikat der 0.4.5 kann bei niedrigerem Versionscode
aktualisiert werden; diese konkrete Installation wurde nicht auf Hardware geprüft.

Paket bleibt `de.bonsai.offline.debug`, Versionscode ist 12. Ein abweichendes
Zertifikat erlaubt keine parallele Installation desselben Pakets. Die bestehende
App nicht vorschnell deinstallieren: dabei gehen private Chats, Einstellungen
und die interne Modellkopie verloren. Es gibt keinen automatischen Datentransfer
zwischen den verschiedenen Signieridentitäten.

## Schlüssel aufbewahren und APK prüfen

Der Keystore und Zugangsdaten bleiben außerhalb von Git, CI und Release-Assets.
Für spätere Updates derselben Signieridentität genau diesen Keystore sichern und
wiederverwenden. Alias des bereitgestellten Schlüssels: `androiddebugkey`, Format
PKCS12. Es wird kein neuer privater Schlüssel erzeugt.

Android SDK Build-Tools 35.0.0:

```sh
apksigner sign --ks /privater/pfad/Bonsai-original-debug.keystore \
  --ks-key-alias androiddebugkey --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out Bonsai-Offline-0.4.5-arm64-CPU-Vulkan.apk app-release-unsigned.apk
apksigner verify --verbose --print-certs Bonsai-Offline-0.4.5-arm64-CPU-Vulkan.apk
zipalign -c -P 16 -v 4 Bonsai-Offline-0.4.5-arm64-CPU-Vulkan.apk
```

Passwörter interaktiv oder über eine geschützte Umgebungsvariable übergeben;
nicht in Repository-Dateien oder öffentliche Buildlogs schreiben. Vor einer
Veröffentlichung Zertifikatsfingerabdruck, Versionscode, Paket, Signatur und
Dateiprüfsumme kontrollieren. Die Signatur beseitigt keine Samsung-/Play-Protect-
Installationswarnungen garantiert.
