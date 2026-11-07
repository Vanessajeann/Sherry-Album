# Changes from PhotoTimeFixer

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
