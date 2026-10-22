# Drittanbieter

Die Gradle-Wrapper-Skripte und `gradle/wrapper/gradle-wrapper.jar` stammen aus `gradle/gradle`, Tag `v8.11.1`. Gradle ist unter Apache License 2.0 lizenziert: https://github.com/gradle/gradle/blob/v8.11.1/LICENSE

Die PrismML-Runtime wird durch `scripts/fetch_runtime.py` separat am festen Commit bezogen und um den geprüften EmbeddingGemma-2-Backport aus llama.cpp ergänzt (MIT, Herkunft und Anpassungen in `runtime/README.md`). Sie und ihre mitgelieferten Abhängigkeiten behalten die jeweiligen Lizenzen und Urheberrechtshinweise im Runtime-Repository. Diese Hinweise müssen beim Verteilen eines Builds berücksichtigt werden.

Die App enthält die mitgelieferten Runtime- und Toolchain-Hinweise in `assets/third_party_notices.txt`; sie sind unter den Modelleinstellungen über „Open-Source-Lizenzen“ zugänglich.

Modellgewichte werden separat vom Nutzer importiert. Bonsai 2 4B wird laut offiziellem Repository unter Apache License 2.0 angeboten; die dort enthaltenen Lizenz- und Attributionsbedingungen gelten unabhängig vom App-Quellcode.

EmbeddingGemma 2 wird von Google unter Apache License 2.0 angeboten. Seine Gewichte werden separat bereitgestellt und sind nicht Teil der APK. Modellkarte: https://huggingface.co/google/embeddinggemma-2
