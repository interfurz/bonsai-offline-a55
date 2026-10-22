# Minimal native chat and Java glow — design QA

final result: passed

## Target

The user's two mobile reference screenshots establish a dark chat with generous
empty space, round header actions, bottom suggestions, a capsule composer, and a
left drawer. The user explicitly removed the Chat/Work switch from scope.
The Paper Shaders snippet supplies three effect presets. This implementation is
native Android Java; the gradient-based effect interprets the reference rather
than claiming WebGL pixel equivalence.

## Evidence and review

Native Robolectric captures in `app/build/ui-captures/`:

- `ready-dark.png`: 375 × 812 dp; minimal ready chat and reachable composer.
- `history-dark.png`: 375 × 812 dp; drawer and anchored new-chat action.
- `welcome-light.png`: 412 × 892 dp; first-use model import and draft entry.
- `glow-generating-dark.png` and `glow-done-dark.png`: 412 × 892 dp;
  cyan/magenta/coral/amber active border and green/cyan completion border.
- `large-text-chat.png`, `large-text-Modell.png`, `large-text-Leistung.png`:
  375 × 812 dp, font scale 2; wrapping and primary action reachability.
- `model-light.png`, `performance-light.png`: native settings dialogs.
- `tablet-light.png`, `tablet-model-popup.png`: 1024 × 900 dp; capped reading width.

The supplied reference images and the native rendered images were visually
inspected. This is a redesign using the requested layout; prompt text and menu
items reflect actual offline Bonsai functions. The app does not add unsupported
image generation, microphone or voice controls from the reference.

## Findings and fixes

1. Initial light halos had a visible square falloff around circular actions.
   Constrained local highlight rendering to layered strokes of the rounded
   perimeter; inspected the next captures.
2. Centered the title and subtitle themselves inside the header's weighted area.
3. Removed the disabled send button's rectangular surface; retained the round
   target, disabled opacity, and separate background-independent focus outline.
4. Test-only native offscreen captures can reuse stale text RenderNodes between
   repeated bitmap draws of the same Activity. The completion capture now uses
   its own fresh Activity; functional state checks remain separate.

No remaining P0/P1/P2 visual or interaction findings in the inspected captures.

## Verification

- 132 native Java/Robolectric tests pass; ARM64 CPU debug APK builds. Android
  Lint: zero errors, eight pre-existing configuration/inline-text warnings.
- Contrasts for normal, muted, accent, warning and error text, primary actions,
  badges and controls verified by the existing palette tests.
- Streaming reuses the same text view; draft, popup and conversation recreation,
  copy, archive, deletion confirmation and answer-variant behaviors remain tested.
- Effect states, completion timeout, error/cancel handling, hidden views, activity
  lifecycle, reduced animation and power saving have explicit tests.
- Shaders and paths are cached; frames run at approximately 30 fps and do not
  modify layout or inference settings. Covered chat effects pause behind overlays.

These are native test renders with fixture messages/model state. There is no
attached Android device; A55 frame rate, keyboard insets, TalkBack and real
inference under simultaneous animation remain device checks. No browser applies.
