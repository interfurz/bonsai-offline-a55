# Lokale SymPy-Engine

Das KI-Tool `mathe` verwendet echtes SymPy 1.14.0 mit mpmath 1.3.0, Chaquopy
17.1.0 und CPython 3.12.15 für ARM64. Die bisherige `LocalMath`-Polynomengine und
ihre Tests sind entfernt. `calc` ist weiterhin der eigenständige numerische
Rechner. Es gibt keinen stillen Rückfall auf die alte Polynomengine.

## Verwendung im Chat-Tool

Alle Argumentwerte sind Strings. `op` und `expr` sind Pflichtfelder.

```json
{"name":"mathe","args":{"op":"integrate","expr":"sin(x)","var":"x","lower":"0","upper":"pi"}}
```

| Operationen | Zweck / zusätzliche Argumente |
|---|---|
| simplify, expand, factor, cancel, apart, trigsimp | Algebraische und trigonometrische Umformungen |
| differentiate | `var` (Standard x), `order` (1–20, Standard 1) |
| integrate | `var`; optional `lower` und `upper` gemeinsam; unbestimmt immer + C beachten |
| solve, solveset | Einzelgleichung z.B. `x^3=1`; `var`, `domain` real/complex (Standard complex) |
| solve | Systeme als JSON-Liste von Gleichungsstrings oder `[Eq(x+y,3),Eq(x-y,1)]`; `vars` z.B. `x,y` |
| linsolve, nonlinsolve | Gleichungsliste und kommagetrennte `vars` |
| nsolve | Gleichung/System, `var`/`vars`, `point` als Startzahl/-liste; lokale numerische Lösung |
| dsolve | ODE, z.B. `Eq(Derivative(y(x),x),y(x))` |
| limit | `var`, Pflichtfeld `point`; `direction` +, - oder +- |
| series | `var`, `point` (Standard 0), `order` (Standard 6) |
| summation, product | `var`, Pflichtfelder `lower`, `upper` |
| numeric | `precision` (1–100 Dezimalstellen, Standard 30) |
| det, inverse, rank, eigenvalues, transpose, rref | `expr`: `Matrix([[1,2],[3,4]])` oder verschachtelte Zahlenlisten |
| gcd, lcm | `expr`: Liste von zwei Ausdrücken |
| factorint, isprime | Ganze Zahl bis 128 Bit |

`assumptions` ist ein JSON-Objekt als String, etwa
`{"x":{"positive":true}}`. Unterstützt werden real, complex, positive,
negative, nonnegative, nonpositive, integer, nonzero, finite und rational.
`domain=real` ergänzt für die gewählten Lösungsvariablen die Annahme real.
Ohne solche Annahmen ist beispielsweise `sqrt(x^2)` nicht automatisch `x`.

Ausdrücke erlauben explizite Operatoren `+ - * / % ^` (auch `**`), Zahlen,
Symbole und mathematische Funktionen. Dazu gehören sin/cos/tan, inverse und
hyperbolische Funktionen, exp/log/sqrt/Abs, gamma, erf, zeta, Besselfunktionen,
LambertW, factorial, binomial, Piecewise und symbolische Integral-/Derivative-
Konstruktoren. Konstanten: pi, E/e, I, oo, EulerGamma und Catalan. Unbekannte
Funktionen f, g, y und u sind für mathematische Funktionsgleichungen erlaubt.
Andere SymPy-APIs sind kein frei ausführbarer Python-Endpunkt.

Das Ergebnis enthält `engine`, `version`, `result`, `latex`, `exact`,
`unevaluated`, `domain`, `assumptions` und `notes`. `solve` nutzt für einzelne
Gleichungen `solveset`; bedingte Mengen und stehen gebliebene Integrale dürfen
nicht als vollständig gelöst ausgegeben werden. Numerische Nullstellen hängen
vom Startwert ab und sind keine vollständige Lösungsmenge. Definitionsbedingungen
ursprünglicher Nenner gelten auch nach algebraischem Kürzen.

Eigene gespeicherte Tool-Anweisungen bleiben erhalten. Wer den Tool-Bereich im
Systemprompt angepasst hat, muss dort die neue SymPy-Schnittstelle berücksichtigen
oder die aktuellen Standard-Tool-Anweisungen wiederherstellen.

## Android-Ausführung und Grenzen

