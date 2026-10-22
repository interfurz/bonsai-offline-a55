# A55-Echtwelttest: Debugausgaben und persönliche Nutzererfahrung

Stand: 22. Oktober 2026, erneute Prüfung nach #15/#17. Dieser Plan folgt auf die
[Projektanalyse/Roadmap](ROADMAP.md). Geprüfter `main`-Stand:
`efcd87a0b02fbc0325fbc30a20ce9b1dac291a15` (v0.5, nach #16/#18). Neben Encoder (#12), Wikipedia (#13)
und Wikidata (#14) sind jetzt die Chatoberfläche mit appweiten Lichtzuständen
(#15) und optischen 16-dp-Iconvarianten (#17) integriert.
**Ein physischer A55-Test wurde hier noch nicht ausgeführt.**

Es liegen weiterhin keine neuen A55-Logs oder abgeschlossenen persönlichen
Testdurchläufe vor. Ein im Chat bereitgestellter Nutzer-Screenshot zeigt
„Was geht“ und die statische Wartezeile „Bonsai denkt nach …“; der Nutzer wünscht
dort aktuellen Thinking-Text beziehungsweise Prefill-Fortschritt und Tempo.
Das ist ein konkreter UX-Befund zur Warteanzeige, aber ohne APK-/Modellhash,
Zeitmessung oder Antwortabschluss kein Leistungs- oder Geräteabnahmebeleg.
Die Renderaufnahmen/138 Tests aus der Iconvalidierung
sind Hostbelege mit Testinhalten; sie ersetzen deinen Durchlauf nicht.
Die neue CPU/Vulkan-APK ist inzwischen lokal gebaut, signiert und binär geprüft;
[Buildnachweis](release/0.5/build-info.json) und
[Updatevergleich](release/0.5/update-compatibility.json) identifizieren genau das
Artefakt. Der lokale Build ist kein physischer A55-Test.

Ziel ist ein gemeinsamer Befund aus **deinen echten Aufgaben, deinem Eindruck
und dem dazu passenden Geräteprotokoll**. Ein schneller Benchmark allein reicht
nicht. Zuerst prüfen wir, ob die App im Alltag funktioniert; anschließend
grenzen wir auffällige Wartezeiten, falsche Antworten oder Abbrüche technisch ein.

## 1. Ablauf und Zuständigkeiten

1. **Vorbereitung:** Test-APK und Modelle eindeutig zuordnen, bestehende Daten
   erhalten, Logs einschalten. Der betreuende Agent liefert ein ausgefülltes
   Artefaktmanifest; du musst keine Git-Hashes aus der Versionsanzeige erraten.
2. **Erste kurze Sitzung:** Du bearbeitest drei eigene Aufgaben und die festen
   Basistests unten. Wir sammeln die unveränderte Antwort und deinen Eindruck.
3. **Erste Auswertung:** Agent ordnet jeden Befund der Testfall-ID und Zeit zu;
   höchstens die wichtigsten reproduzierbaren Probleme werden danach vertieft.
4. **Gezielte zweite Sitzung:** Nur nötige Vergleichsläufe, etwa CPU skalar,
   Vulkan, Cache oder RAG. Keine große Profilmatrix, bevor die Basis korrekt ist.
5. **Abschluss:** belegte Gerätefunktion, UX-Probleme, technische Fehler und
   wegen fehlender Daten nicht ausführbare Tests getrennt dokumentieren;
   konkrete Roadmap-Aufgaben mit Log-/Antwortbelegen verknüpfen.

Du lieferst Bedienung und ehrliche Beobachtungen; der Agent übernimmt APK-/
Paketprüfung, Logzuordnung und Ursachenanalyse. Ein technisch korrektes Ergebnis
kann sich für dich trotzdem unbrauchbar langsam oder umständlich anfühlen.
Beides wird festgehalten, ohne die eine Beobachtung durch die andere zu ersetzen.

## 2. Voraussetzungen und Testumfang

### Testartefakte vor Beginn festhalten

| Feld | Vor der Sitzung ausfüllen |
| --- | --- |
| Sitzungs-ID | `A55-YYYYMMDD-HHMM` |
| APK | Dateiname, SHA256, Quellcommit, CPU/Vulkan-Build, VersionCode, Signaturfingerabdruck |
| Telefon | genaue Modellnummer, 8 GB RAM, Android-/One-UI-/Buildstand |
| Bonsai | Datei, SHA256, Bytes; bevorzugt offizielle PQ2_0, kein unmarkierter Override |
| Runtime | PrismML `e3de715…` **plus EmbeddingGemma-2-Backport** |
| Encoder | `google/embeddinggemma-2`, tatsächliche GGUF-Datei/-Revision/-Hash; nicht 300M |
| Wissen | Paket-/Dumpdatum, DB-/IVF-Hashes, Profil, Chunkanzahl, echte Quelle oder Fixture |
| Einstellungen | CPU/GPU, Threads, Kontext, Antwortlimit, Microbatch, FA, KV, GBNF, Prefix-Warmup |
| Systemprompt (Nachfolgebuild) | Standard/angepasst und `system_prompt_sha256` aus dem Chat-Benchmark; private Prompttexte nicht in öffentliche Logs kopieren |
| Umgebung | Akkustand, Laden ja/nein, Temperaturgefühl, Energiesparen, Flugmodus, sonstige aktive Apps |

Die Versionsanzeige **0.4.5 reicht nicht zur Identifikation**: Die veröffentlichte
APK ist älter als die drei Integrationen. Vor dem Update den Signaturvergleich
gemäß [SIGNIEREN.md](SIGNIEREN.md) durchführen. Keine Deinstallation und kein
`pm clear`: private Chats, Gedächtnis und Modellkopien sollen erhalten bleiben.
Für ein inkompatibles Zertifikat zuerst einen geprüften Export-/Migrationsweg
bereitstellen; ein beliebiges neu signiertes Debug-APK ist kein Ersatz.

Für den angeforderten Folgerelease gilt Appversion **0.5**, Versionscode **13**.
Der bereitgestellte Release-Keystore entspricht der geprüften 0.4.4-Signatur
(`d45ceaf8…`), nicht der 0.4.5-/0.3.0-Signatur (`ce1a585c…`). Den tatsächlichen
APK-/Buildhash des veröffentlichten v0.5-Artefakts in das Sitzungsmanifest
übernehmen; die neue Versionsnummer allein ist weiterhin kein Gerätebeleg.

### Drei ausführbare Teststufen

| Stufe | Was geht? | Was darf daraus nicht abgeleitet werden? |
| --- | --- | --- |
| A – bestehende Release-App, Modell, USB/ADB | Alltag, Modelltest, `BonsaiTurn`, Chat-/Benchmarkexport, RAM/Thermik, Lebenszyklus | Keine RAG-/Wikidata-Freigabe ohne lokale Daten; ältere APK beweist keine neue Integration |
| B – aktueller integrierter Build + vorbereitete kleine Daten | zusätzlich FTS/IVF/Encoder und strukturiertes Tool | Fixtures sind keine echte Wikipedia und kein Nachweis für Millionen Chunks |
| C – freigegebenes echtes Paket | fachliches RAG mit Originalquellen, reale Skalierung | Kleine Pilotdaten beweisen noch keine Vollindexleistung |

Der normale Release hat aktuell **keinen Wissenspaketimport**. `files/rag` und
`files/wikidata` sind privater Appspeicher; `adb push` kann sie nicht einfach
beschreiben. Debug-`run-as` aus der Entwickleranleitung funktioniert nur bei
einem wirklich debuggbaren Build, nicht wegen des Paketsuffixes `.debug`.
Wenn die Daten nicht sicher bereitgestellt sind, R-/W-Tests als **blockiert**
markieren. Den fehlenden Produktimport als eigenen UX-/Roadmapbefund aufnehmen.
Nicht zur Testvorbereitung die bestehende Installation löschen oder private
Datenzugriffe auf einem nicht debuggbaren Release voraussetzen.

Für quantitative Durchläufe: Flugmodus, WLAN und mobile Daten tatsächlich aus,
USB für ADB erlaubt; keine Downloads während des Tests. Start ohne Energiesparen,
Gerät zunächst abkühlen lassen. USB-Laden und Akkustand protokollieren; nicht
zwischen zwei verglichenen Läufen unbemerkt wechseln. Keine erzwungene Hitze
oder thermischen Schutzabschaltungen. Bei Android-Temperaturwarnung pausieren.

## 3. Debugaufzeichnung auf dem Windows-Laptop

Voraussetzung: Android Platform Tools, `adb` im PATH, USB-Debugging am A55
aktiviert und dieser Rechner am Gerät freigegeben. Kein Root und kein App-Debug-
Build für Logcat nötig. Bei mehreren Geräten allen Befehlen `adb -s SERIENNUMMER`
voranstellen; Gerätenummer nicht in veröffentlichte Berichte übernehmen.

### 3.1 Sitzung anlegen und Ausgangszustand sichern

PowerShell im Repository-Stamm:

```powershell
adb devices -l
$BonsaiPackage = 'de.bonsai.offline.debug'
$BonsaiSession = Join-Path (Get-Location) ('work/a55/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $BonsaiSession | Out-Null
$BonsaiSession | Set-Content -Encoding utf8 work/a55/active-session.txt

Get-Date -Format o | Set-Content -Encoding utf8 "$BonsaiSession/laptop-start.txt"
adb shell date | Set-Content -Encoding utf8 "$BonsaiSession/device-start.txt"
adb shell getprop ro.product.model | Set-Content -Encoding utf8 "$BonsaiSession/model.txt"
adb shell getprop ro.build.fingerprint | Set-Content -Encoding utf8 "$BonsaiSession/build-fingerprint.txt"
adb shell getprop ro.build.version.release | Set-Content -Encoding utf8 "$BonsaiSession/android.txt"
adb shell dumpsys package $BonsaiPackage | Set-Content -Encoding utf8 "$BonsaiSession/package.txt"
adb shell dumpsys battery | Set-Content -Encoding utf8 "$BonsaiSession/battery-before.txt"
adb shell dumpsys thermalservice | Set-Content -Encoding utf8 "$BonsaiSession/thermal-before.txt"
adb shell dumpsys meminfo $BonsaiPackage | Set-Content -Encoding utf8 "$BonsaiSession/mem-before.txt"
adb shell cat /proc/meminfo | Set-Content -Encoding utf8 "$BonsaiSession/system-memory-before.txt"
```

`work/` ist bereits gitignored. Logs und Feedback dort lokal sammeln. Samsung
kann einzelne Dumpsysdetails sperren; dann Fehlermeldung sichern und Feld als
nicht verfügbar kennzeichnen. Weder Root noch Umgehungen voraussetzen.

### 3.2 Turn-Logs in der App aktivieren

Aktuelle UI laut Quellcode:

1. Über **Menü öffnen → Modell** das Modell laden. Alternativ öffnet **Plus**
   links in der Chatbar Modell/Einstellungen. Zurück zum Chat per Dialog-Schließen.
2. **Menü öffnen → Leistung → Benchmark öffnen → Benchmark starten**.
3. Im Dialog **Benchmark-Modus → Messoptionen** wählen.
4. **Turn-Statistik in Logcat (BonsaiTurn)** einschalten und **Übernehmen**.
5. Für die erste Sitzung: Prefix-Warmup an, Flash Attention aus, Q8-KV aus;
   CPU-Microbatch 32, vier Threads, Kontext 4096, Antwortlimit 512, GBNF aus.
   Bestehende abweichende Einstellungen zuerst dokumentieren. Kein Optimizerlauf
   mitten in einem Vergleich: Er kann das aktive Profil ändern.

Der Weg läuft über den Benchmarkdialog, startet beim Öffnen der Messoptionen
aber noch keinen Benchmark. Wenn die installierte APK diesen Dialog nicht hat,
das als Versions-/Bedienbefund notieren und ihren vorhandenen Export benutzen.
Aktuell gibt es keine unteren Modell-/Leistungs-Tabs: Beide Ansichten sind
native Pop-ups aus der Seitenleiste. Die Plus-Aktion öffnet keinen Wissensimport.
Nach Profiländerungen den tatsächlich angewandten Backendwert prüfen: Ein
automatischer Fallback kann vom angeforderten Vulkanprofil abweichen.

### 3.3 Logcat in einem zweiten PowerShell-Fenster starten

Ebenfalls im Repository-Stamm:

```powershell
$BonsaiSession = (Get-Content work/a55/active-session.txt).Trim()
adb logcat -b main -b system -b crash -v threadtime `
  'BonsaiTurn:D' 'BonsaiTest:I' 'AndroidRuntime:E' `
  'ActivityManager:I' 'ActivityTaskManager:I' 'lmkd:I' `
  'lowmemorykiller:I' 'libc:F' 'DEBUG:F' '*:S' `
  | Out-File -Encoding utf8 "$BonsaiSession/logcat.txt"
```

Dieses Fenster bis Sitzungsende laufen lassen; mit **Strg+C** beenden. Die
Aufzeichnung bewusst nicht auf eine PID begrenzen: Nach Prozessabbruch ändert
sich die PID, und System-/LMKD-Meldungen laufen außerhalb des Appprozesses.
Der Ringpuffer wird nicht mit `logcat -c` gelöscht; Testmarker trennen neue
Ereignisse von vorherigen. Der Tagfilter erfasst nicht garantiert sämtliche
nativen Runtimeausgaben. Bei einem Fehler erst zeitnah den Zusatzdump unten
sichern, bevor Meldungen aus dem Ringpuffer verschwinden.

Im ersten Fenster vor/nach jedem Fall einen Marker setzen, beispielsweise:

```powershell
adb shell log -p i -t BonsaiTest 'C01-BEGIN'
# C01 auf dem Handy durchführen und Feedback notieren.
adb shell log -p i -t BonsaiTest 'C01-END'
```

Nach dem ersten vollständigen Chatturn prüfen, ob `BonsaiTurn` im Log erscheint.
Falls nicht: Checkbox/Buildstand und Samsung-Logausgabe prüfen; fehlende Zeile
als **fehlende Evidenz** dokumentieren, nicht als Null-Latenz. Kein bloßes Warten
auf dauerhaft fehlende Logs. UI-/JSON-Export bleiben als Ersatzbelege verwendbar.

### 3.4 Zustand nach Tests und Zusatzdaten bei Fehlern

Erst nach einem Zeitmesslauf, damit Dumpsys die Messung nicht beeinflusst:

```powershell
adb shell dumpsys meminfo $BonsaiPackage | Set-Content -Encoding utf8 "$BonsaiSession/mem-after.txt"
adb shell dumpsys battery | Set-Content -Encoding utf8 "$BonsaiSession/battery-after.txt"
adb shell dumpsys thermalservice | Set-Content -Encoding utf8 "$BonsaiSession/thermal-after.txt"
adb shell dumpsys activity exit-info $BonsaiPackage | Set-Content -Encoding utf8 "$BonsaiSession/exit-info.txt"
```

Bei Crash, Zeichensalat, OOM oder Hänger zusätzlich unmittelbar:

```powershell
adb logcat -d -b main -b system -b crash -v threadtime `
  | Out-File -Encoding utf8 "$BonsaiSession/error-window.txt"
```

Der Zusatzdump kann Meldungen anderer Apps enthalten. Vor dem Teilen auf den
relevanten Zeitraum/Appbezug begrenzen und persönliche Inhalte entfernen;
Zeitstempel, Fehler, Einstellungen und IDs erhalten. `BonsaiTurn` protokolliert
keine regulären Prompt-/Antworttexte, aber Fehlertexte können Pfade enthalten.
Benchmark-JSON enthält die Testfragen und Antworten. Normale Antworten deshalb
separat über **Antwort kopieren** sichern; Debuglogs ersetzen das UX-Protokoll nicht.

### 3.5 Was die vorhandene Diagnose wirklich misst

| Beleg | Vorhandene Felder / Aussage | Grenze |
| --- | --- | --- |
| `BonsaiTurn` | `ttft_ms`, `turn_ms`, `prefill_ms`, `tokens_per_second`, `rounds`, KV-Reuse/Decode, Reasoning, Cleanup/Commit | Abschluss-/Fehlerlog, kein Live-Heartbeat; native Tokens/s umfassen auch interne Generierung, nicht nur sichtbare Wörter |
| Native Runde in `rounds` | `cache_reset_reason`, `cache_resets`, `cached_before`, `cached_tokens`, `kv_positions`, `dropped_turns`, Microbatch/FA/KV | Top-Level-Werte können aggregiert sein; Cachezustand pro Runde plus finalen Commit prüfen |
| Modelltest | Hash/Bytes/Metadaten, zwei feste Fragen/Antworten, Rechenfrage korrekt ja/nein | Kein vollständiger Qualitäts- oder Gerätebelastungstest |
| 5-Turn-JSON | Fragen/Antworten, Profil, Modellhash, TTFT/Turnzeiten, Temperatur/Status pro Turn, RSS-Peak alle 250 ms | USB/Laden und Cachezustand separat notieren; kurze Peaks/GPU-Speicher können fehlen |
| Dumpsys meminfo | PSS/RSS-/Prozessübersicht zum Aufnahmezeitpunkt | Keine garantierte Spitze während einer normalen RAG-Query; GPU-/Treiberanteile ggf. unvollständig |
| Exit-Info / Logcat | Hinweise auf Prozessende, Java-/native Fehler, ANR/LMKD soweit verfügbar | Ursache nur behaupten, wenn der entsprechende Beleg vorhanden ist |
| Eigene Beobachtung | verständlich, nützlich, lange Pause, Ruckeln, Hitzegefühl, Vertrauen | Akkutemperatur ist nicht SoC-Temperatur; Gefühl allein beweist keine thermische Drosselung |

Aktuell fehlen im App-Export getrennte Encoder-Lade-/Encode-/Free-, FTS-, IVF-
und RRF-Zeiten, ein verbindlicher Toolnamen-/Argumenttrace sowie ein vollständiger
Wissenspaket-/Profilhash. Zwei Modellrunden beweisen nicht, welches Tool lief;
`43077` allein beweist nicht `calc`. Such-/Wikidata-Quellenfooter und kontrollierte
Fixtureinhalte liefern zusätzliche Indizien. Für einen direkten Nachweis ist
gezielte Instrumentierung ein **späterer Implementierungsauftrag**, kein bereits
vorhandenes Feature dieses Plans. Ohne diese Daten keine präzisen Teilzeit- oder
Untersekundenaussagen aus der Gesamtdauer ableiten.

## 4. Erste Sitzung: deine Aufgaben und feste Basistests

### U01–U03: zuerst ungeleitete echte Nutzung

Wähle drei Dinge, für die du den Assistenten tatsächlich benutzen möchtest,
zum Beispiel eine Erklärung, eine praktische Frage und eine Rechnung. Verwende
deine normale Formulierung, zunächst ohne Toolnamen oder erwartete Antwort.
Vorher jeweils notieren: Was wäre eine brauchbare Antwort, und wie lange würdest
du dafür im Alltag warten? Danach vollständige Antwort und Feedback aufnehmen.
So messen wir nicht nur, ob die App eine Entwickleranweisung befolgen kann.

### Wiederholbare Basistests

Jeder Fall erhält BEGIN/END-Marker und einen Feedbackeintrag. GBNF zunächst aus;
bei fehlerhaftem Toolformat denselben Fall später in einem neuen Chat mit GBNF
an wiederholen. Gewöhnlicher Chat hat keinen vom Nutzer fest einstellbaren Seed;
abweichende Formulierungen sind deshalb nicht automatisch ein Rechenfehler.

| ID | Handlung / Eingabe | Erwartung / wichtige Beobachtung |
| --- | --- | --- |
| C01 | **Modell → Modell prüfen → Test starten**, anschließend Dialog neu öffnen und **Protokoll kopieren** | Rechenantwort exakt `4`, kurze deutsche Baumerklärung lesbar; Hash/Backend/Bytes sichern. Gelingt dies nicht, weitere Qualitäts-/Geschwindigkeitstests pausieren und Datei/CPU prüfen. |
| C02 | Neuer Chat: „Erkläre in drei kurzen Sätzen, warum Pflanzen Licht brauchen.“ | Verständliches Deutsch, keine internen Tags/kaputten Zeichen; erste sichtbare Antwort und Ende getrennt beobachten. |
| C03 | Derselbe Chat: „Und was passiert bei zu wenig Licht?“ Danach: „Fasse beide Antworten in zwei Sätzen zusammen.“ | Bezug auf vorige Turns, kein grundloser vollständiger Cacheverlust. Prefix-Warmup kann schon Turn 1 wiederverwendete Tokens geben. |
| T01 | Neuer Chat: „Berechne 173 * 249 mit calc und nenne das Ergebnis.“ | `43077`; Toolformatfehler, Wartezeit und Quellen der Behauptung gesondert notieren. Richtiges Ergebnis allein beweist Toolausführung nicht. |
| T02 | „Berechne 1 / 0 mit calc.“ | Kontrollierter Fehler/Erklärung, kein erfundener endlicher Wert, kein Appabsturz. |
| T03 | „Merke dir unter dem Schlüssel a55_testwort den Wert Bergahorn.“ App normal schließen/öffnen, **neuer Chat**: „Rufe den Schlüssel a55_testwort ab.“ | Exakt `Bergahorn`, chatübergreifend persistent. Nur künstlichen Testwert verwenden; die aktuelle App hat kein allgemeines Memory-Löschtool. |
| T04 | Neuer Chat: „Rufe den Schlüssel a55_nicht_gesetzt_20261022 ab.“ | Fehlenden Eintrag klar melden; falls Schlüssel schon existiert, vorher vereinbarten neuen Testschlüssel wählen. |
| L01 | „Schreibe eine längere Erklärung in zehn Absätzen über Wälder.“ Sobald Streaming beginnt **Abbrechen**, danach „Was ist 2 + 2?“ | Abbruch wird wirksam, UI bleibt bedienbar, keine halbe Antwort als fertiger Turn gespeichert, Folgefrage funktioniert. Abbruchreaktion grob messen. |
| L02 | Dieselbe lange Aufgabe; 30 Sekunden zu einer anderen App wechseln, zurückkehren | Lauf wird fortgesetzt/abgeschlossen und Antwort gespeichert; kein doppelter Turn. Vordergrundbenachrichtigung/WakeLock prüfen. |
| L03 | Lange Aufgabe starten, Bildschirm 60 Sekunden ausschalten, wieder öffnen | Ergebnis oder nachvollziehbarer Zustand, kein Verlust gespeicherter Chats; Benachrichtigungs-Abbruch in separatem Durchlauf prüfen. |
| X01 | Chatliste öffnen, zwischen Testchats wechseln, einen Testchat archivieren und wiederherstellen | Bedienweg verständlich, Chat erhalten, neue Antwort bezieht sich auf richtigen Verlauf. Kein Löschen echter Chats für den Test. |
| X02 | Vor dem Modellladen einen Entwurf schreiben, über Plus das Modell-Pop-up und über Seitenmenü Leistung öffnen/schließen; danach Tastatur öffnen und längeren Text eingeben | Entwurf erhalten, Senden ohne Modell gesperrt; Modell-/Leistungsaktionen und Chatbar werden nicht von Tastatur/Systemleisten verdeckt. Kein Tabwechsel voraussetzen. |
| X03 | Während C02 und L01 Lichtzustände an Chatbar, neuester Antwort und geöffnetem Pop-up beobachten; einen erfolgreichen Lauf und einen Abbruch vergleichen | Lichtzustand und Textstatus stimmen überein. Nach Erfolg kurz Done, ungefähr 1,8 s danach Idle; nach Fehler/Abbruch kein grüner Erfolgshinweis. Licht ist kein Fortschritts-/Inferenznachweis. |
| X04 | Im System Animationen ausschalten, App erneut öffnen; separat Energiesparen einschalten und später Ausgangseinstellungen wiederherstellen | Dekorative Bewegung ruht, Bedienung/Status/Abbruch funktionieren weiter. Dies ist eine eigene UX-Sitzung; Energiesparwerte nicht mit normalem Latenzprofil vergleichen. |
| X05 | Helles/dunkles Theme und große Systemschrift (bis 200 %) prüfen; Metadaten, Kopieren und „Neu generieren“ bedienen | 16-dp-Metadaten unterscheidbar/lesbar, Kopieren funktioniert, neue Antwortvariante als eigener Chat erhält Original. Kleine Glyphen dürfen nicht mit kleinen Touchflächen verwechselt werden. |
| X06 | TalkBack aktivieren, Kopfaktionen, Plus, Senden/Stop, Pop-up-Schließen und Antwortaktionen der Reihe nach bedienen | Namen/Fokusreihenfolge verständlich, Status nicht ausschließlich über Farbe vermittelt; keine ungewollten Aktionen. Anschließend eigene normale Bedienkonfiguration wiederherstellen. |

Für L01–L03 ist „keine automatische Wiederaufnahme nach Android-Prozesskill“
der dokumentierte Ist-Zustand. Appwechsel ist kein Prozesskill. Wenn ein Lauf
abbricht, zuerst Exit-Info/Logcat prüfen, bevor der Befund als Modell-/Cachefehler
eingeordnet wird. Erzwungenen Stopp erst in einer getrennten Diagnosesitzung nach
Sicherung aller Antworten testen; er soll Inferenz beenden, nicht fortsetzen.

Seit #15/#17 gehören X02–X06 zur gezielten zweiten UX-Sitzung. Deine spontanen
Eindrücke zu Licht, Lesbarkeit und Bedienelementen schon bei U01–U03 notieren;
nicht erst nach Erklärung der beabsichtigten Gestaltung bewerten.

### P01–P06: Systemprompt und Live-Wartezeile im Nachfolgebuild

Diese Fälle benötigen einen Build aus `feat/editable-system-prompt` oder dessen
späterem Merge. Die bereits veröffentlichte v0.5-APK enthält den Editor nicht.
Der Bedienweg ist **Menü → Modell → Antworten anpassen → Systemprompt bearbeiten**.

| ID | Durchführung | Erwartung und Beleg |
| --- | --- | --- |
| P01 | Eigene Vorgaben um „Antworte in zwei kurzen Sätzen.“ ergänzen; Prompt speichern, neue Frage stellen, App neu öffnen | Vorgabe bleibt gespeichert und beeinflusst die neue Antwort. Tool-Anweisungen bleiben unverändert. Wortlaut/Antwort und persönlichen Eindruck notieren; Modellbefolgung ist kein Hostbeleg. |
| P02 | Tool-Bereich lesen, Entsperren antippen, Warnung abbrechen; erneut entsperren und bestätigen | Vor Bestätigung keine Texteingabe und kein Tool-Reset möglich. Nach Bestätigung bearbeitbar; nach Schließen/Öffnen wieder schreibgeschützt. Prüfen, ob Warnung und Begriffe verständlich sind. |
| P03 | Beide Bereiche ändern, Abbrechen wählen; erneut öffnen. Danach einen Entwurf bearbeiten und Gerät drehen | Abbrechen speichert nichts. Drehung erhält den Entwurf, sperrt den Tool-Bereich erneut; geänderte Tool-Entwürfe benötigen vor dem Speichern die Warnbestätigung. Auch große Schrift und Tastatur prüfen: Felder scrollbar, Speichern/Abbrechen erreichbar. |
| P04 | Vorgabe speichern, im bestehenden Chat `173 * 249` rechnen lassen; Antwort und Cache-Logs erfassen, mit GBNF an/aus wiederholen | Neue Vorgaben ab der nächsten Nachricht; keine alten Promptreste oder sichtbaren Tool-Tags. Ergebnis **43077**, bestehender Verlauf/Gedächtnis erhalten. Während laufender Berechnung kein Speichern. Hosttests simulieren Tool-Ausgaben, die echte Modellwahl bleibt zu prüfen. |
| P05 | Nach bestätigtem Entsperren Tool-Standard und Vorgaben-Standard einsetzen, speichern; Chat-Benchmark vor/nach Anpassung exportieren | Rückkehr zum Standard ohne Neuimport des Modells. `system_prompt_sha256` ändert sich bei geändertem Prompt, `system_prompt_customized` markiert Anpassungen; Vergleichsskript weist unterschiedliche Hashes zurück. Export enthält keinen eigenen Prompttext. |

**P06 – markierte Wartezeile im Chat:** Im Nachfolgebuild dieselbe Frage einmal
mit kaltem Cache und als Folgefrage stellen. Unter „Bonsai“ soll während Prefill
**eine Zeile** mit `Prompt-Cache … % · … Tok/s` erscheinen. Die Rate beruht auf
frisch dekodierten Tokens; der Fortschritt enthält auch Cache-Wiederverwendung.
Vor dem ersten abgeschlossenen Batch steht statt einer erfundenen Rate `… Tok/s`.
Wenn das Modell Thinking ausgibt, erscheint dessen aktueller Text in derselben
Zeile, bei Überlänge das Ende. Ohne Modell-Thinking bleibt es beim tatsächlichen
Arbeitsstatus. Die Endantwort wird normal mehrzeilig dargestellt. Abbrechen,
App-Wechsel und Chatwechsel prüfen: keine alten Gedanken in neuen Antworten,
im gespeicherten Verlauf oder in `BonsaiTurn`. Test mit Screenshot/Zeitmarker
und persönlichem Eindruck dokumentieren; Host-Renderbilder sind Testinhalte.

Für reguläre CPU-/Vulkan-Vergleiche anschließend beide Promptbereiche auf
denselben Standard zurücksetzen. Änderungen an Namen/JSON-Beispielen erzeugen
keine neuen ausführbaren Tools; GBNF und Java-Argumentgrenzen bleiben fest.
Alle sechs Geräte-/UX-Ergebnisse sind bis zur belegten A55-Sitzung **ausstehend**.

## 5. Datenabhängige RAG- und Wikidata-Tests

Diese Fälle nur ausführen, wenn APK und Datenvertrag wirklich passen. Die
technischen mitgelieferten Textfixtures sind **selbst verfasst**, auch wenn sie
über Photosynthese sprechen. Eine erfolgreiche Fixture-Suche als solche benennen.
Echte Wikipedia-Abnahme braucht den Pilotbestand aus ROADMAP M1.1/M1.2.

| ID | Eingabe / Aufbau | Erwartung und Debug-/UX-Beleg |
| --- | --- | --- |
| R01 | „Suche in meinen lokalen Wissensdaten nach Photosynthese und erkläre, wie dabei Sauerstoff entsteht. Nenne deine Quellen.“ | Treffertext stützt Aussage, Quellenfooter/Artikelbezug vorhanden, nach Chatneustart erhalten. Nutzer beurteilt, ob die Quelle verständlich/vertrauenswürdig dargestellt ist. |
| R02 | Neuer Chat, Paraphrase ohne zentrales Stichwort: „Suche lokal: Wie machen grüne Pflanzen mit Licht Energie für ihr Wachstum verfügbar?“ | Bei echtem Encoder semantisch passende Evidenz. Nur FTS-Treffer sind kein Nachweis funktionierender Embeddings. Genaue Quelle/Goldlabel aus Paketmanifest vorab festlegen. |
| R03 | Exakter, im Pilotbestand vorhandener ungewöhnlicher Artikel-/Fachbegriff; Frage vor Sitzung festlegen | FTS kann den Begriff präzise finden; Quelle/Originalchunk vergleichen. Ohne unabhängigen Tooltrace nur End-to-End-Verhalten bewerten. |
| R04 | Zwei Nachfragen zu derselben Quelle, danach langer vorheriger Chat | Zusammenhang bleibt erhalten, keine erfundenen Quellen-IDs, kein Kontextüberlauf. Wiederholte Suche darf Encoder erneut laden; Wartezeit sichtbar bewerten. |
| R05 | Vorab vereinbarte Frage außerhalb des **kontrollierten kleinen Pakets** | Fehlende lokale Evidenz/Abdeckung offen melden; nicht versehentlich eine im kompletten Wikipedia-Bestand beantwortbare Frage als universell unbeantwortbar definieren. |
| R06 | Entwickler stellt separate Testkonfiguration mit fehlendem Encoder/Profil bereit; vorhandenes Paket nicht spontan überschreiben | FTS bleibt nutzbar, Diagnose benennt Fallback. Ob die Warnung für dich in der UI erkennbar ist, separat beurteilen; interne Tool-Warnung allein reicht für UX nicht. |
| W01 | Mit bekanntem Wikidata-Paket: „Nutze wikidata: Was ist die Hauptstadt von Japan, Property P36?“ | Tokio, korrekter lokaler Datenstand/Herkunftsfooter; Sollwert vorab aus genau dieser DB prüfen. |
| W02 | „Nutze wikidata: Wann wurde Albert Einstein geboren, Property P569?“ | Datum samt passender Präzision/Quelle; Datenstand und Beobachtungszeit nicht verwechseln. |
| W03 | Mehrdeutiger, im Testpaket wirklich doppelt belegter Alias | Rückfrage/Kandidaten statt zufälliger Zuordnung; Alias aus DB-Manifest festlegen. |
| W04 | Nur mit beigefügter Wikidata-Fixture: Zähle über Tooloperation `count` Planeten (`Q634`) mit Mondbeziehungen (`P397`, Klasse `Q2537`) | Fixture: Jupiter 2 und Saturn 1 **erfasste** Monde. Diese Zahlen dürfen nie als weltweite aktuelle Gesamtzahlen erscheinen. Bei echtem Paket andere lokale Sollwerte verwenden. |

Vorab-Paketmanifest nennt für jeden R-/W-Fall Query, Gold-Artikel-/Entity-ID,
Originalevidenz und erlaubte Antwort. Im ersten Nutzerdurchgang diese Sollwerte
nicht als Chatantwort vorgeben. Fachlich richtige Antwort ohne Tool-/Quellenbeleg
erfüllt nicht automatisch den Retrievalnachweis. Fehlende Daten führen zu
„blockiert“, falsche Antwort bei gültigem Paket zu „fehlgeschlagen“.

## 6. Gezielte Geschwindigkeits- und Speichervergleiche

Erst starten, wenn C01/C02 lesbar und korrekt sind. Vor jedem Lauf identische
Dateien/Settings prüfen, vorherige JSON-Datei exportieren: Die App speichert nur
den letzten Benchmark unter `benchmark.json` und überschreibt ihn beim nächsten.

### B01: kalter Einzelprompt und B02: fünf verbundene Turns

1. **Menü öffnen → Leistung → Benchmark öffnen → Benchmark starten → Bisheriger
   Einzelprompt-Benchmark**: ein Warmup, drei gemessene Läufe, Cache je Lauf leer.
   Danach **JSON exportieren**, etwa `cpu-single-run1.json`.
2. Im selben Modusdialog **5 Fragen als zusammenhängender Chat**: Export
   `cpu-chat-run1.json`. Dieser Lauf startet mit einmaligem Reset und optionalem
   Prefix-Warmup; kein zusätzlicher ungemessener Generierungs-Warmup zwischen Turns.
3. Wenn relevant, jeden Modus in drei getrennten, abgekühlten Sitzungen wiederholen.
   Erster Durchgang ist Diagnose, keine belastbare p95-Schätzung aus nur fünf Turns.
4. Antworten in den Exporten mitlesen; Tokens/s allein ist keine Qualitätsmetrik.

Der bestehende Fünf-Turn-Test verwendet fünf Waldfragen. Für die subjektive
Bewertung nicht verlangen, dass dir diese Aufgaben persönlich nützlich sind;
dafür dienen U01–U03. UI-TTFT bedeutet Zeit bis zum ersten sichtbaren Endantwort-
Callback, keine Messung der tatsächlichen Displaydarstellung mit externem Sensor.

### B03: CPU, skalarer Rückfall und Vulkan

| Profil | Einstellungen | Zweck |
| --- | --- | --- |
| P-CPU | 4 Threads, 4096 Kontext, CPU-Microbatch 32, F16-KV, FA aus | verlässliche Ausgangsbasis |
| P-SCALAR | CPU-Kompatibilitätsmodus, Microbatch 1, übrige Werte identisch | Diagnose bei Rechenabweichung/Zeichensalat, nicht fairer reiner Geschwindigkeitsvergleich derselben Kernelkonfiguration |
| P-VK | nur CPU/Vulkan-APK, Vulkan-Microbatch 1, F16-KV, FA aus | tatsächlich angewandtes Backend gegen CPU vergleichen |

Ein Profil pro Sitzung; nach Wechsel neuen Chat/B02 starten und exportieren.
Kein gleichzeitiges Ändern von Kontext, GBNF, FA und KV. Optionales FA/Q8 erst
danach als eigene Experimente mit bestandener Korrektheitsprüfung. Prefix-Warmup
für den direkten Vergleich gleich lassen. Der Optimizer kann weitere Profile
testen und verändern; separat als B04 inklusive eigenem JSON-Protokoll behandeln.

Vergleich zweier abgeschlossener Fünf-Turn-Exporte:

```powershell
python -X utf8 benchmark/compare.py `
  "$BonsaiSession/cpu-chat-run1.json" `
  "$BonsaiSession/vulkan-chat-run1.json" `
  | Set-Content -Encoding utf8 "$BonsaiSession/compare-cpu-vulkan.json"
```

Die Exporte direkt aus der App unverändert kopieren. Das Vergleichsskript
prüft Modellhash, Runtime, Kontext, Seed, Antwortlimit, Reasoningbudget, GBNF
und Warmup; es ersetzt keinen manuellen Vergleich von Appcommit, Gerät,
Temperatur, Paket, tatsächlichem Backend und Antworten. Es lehnt unvollständige
oder nach seinen Vertragsfeldern inkompatible Exporte ab; das ist ein hilfreicher
Befund, kein Grund, JSON-Felder zur Erzeugung eines Vergleichs umzuschreiben.

### M01: Speicher separat beobachten

- Dumpsys-Schnappschüsse bei leerer App, nach Bonsai-Laden und nach zehn RAG-
  Queries sichern. Während des Encoders für einen **separaten Speicherlauf**
  zusätzlich wiederholt `adb shell dumpsys meminfo $BonsaiPackage` aufnehmen.
  Diese Messlast kann die Query verlangsamen und gehört nicht in den Latenzvergleich.
- Benchmark-RSS-Peak mit Zeitpunkt/Abtastrate und PSS-Schnappschüssen vergleichen;
  daraus keine exakte GPU- oder kurzzeitige Encoder-Peakallokation behaupten.
- Wenn die App verschwindet: Marker, Exit-Info und Fehlerdump sichern. Kein
  automatisches „zu wenig RAM“ ohne LMKD-/Exitbeleg; nativer Crash und Java-OOM
  sind unterschiedliche Fehlerbilder.
- Unter üblicher Alltagslast mit deinen normalen Hintergrundapps einmal gezielt
  wiederholen. Kein künstliches Vollstopfen des Geräts und kein Cacheleeren mit Root.

Reine IVF-/Wikidata-Gerätemikrobenchmarks existieren als DEX-Helfer unter
[rag/device/](rag/IVF.md) und [wikidata/device/](wikidata/README.md).
Agent bereitet sie bei Bedarf vor. Sie messen den jeweiligen Scanner/Abfragepfad,
ersetzen aber nicht App-Encoder, Toolrouting, Quellenantwort oder Nutzererfahrung.

### G01: Zeichnung und Inferenz getrennt eingrenzen

Bei Ruckeln im neuen UI in einem eigenen Diagnoselauf vor und nach dem Fall
`adb shell dumpsys gfxinfo de.bonsai.offline.debug framestats` sichern. Optional
vor diesem isolierten Lauf dessen Statistik mit `adb shell dumpsys gfxinfo
de.bonsai.offline.debug reset` zurücksetzen. Erst Idle mit offener Tastatur,
dann Streaming/Scrollen, anschließend Pop-up/Seitenleiste beobachten; denselben
Fall mit ausgeschalteten Systemanimationen wiederholen. Keine zusätzliche
Inferenzprofilauswahl ändern. Gfxinfo kann Frames des UI-Fensters eingrenzen,
beweist aber weder Encoder-/Vulkanrechenzeit noch GPU-Energiebedarf. Der
33-ms-Taktgeber ist keine gemessene konstante 30-fps-Ausgabe des A55.

## 7. Dein Feedback pro Testfall

Kurze, freie Aussagen sind erwünscht. „Nach 20 Sekunden wusste ich nicht, ob
noch etwas passiert“ ist hilfreicher als eine bloße Schulnote. Inhalt und
Bedienung getrennt bewerten; eine gefühlt schnelle falsche Antwort bleibt falsch.

Kopiervorlage, etwa als `feedback.md` im Sitzungsordner:

```text
Sitzung / Test-ID:
BEGIN-/END-Zeit oder Logmarker:
Profil / neuer Chat oder Fortsetzung:
Meine genaue Eingabe:
Was ich erreichen wollte / erwartetes Ergebnis:
Wie lange ich im Alltag dafür warten würde:
Vollständige tatsächliche Antwort / Dateiname:

Zeit bis sichtbarer Text, grob beobachtet:
Zeit bis fertig, grob beobachtet:
Antwort fachlich richtig: ja / teilweise / nein / kann ich nicht beurteilen
Antwort für mich nützlich: 1 2 3 4 5
Wartezeit für mich akzeptabel: 1 2 3 4 5
Bedienung/Status verständlich: 1 2 3 4 5
Vertrauen in Antwort/Quellen: 1 2 3 4 5 / keine Quellen erwartet

Wo war ich unsicher oder genervt?
Licht/Bewegung: hilfreich / neutral / ablenkend; Metadaten/Icons lesbar?
Was hat gut funktioniert?
Ruckeln, Zeichensalat, Appwechsel, Hitzegefühl, Abbruch oder sonstige Auffälligkeit:
Würde ich dieselbe Aufgabe morgen wieder damit machen? Warum?
Was wäre die wichtigste Verbesserung?
```

Skala: 1 = für diese Aufgabe unbrauchbar, 3 = mit Einschränkungen nutzbar,
5 = gut im Alltag nutzbar. „Kann ich nicht beurteilen“ wird nicht als positive
Korrektheitsbewertung gewertet. Persönliche Fragen/Antworten vor Weitergabe
bei Bedarf anonymisieren; der reproduzierbare Ablauf bleibt erhalten.

Zusätzlich nach jeder Sitzung drei freie Punkte: **größtes Problem**, **nächste
gewünschte Verbesserung**, **Aufgabe, bei der die App dir tatsächlich geholfen hat**.
Erst danach schlägt der Agent Ursachen/Optimierungen vor, damit dein ursprünglicher
Eindruck nicht durch eine technische Erklärung überschrieben wird.

## 8. Auswertung: beobachtete Wirkung und technische Ursache verbinden

| Beobachtung | Zuerst prüfen | Aussagegrenze / nächster Schritt |
| --- | --- | --- |
| Schon Modelltest liefert Zeichensalat | Modellhash/Bytes, tatsächlicher Backendpfad, skalarer CPU-Test | Kein RAG-Problem behaupten, solange Basisinferenz fehlschlägt |
| Lange Pause vor Antwort, danach schnell | TTFT/Turnzeit, Prefill, Toolrunden, KV-Reuse | Fehlende Encoder-/Suchteilzeiten als offene Diagnose benennen; nötigenfalls gezielt instrumentieren |
| Folgefragen werden langsamer | Promptlänge, neu dekodierte Tokens, Resetgrund, thermischer Verlauf, Reasoning/Cleanup | Weder steigender Kontext noch Temperatur allein beweist die Ursache |
| Hohe Tokens/s, aber gefühlt träge | sichtbare TTFT, Gesamtzeit, interne Runden/Reasoning, Statusdarstellung | Decode-Metrik kann gut sein, während Alltagserlebnis schlecht bleibt |
| Quellen fehlen oder Antwort erfunden | Daten vorhanden? passende APK? Paketprofil? Toolrouting? Text stützt Aussage? | Korrekte Vektorform oder vorhandener Quellenfooter beweist keine fachliche Fundierung |
| App verschwindet | Exit-Info, LMKD, AndroidRuntime/native Crash | Ursache erst nach Logbeleg zuordnen; Sitzung nicht als abgeschlossen werten |
| Gedächtnis wirkt verloren | exakt gleicher Schlüssel/Paket/Appdaten, Chatwechsel vs. Deinstallation | Schlüsselgedächtnis ist keine semantische Erinnerung |
| GBNF hilft beim Format, aber Antwort ist falsch | Toolname/Argumente/Ergebnisinterpretation | Grammatik erzwingt Format, nicht richtige Werkzeugwahl oder Fachwissen |

Ergebnisstatus pro Fall: **bestanden**, **fehlgeschlagen**, **blockiert**
(Voraussetzung fehlt) oder **unklar** (Belege reichen nicht). UX-Wertung daneben
erhalten; nicht in diesen technischen Status hineinmitteln.

Priorisierung nach dem ersten Durchgang:

1. Datenverlust, Crash, falsche Grundrechnungen/Zeichensalat zuerst.
2. Danach fehlendes echtes Retrieval/Quellenvertrauen und blockierter Datenimport.
3. Anschließend größte von dir berichtete Warte-/Bedienhürde mit Logs eingrenzen.
4. Erst danach Backend-/KV-/native Optimierungen anhand reproduzierbarer Vergleiche.

Für jeden Fehler: Test-ID, unveränderte Eingabe/Antwort, erwartetes Verhalten,
App-/Modell-/Paketidentität, Zeitfenster, relevanter Logauszug, Reproduktionsrate,
deine Auswirkung und höchstens eine belegte Ursachenhypothese aufnehmen.
Keine bislang ungetestete Hypothese als behobenen Fehler melden.

## 9. Ergebnisablage und Abschlusskriterien

```text
work/a55/<SITZUNG>/
  manifest.md                  # ausgefüllte Artefakt-/Einstellungsdaten
  feedback.md                  # deine Fälle U01... und Bewertungen
  logcat.txt                   # Marker + Turn-/Systemmeldungen
  modelcheck.json              # kopiertes App-Modellprotokoll
  cpu-single-run1.json         # unveränderter App-Export
  cpu-chat-run1.json           # unveränderter App-Export
  vulkan-chat-run1.json        # nur wenn tatsächlich ausgeführt
  mem-*.txt / thermal-*.txt / battery-*.txt
  exit-info.txt                # soweit System verfügbar
  error-window.txt             # nur bei Fehler, vor Teilen minimieren
  answers/                    # vollständige einzelne Testantworten
  AUSWERTUNG.md                # vom Agenten nach Sichtung zu erstellen
```

Nicht ausgeführte Dateien/Fälle bleiben weg oder werden als offen markiert;
keine Beispielmessungen eintragen. Rohdaten bleiben zunächst außerhalb Git.
Nach Sichtung nur anonymisierte, freigegebene Belege in einen passenden
Validierungs-PR übernehmen.

Der erste Durchgang ist vollständig, wenn U01–U03, C01–C03, T01–T04 und
L01–L03/X01 erfasst **oder mit konkretem Blocker markiert** sind, dein Feedback
vorliegt und der Agent eine priorisierte Auswertung geschrieben hat. Das ist
noch keine Produktfreigabe. RAG-/Vulkan-/Vollindexfreigaben benötigen jeweils
ihre Daten-/Korrektheits-/Gerätegates aus der Roadmap.

Die zweite UX-Sitzung deckt X02–X06 und bei Bedarf G01 ab; ihre Ergebnisse
stehen bis zu deinem echten Gerätedurchlauf weiter aus.

Die Auswertung beantwortet: Was funktioniert auf **deinem A55** tatsächlich?
Was stört dich im Alltag am meisten? Welche technischen Belege erklären es,
welche fehlen noch, und welcher einzelne nächste Entwicklungsschritt bringt
für deine Nutzung den größten belegbaren Nutzen?
