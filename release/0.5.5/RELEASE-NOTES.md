# Bonsai Offline v0.5.5

Neuer Release aus `main` nach der Integration von `perf/decode-throughput`.
Version **0.5.5**, Versionscode **21**, Paket `de.bonsai.offline.debug`, ARM64.

## Änderungen

- Verlustfreier experimenteller PTQ-Ladecache mit Vierzeilen-GEMV, Shape-/Upload-Prüfungen und sicheren Fallbacks.
- Zusätzliche Scalar-, SIMD- und kompilierte/emulierte ARM-Korrektheitstests; kontrollierter Fixed-token-Decode-Benchmark mit vollständigem Logitvergleich.
- Experimentelle persistente CPU-Worker und weitere getrennte Kernel-/Layoutvarianten mit reproduzierbaren Messberichten und Profilierung.
- Build- und Profilkennungen unterscheiden die aktivierten Varianten, damit Optimierungsergebnisse nicht zwischen inkompatiblen Konfigurationen wiederverwendet werden.

**Die experimentellen Schalter `bonsaiPtqRecode`, `bonsaiPtqPlanar`,
`bonsaiFastTernary` und `bonsaiPersistentCpuPool` bleiben in den regulären APKs aus.**
Der neue Cache benötigt bei Aktivierung zusätzlichen Speicher. Dieser Release
behauptet keinen bestätigten 2×-Speedup auf dem Galaxy A55 und aktiviert keine
produktive spekulative Decodierung. Hostmessungen und ARM-Emulation ersetzen
keine Leistungsabnahme auf dem Zielgerät.

## Builds und Update

CPU und CPU/Vulkan werden getrennt als nicht debuggbarer Release gebaut.
GitHub Actions erstellt und prüft die unsignierten APKs; die Signierung erfolgt
anschließend lokal mit dem bisherigen Release-Schlüssel. Keystore und Passwort
werden weder an CI übertragen noch veröffentlicht. Build-, Test-, Signatur- und
Prüfsummennachweise werden mit den endgültigen APKs bereitgestellt.

Die vorgesehene Updateidentität bleibt `de.bonsai.offline.debug` mit dem
bisherigen Zertifikat SHA256
`d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d`.
Versionscode 21 liegt über dem Versionscode 20 von 0.5.4. Die tatsächlichen
APK-Identitäten und Signaturen müssen vor Veröffentlichung geprüft sein.
Ein physischer Installations-/Updateversuch auf dem A55 wird nicht behauptet.

Die App benötigt weiterhin keine Internetberechtigung. Modellgewichte und
Wissenspakete bleiben separate Importe. Die bestehende Installation nicht
vorschnell deinstallieren, da dabei lokale Daten gelöscht werden.
