# Bonsai Offline v0.5.4

Neuer Release auf Basis von `main` mit Cache-, CPU-, Chat- und Offline-Werkzeug-Verbesserungen.

- KV-Cache nach abgeschlossenen Antworten weiterverwenden; aufgeschobene Tool-Bereinigung für CPU-Repacking und CPU-Referenzpfad.
- CPU-Repacking standardmäßig aktiv und Prefill-Batches mit 512 Tokens.
- Überarbeitete Chatoberfläche mit animiertem Eingabefeld, kompakter Reasoning-Vorschau und Markdown-Antworten.
- Offline-SymPy für symbolische Mathematik in einem separaten App-Prozess; SymPy und mpmath werden mitgeliefert.
- Persönliche Daten und Erinnerungen lokal verwalten, durchsuchen sowie exportieren und importieren.
- Ältere installierte Wissenspaket-Versionen gezielt aktivieren und optionales Titel-/Abschnittsreranking einschalten.

Version **0.5.4**, Versionscode **20**, Paket `de.bonsai.offline.debug`, ARM64.
Basis: `main` bei `2923cf890e4714155b004ff12057408ca4352502` (PR #33).
Die App benötigt keine Internetberechtigung. Modellgewichte und Wissenspakete sind weiterhin separate Importe.

CPU- und CPU/Vulkan-APKs werden in der Arbeitsumgebung aus Quellcode gebaut und geprüft. Keine GitHub Actions und keine übernommenen CI-Buildartefakte.

Beide APKs sind mit dem bisherigen Release-Schlüssel signiert. Paket und
Zertifikat stimmen mit den tatsächlich heruntergeladenen 0.5.3-APKs überein;
Versionscode 20 ist höher als 19. Ein Update über 0.5.3 ist damit hinsichtlich
Paketkennung, Signatur und Versionscode kompatibel.

Prüfung: je **203 Release-Testfälle** für CPU und Vulkan, davon **201 bestanden**
und zwei wegen fehlender externer realer Datenpakete übersprungen; keine Fehler.
Je 37 Lint-Warnungen, keine Lint-Fehler. Zusätzlich zwölf Python-Tests,
Host-/Runtime-/Icon-Prüfungen und CPU-Kernelvergleiche bestanden.
Signaturen v2/v3, 16-KiB-ZIP-/ELF-Ausrichtung und unveränderte APK-Nutzdaten nach
dem Signieren sind geprüft. App-Minimum bleibt Android 9 / API 28.

Die Veröffentlichung enthält beide signierten APKs, `SHA256SUMS.txt`,
`build-info.json`, `signature-verification.txt`, `update-compatibility.json`
und `validation-evidence.zip` mit Test- und Buildnachweisen.
Der zusätzliche eingebettete Python-/SymPy-Laufzeitumfang erhöht die APK-Größe
gegenüber 0.5.3; Modellgewichte sind weiterhin nicht enthalten.

Host-/Robolectric-Tests ersetzen keine physische A55-Prüfung, keinen Geräteupdateversuch und keine echte Modellinferenz auf dem Zielgerät.
