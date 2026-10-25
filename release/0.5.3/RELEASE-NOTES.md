# Bonsai Offline v0.5.3

Neues App-Icon und Zusammenführung aller noch offenen Quellbranches.

- Adaptives Bonsai-Icon in Violett/Lavendel auf dunklem Grund, passend zur
  bestehenden Chatoberfläche. Runde und abgerundete Launcher-Masken erhalten
  die vollständige Silhouette. Android 13+ unterstützt systemgefärbte Icons.
- Eigene transparente Bonsai-Silhouette für Vordergrundbenachrichtigungen.
- Release-Nachweise aus PR #25 integriert; Konflikt mit der aktuellen
  Dateninfrastruktur-Roadmap aufgelöst. Die Historien der bereits übernommenen
  Suchtool- und Modellladebranches sind zusammengeführt.
- Der Suchtool-Hotfix aus 0.5.2.1, Offline-Wissenspakete, lokales Modellladen,
  Chatverlauf und anpassbare Systemprompts bleiben enthalten.

Version **0.5.3**, Versionscode **19**, Paket `de.bonsai.offline.debug`, ARM64.
CPU- und CPU/Vulkan-Release verwenden den bisherigen bereitgestellten
Release-Schlüssel. Bei passender bisheriger Signieridentität kann die APK über
0.5.2.1 installiert werden. Modellgewichte und Wissenspakete bleiben separate
Importe; keine Internetberechtigung in der App.

Der eigenständige `downloads`-Branch ist eine Ablage bereits signierter
Release-Dateien mit unabhängiger Git-Historie und bleibt als Downloadablage
bestehen. Alle Quellbranches werden in `main` aufgenommen.

Die Veröffentlichung enthält beide signierten APKs, SHA256-Prüfsummen,
Signaturprotokoll, Updatevergleich und vollständigen Buildnachweis.

Prüfung: je 175 Release-Testfälle für CPU und Vulkan, keine Fehler; zwei Tests
mit externen realen Datenpaketen wurden jeweils übersprungen. Release-Lint ohne
Fehler, Host-/Java-/Icon- und Kernelprüfungen bestanden. Signaturen v2/v3,
16-KiB-ZIP-/ELF-Ausrichtung, unveränderte APK-Nutzdaten nach dem Signieren sowie
Paket, Zertifikat und höherer Versionscode gegenüber den tatsächlich
heruntergeladenen 0.5.2.1-APKs sind geprüft.

[CPU/Vulkan-Build und Prüfungen](https://github.com/interfurz/bonsai-offline-a55/actions/runs/40175307043)
und [Integrations-PR #29](https://github.com/interfurz/bonsai-offline-a55/pull/29).
Die Bauinputs sind bytegleich mit dem gemergten `main`-Stand; Quellcommit,
Branch-Spitzen und genaue Artefakthashes stehen in den JSON-Nachweisen. Host-/Robolectric-Tests ersetzen keine physische A55-Prüfung,
keinen Geräteupdateversuch und keine echte Modellinferenz auf dem Zielgerät.
