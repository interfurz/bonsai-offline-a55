# NPU auf dem Galaxy A55

Geprüft am 19. Oktober 2026. **Nicht implementiert: Bonsai-4B-Inferenz auf der Exynos-1480-NPU.**

Das Vorhandensein der NPU im Gerät bedeutet nicht, dass die hier verwendete Runtime sie ansprechen kann. PrismMLs GGUF-Backendübersicht enthält CPU, Metal, CUDA, ROCm, Vulkan und SYCL, aber keinen Exynos-NPU-Pfad für die Bonsai-Formate und ihre Hadamard-Transformationen:

- [Offizielle Bonsai-Backendübersicht](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/BACKEND-SUPPORT.md)

Die neue Samsung-Anbindung in LiteRT ist ein anderer Ausführungspfad mit konvertierten/kompilierten Modellen. Ihre offizielle Liste nennt Exynos 2500 und 2600, nicht den Exynos 1480 des A55:

- [Samsung Exynos AI LiteCore in LiteRT](https://developers.google.com/edge/litert/next/samsung)
- [LiteRT-NPU-Modellkonvertierung und Ausführung](https://developers.google.com/edge/litert/next/npu)

Samsungs ältere Neural-SDK-Seite weist außerdem darauf hin, dass dieses SDK nicht mehr an Drittentwickler ausgegeben wird:

- [Samsung Neural SDK](https://developer.samsung.com/neural/overview.html)

Für einen echten Modus fehlen damit eine nutzbare, für den Exynos 1480 geeignete Laufzeit-/Compileranbindung und ein kompatibler Bonsai-Modellgraph mit Tokenizer, Zustandsverwaltung und Quantisierung. Ein bloßer NNAPI- oder NPU-Schalter konvertiert die GGUF-Datei nicht. Eine künftige Implementierung muss auf dem A55 nachweisen, welche Operationen tatsächlich auf der NPU laufen, ihre Antworten gegen CPU prüfen und Latenz sowie Speicher messen. Ein CPU-Fallback darf nicht als NPU-Inferenz angezeigt werden.
