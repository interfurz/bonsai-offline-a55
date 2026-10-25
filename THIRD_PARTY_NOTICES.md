# Drittanbieter

Das CAS verwendet SymPy 1.14.0 (BSD), mpmath 1.3.0 (BSD), Chaquopy 17.1.0
(MIT) und CPython 3.12.15 (PSF und eingebettete Herkunftshinweise). Die
Lizenztexte stehen in `app/src/main/assets/third_party_notices.txt`; die
Python-Paketcontainer enthalten zusätzlich ihre Paket-Lizenzdateien.

Die Markdown-Darstellung verwendet Markwon 4.6.2 (Core, Strikethrough und Tables;
Copyright 2019 Dimitry Ivanov, Apache License 2.0,
https://github.com/noties/Markwon) und commonmark-java 0.13.0 einschließlich der
GFM-Erweiterungen (Copyright 2015–2016 Atlassian Pty Ltd, BSD 2-Clause,
https://github.com/commonmark/commonmark-java). Die Lizenztexte sind auch in
`app/src/main/assets/third_party_notices.txt` enthalten.

Die Gradle-Wrapper-Skripte und `gradle/wrapper/gradle-wrapper.jar` stammen aus `gradle/gradle`, Tag `v8.11.1`. Gradle ist unter Apache License 2.0 lizenziert: https://github.com/gradle/gradle/blob/v8.11.1/LICENSE

Die PrismML-Runtime wird durch `scripts/fetch_runtime.py` separat am festen Commit bezogen und um den geprüften EmbeddingGemma-2-Backport aus llama.cpp ergänzt (MIT, Herkunft und Anpassungen in `runtime/README.md`). Sie und ihre mitgelieferten Abhängigkeiten behalten die jeweiligen Lizenzen und Urheberrechtshinweise im Runtime-Repository. Diese Hinweise müssen beim Verteilen eines Builds berücksichtigt werden.

Die App enthält die mitgelieferten Runtime- und Toolchain-Hinweise in `assets/third_party_notices.txt`; sie sind unter den Modelleinstellungen über „Open-Source-Lizenzen“ zugänglich.

Modellgewichte werden separat vom Nutzer importiert. Bonsai 2 4B wird laut offiziellem Repository unter Apache License 2.0 angeboten; die dort enthaltenen Lizenz- und Attributionsbedingungen gelten unabhängig vom App-Quellcode.

EmbeddingGemma 2 wird von Google unter Apache License 2.0 angeboten. Seine Gewichte werden separat bereitgestellt und sind nicht Teil der APK. Modellkarte: https://huggingface.co/google/embeddinggemma-2
