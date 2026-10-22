# EmbeddingGemma 2 backport

`embeddinggemma2.patch` adds the text architecture from
[ggml-org/llama.cpp#30054](https://github.com/ggml-org/llama.cpp/pull/30054),
merge `4fbc76dec51d`, to PrismML commit
`e3de715222b2667c54a267441159a5729f11155e`.
Upstream llama.cpp is MIT licensed (see `vendor/llama.cpp/LICENSE`).

Only the five C++ registration/model files are changed. The model graph uses
the pinned runtime's `set_swa_pattern` and `build_inp_embd` APIs. Attention is
bidirectional, with per-layer inputs, value normalization and the trained
512→768 output projection. Conversion tools and multimodal encoders are outside
this backport; use the upstream text GGUF from
[ggml-org/embeddinggemma-2-GGUF](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF).

`python3 scripts/fetch_runtime.py` applies the patch to a clean pinned checkout,
or verifies the already patched checkout against `embeddinggemma2-files.json`.
It rejects partial patches, unrelated edits and different runtime revisions.
The vendor submodule remains pinned to PrismML; the reproducible patch is tracked
in this repository. Cached core archives must have been built with this patch.
