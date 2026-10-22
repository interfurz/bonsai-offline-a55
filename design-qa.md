# Answer footer icons — visual QA

final result: passed

## Target and evidence

- User references: `3060.jpg` (existing large buttons) and `3061.jpg` (compact icon-row style), supplied in `/tmp/codex-remote-attachments/01a1614b-c1cf-77d4-a61d-bf6df55c60b7/f452f797-112b-485d-a30d-7c68a9bfcde8/` as `1-3060.jpg` / `2-3061.jpg`.
- Native Android implementation: `docs/ui/answer-icons-dark.png` and `docs/ui/answer-icons-large-text.png`; additional light-theme capture: `app/build/ui-captures/answer-icons-light.png`.
- Regular viewport: 412 × 892 dp, 824 × 1784 pixels, density 2. Large-text viewport: 360 × 800 dp, 720 × 1600 pixels, density 2, font scale 1.5.
- State: completed answer with three metrics, copy available and latest-answer regeneration enabled. Light/dark themes inspected. Copy to clipboard and disabled regeneration without a loaded model tested.
- Combined source/implementation evidence opened for comparison: `app/build/ui-captures/answer-icons-comparison.png`. Focused before/after comparison for the large-font correction: `app/build/ui-captures/answer-icons-wrap-comparison.png`.
- The user references are zoomed crops without a declared device density. Compare compact layout and relative icon hierarchy, rather than claiming a pixel-identical recreation. The screenshot's speaker/share/menu controls are not requested app functions. The native app retains its existing palette and its two existing actions.

## Findings and iteration

1. Initial native render: normal-size footer matched the requested compact structure. At font scale 1.5, the `s` unit could wrap alone and inline icons sat too low. P2, blocked until fixed. Evidence: `app/build/ui-captures/answer-icons-large-text-before.png`.
2. Fixed with non-breaking spacing inside each metric and baseline-aligned image spans. Re-captured and inspected the full screen and the combined before/after crop. The timer metric now wraps as a complete icon/value/unit group. No actionable P0/P1/P2 findings remain.

## Fidelity surfaces

- Typography: existing Android system font, 12sp metadata and unchanged response typography. Full original metric descriptions remain available to accessibility services and tooltips.
- Spacing/layout: small 16dp metric icons; larger 28dp action-icon canvases in 48dp touch targets, with an 8dp gap between targets. No full-width regeneration button or permanent explanatory paragraph. Large text wraps between metric groups.
- Colors: existing Bonsai light/dark muted-foreground colors and transparent action backgrounds; native ripple feedback and visible disabled opacity.
- Assets: official Google Material Symbols Rounded paths in Android VectorDrawables, sharp at both tested densities. Provenance and Apache-2.0 attribution included in the app's third-party notices. No generated raster UI or emoji icons.
- Copy/content: copying and regeneration keep their behavior. Regeneration's separate-chat explanation moves to its tooltip/content description. No unsupported speaker/share functionality added.

## Verification

77 Java/Robolectric tests pass, Android Lint has no errors (eight existing configuration/UI-text warnings), ARM64 debug APK builds. Screenshots come from native Android rendering through Robolectric, not a web prototype. No browser console applies. Physical-device/touch feedback remains untested.
