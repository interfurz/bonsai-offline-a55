# Bonsai Offline 0.3.1 — vollständiges CPU/Vulkan-Update

## Umfang

Diese Version führt CPU und Vulkan aus der bisherigen 0.3.0-App fort. Sie ist kein CPU-only-Testbuild. Die Android-Release-Konfiguration ist nicht debuggable; der bestehende Paketname `de.bonsai.offline.debug` bleibt aus Update-Kompatibilitätsgründen unverändert. Der Namenssuffix ist historisch und kein Hinweis auf die neue Build-Konfiguration.

Änderungen:
- Kernel-Optimierung ohne app-eigene Temperatursperre: Start bei jedem Android-Thermalstatus sowie Fortsetzung bei Erwärmung. Androids thermischer Geräteschutz bleibt unverändert.
- Temperaturstatus vor/nach Messungen und pro Profil bleiben protokolliert (`thermal_policy: observe_only`). Rechenprüfungen, Stabilitätsgrenzen und manueller Abbruch bleiben aktiv. Ein warm gemessenes Profil ist keine Garantie für kalte oder wechselnde Bedingungen.
- Synchron abgewiesene Chatnachrichten behalten den exakten Entwurf, statt ihn vor der Annahme zu löschen.
- Die differentielle Kernel-Prüfung akzeptiert keine Referenzen ohne Top-Token-Daten.

## Installation als Update

Die endgültig veröffentlichte CPU/Vulkan-APK über die vorhandene Bonsai-0.3.0-App installieren. Nicht vorher deinstallieren: Die privaten Chats, Einstellungen und Modellkopie sollen im vorhandenen App-Speicher bleiben.

Veröffentlichungsgates:
- Paketname identisch zu 0.3.0: `de.bonsai.offline.debug`.
- versionCode 6, versionName `0.3.1`.
- Signierzertifikat identisch zur tatsächlichen 0.3.0-CPU/Vulkan-APK: SHA256 `ce1a585cbd3b51dcb446c50814122d5c68832a6a052f2c6f139cf2e0c44208db`.
- Der ursprüngliche private Schlüssel bleibt lokal und wird weder ins Repository noch zu GitHub Actions übertragen. Actions baut eine ausgerichtete, unsignierte APK; die endgültige Signierung erfolgt lokal.

Der vorherige Release `v0.3.1-cpu-test` ist nicht update-kompatibel und darf nicht zum Aktualisieren der alten CPU/Vulkan-App benutzt werden. Seine Signatur ist eine andere; seine frühere Installationsbeschreibung wurde korrigiert.

## Verifikation

Build-, Test-, Signatur- und Paketierungsbelege sowie die genaue Prüfsumme gehören zur endgültigen Veröffentlichung. Dokumentation und vorhandene Tests allein sind kein Nachweis eines fertigen CPU/Vulkan-Builds.

Ohne angeschlossenes Galaxy A55 bleiben die physische Installation und die CPU-/Xclipse-Vulkan-Laufzeit ungetestet. Der zuvor gemeldete Zeichensalat auf dem A55 wird nicht als behoben behauptet. Eine vollständige APK mit beiden Backends ist keine Garantie für die Stabilität eines konkreten GPU-Treibers.
