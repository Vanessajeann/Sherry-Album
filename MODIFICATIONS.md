# Changes from PhotoTimeFixer

## 0.1.0-test3

- Fix the confirmed Compose progress-semantics crash when mutable totals reset to zero between composition and semantics evaluation.
- Capture immutable, bounded progress fractions; use indeterminate progress for unknown totals.
- Cover rapid visual/group/completion transitions in actual Compose UI tests, including semantics access before recomposition.
- Apply the same snapshot rule to time-repair progress and retain exception messages in diagnostics.

## 0.1.0-test2

- Bound software image decode dimensions and share a single decoder slot with thumbnails.
- Catch per-image runtime/heap failures while retaining scan cancellation; release temporary bitmaps on failure.
- Store compact byte color fingerprints instead of floating-point arrays.
- Add app-private scan diagnostics and historical process exit information, exported only by user action.
- Add emulator regression for the real image decoder and MediaStore visual pipeline, including corrupt and very large fixtures.

Date: 2026-11-07

Upstream: SiRan-Dev/PhotoTimeFixer, commit 15948a12185e3ac8a27671a8c3512a094f734dd7.
Upstream copyright and GPL-3.0-or-later notices remain in the source and LICENSE.

- Added AlbumActivity and duplicate package: local exact/similar image analysis, retained scan state, grouped review and MediaStore trash requests.
- Added DuplicateCoreTest and Android build workflow. Tests have been written but not executed in this environment.
- Changed applicationId, namespace, Kotlin package declarations, app name, launcher activity and icon for independent installation.
- Modified MainActivity permission callbacks to stop repeating denied permission prompts and to guard against concurrent scan/fix operations.
- Modified signing setup to allow debug builds without the upstream private keystore.
- Replaced the bundled donation photograph with a direct link to the original author's donation information.
- Added build instructions, wrapper restoration script, validation report and the original upstream README below the fork introduction.

No Apple/i4Tools proprietary implementation or assets are included. Similarity detection does not merge metadata or library memberships.
