# Android KnowledgeSearch initialization

`KnowledgeSearch` compiled its static word pattern with
`Pattern.UNICODE_CHARACTER_CLASS`. Android rejects that flag with
`IllegalArgumentException`, even though a desktop JVM accepts it. The first
`suche` call therefore raised `ExceptionInInitializerError`; subsequent calls in
the same process raised `NoClassDefFoundError` for `de.bonsai.offline.KnowledgeSearch`.
This matches the two reported error dialogs.

The pattern already uses explicit Unicode letter and number categories
(`\p{L}` and `\p{N}`), so removing the flag preserves tokenization. Android's
implementation documents and rejects the flag in
[Pattern.java](https://android.googlesource.com/platform/libcore/+/refs/heads/android15-release/ojluni/src/main/java/java/util/regex/Pattern.java).

## Regression checks

The unit test checks repeated lexical searches, empty quantum physics results
against the small fixture, and encoding of German, Chinese and Arabic-Indic
characters against `rag.embeddings.HashEncoder`.

Robolectric uses the host JVM's regex implementation, so also run the smoke test
in Android's runtime. It needs an emulator or device, JDK 17, and an Android SDK
with platform/build tools 35; no model or installed APK is required:

```sh
bash tests/build_knowledge_search_smoke.sh
adb shell mkdir -p /data/local/tmp/bonsai-search-smoke
adb push build/knowledge-search-smoke/knowledge-search-smoke.jar \
  app/src/test/resources/rag/knowledge.sqlite /data/local/tmp/bonsai-search-smoke/
adb shell 'CLASSPATH=/data/local/tmp/bonsai-search-smoke/knowledge-search-smoke.jar app_process / de.bonsai.offline.KnowledgeSearchSmoke /data/local/tmp/bonsai-search-smoke'
```

Both attempts must print `passed`. The original implementation fails at class
initialization before returning any search results. The smoke test also verifies
Unicode lexical search and the Python reference vector on Android.