- Die APK enthält Python und CAS-Pakete; keine Downloads oder Internetberechtigung
  zur Laufzeit, keine zusätzliche Nutzerinstallation.
- Ein nicht exportierter gebundener Service läuft in `:sympy`. Die Application
  initialisiert dort weder Inferenz noch Chat-Recovery. Ein Auftrag pro Prozess,
  globale Serialisierung der CAS-Aufträge im Hauptprozess.
- Abbruch und 20 Sekunden Gesamtzeit (einschließlich Start/Wartezeit) beenden den
  CAS-Prozess anhand seiner vom privaten Service gemeldeten PID. Der nächste
  Auftrag startet einen frischen Prozess. JVM-Thread-Interrupt allein reicht für
  eine laufende native Python-Berechnung nicht aus.
- Nach Python-/SymPy-Start versucht der Worker zusätzlich CPU- und Adressraumlimits
  zu setzen (256 MiB über dem aktuellen virtuellen Adressraum). Diese OS-Limits
  sind best effort; die Java-Wallclock-Grenze ist unabhängig davon. Android kann
  den Prozess unter Speicherdruck vorher beenden; das wird als Toolfehler gemeldet.
- Eingabe höchstens 4096 Zeichen, insgesamt 8192 Argumentzeichen, 512 AST-Knoten,
  40 Verarbeitungsebenen, 32 Symbole, 16 Lösungsvariablen, 100 Matrixzellen.
  Zahlen-/Exponenten-/Ergebnisbudgets begrenzen weitere Ressourcen.
- Der AST-Interpreter erlaubt keine Python-Attribute, Indexzugriffe, Imports,
  Strings im mathematischen Ausdruck, Comprehensions, Lambdas oder frei gewählte
  Funktionsaufrufe. Er verwendet weder eval, exec, sympify noch parse_expr für
  untrusted Eingaben. Fachlich nicht unterstützte Syntax führt zu Toolfehlern.

Es gibt keine Garantie, dass jede mathematische Aufgabe innerhalb der Budgets
lösbar ist. Geräte- und Echtwelttests bleiben auf Nutzerwunsch ausgelassen.

## Build und Tests

Python 3.12 auf dem Buildrechner, bei Bedarf
`./gradlew -PbonsaiBuildPython=/pfad/zu/python3.12 ...`.
Die Android-Workflows installieren Python 3.12 und testen das identische
Python-Modul vor dem APK-Bau.

```sh
python3 -m pip install -r tests/requirements-sympy.txt
python3 -m unittest discover -s tests -p test_sympy_engine.py -v
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Python-Fachtests nutzen echtes SymPy, kein nachgebildetes CAS. Java-Tests prüfen
Binder-Protokoll, Argumentprüfung, Dispatcher, Abbruch, Unbind und Zeitlimit
mit einer simulierten Serviceantwort; sie führen keine ARM64-Python-Bibliothek
auf dem Host aus. Die native GBNF-Prüfung prüft neue optionale Matheargumente.

## Nachweise dieses Arbeitsstands

- Das paketierte Python-Modul besteht die sieben Fach-/Grenzfall-Testgruppen
  mit tatsächlichem SymPy; die gesamte Python-Hosttestsuite umfasst 12 Tests.
- Android/Robolectric: 195 Tests, 193 bestanden, zwei optionale Echtdatenfälle
  übersprungen. Keine Fehler.
- Debug- und unsigned Release-APK für ARM64 gebaut; Debug- und Release-Lint
  ohne Fehler. APK enthält das CAS-Modul und SymPy, keine separate Installation.
- Alle 71 ELF-Bibliotheken der Debug-APK einschließlich Python-Container
  unterstützen mindestens 16-KiB-Segmentausrichtung; ZIP-Alignment geprüft.
- Native Tool-/Final-GBNF-Prüfung einschließlich zusätzlicher Variablen,
  Integrationsgrenzen, Domäne und Annahmen bestanden.
- Projektprüfung für Manifest, JNI, UTF-8 und Runtime-Pin bestanden.

Die Test-Serviceantworten belegen das Android-Protokoll, nicht die native
Python-Ausführung auf einem realen A55. Geräteprüfungen wurden wie gewünscht
nicht ausgeführt. Die Debug-APK wächst durch die eingebettete Laufzeit von
rund 10 MiB auf rund 45 MiB; das ist eine gemessene Paketgröße, keine RAM-Messung.
