# Native UTF-8 streaming validation

## Scope

The scanner previously inferred sequence length from the leading byte only. It accepted isolated continuation bytes, overlong encodings, surrogate code points and values above U+10FFFF. The existing streaming test reassembled raw bytes before comparison, so even splitting a Unicode character across Java callbacks could pass.

The fix validates the available bytes before emitting a prefix. Valid partial sequences remain buffered; malformed sequences throw a descriptive `std::runtime_error`, which the existing `NativeEngine.generate` JNI boundary translates into a Java exception. This intentionally fails the generation instead of silently passing malformed model output to Java's replacement decoder.

No evidence shows that the official Bonsai model produced these malformed sequences on the Galaxy A55. This is a reproducible scanner defect, not a verified explanation or repair of the reported device-specific gibberish.

## Executed local checks

- `python scripts/check_project.py`: passed with C++17, `-Wall -Wextra -Werror`; manifest/XML, JNI names, runtime revision and all streaming tests passed.
- JDK 21 `java scripts/ParseJava.java`: seven production Java files parsed successfully. Syntax parsing is not Android type checking.
- `git diff --check`: passed.
- New regression suite compiled against the original header from commit `213ac66`: failed at the malformed-input rejection assertion, as intended.
- Mutation test replacing the scanner's result with the full byte count: failed at the independent per-chunk decoder's incomplete-character assertion, as intended.
- Restored fixed header with the same tests: exit 0.

The tests cover byte-by-byte streaming, every single split position in the fixtures, embedded NUL, German/combining/Japanese text, emoji, valid Unicode boundary values, incomplete suffixes and malformed leading/continuation/range cases. Assertions remain active in the canonical host check command.

Local red/green/mutation evidence: `C:/Users/schaf/.hermes/profiles/realistic-backrooms-unreal-engine/cache/scratch/bonsai-utf8-red-green-mutation.txt`.

## Android and hardware gates

The existing GitHub `Android APK` workflow is the authoritative ARM64 NDK build, Robolectric test and lint gate for this branch. Its exact outcome is recorded in the PR after execution; the local checks above do not claim that this gate has already passed.

No physical Galaxy A55, real model inference or Vulkan runtime test was performed for this change. The behavior for a generation ending with an otherwise valid incomplete UTF-8 suffix remains unchanged; end-of-stream policy is outside this patch.

## Parallel-work isolation

Java draft/guard regression work on `fix/draft-and-kernel-validation` is independent and not included here. This native fix uses the separate `fix/strict-utf8-stream` worktree based on `213ac66`; no shared-clone code was overwritten or merged.
