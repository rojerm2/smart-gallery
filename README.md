# Smart Gallery

A native Android gallery for local photos and videos, with bundled on-device face matching,
image labels, Latin-script OCR and private search. Android 9 (API 28) or newer, ARM64 by default.

This is a personal-testing application. Recognition thresholds are provisional; broad real-library
accuracy, battery and target Redmi/HyperOS acceptance are not established by the small fixture tests.

## Features

- Paged photo/video grid, folders, date/type filters, zoom, Media3 playback and Android sharing.
- Separate People and Review faces; automatic support requires two different content hashes.
  Repeated video frames and byte-identical copies do not inflate that support.
- Complete-link matching with ambiguity margins. Singleton review, naming, promotion, assignment,
  merging, splitting, removal, negative constraints, dismissing and recent-correction undo.
- Versioned, normalized MobileFaceNet embeddings from aligned faces. Tiny, blurred or extreme-pose
  faces and missing landmarks are filtered. Higher-resolution source-region retries do not invent detail.
- Bundled image labeling and OCR. Conservative visual/text rules for broad documents, receipts
  and IDs; manual category overrides. A Dog category does not recognize an individual pet.
- Combined local search: `Dog 2026-10`, `Alice Camera`, or `"total PHP"`. All terms must match;
  quoted phrases are kept together. Match dates use the phone's local timezone.
- Incremental MediaStore reconciliation, durable stage/video-frame checkpoints, retries,
  pause/resume, charging-only defaults, thermal/low-storage yielding and periodic WorkManager jobs.
- Folder/item exclusions, reindexing that preserves corrections, and an explicitly confirmed reset.

## Privacy

No account or cloud recognition is used. The production manifest removes `INTERNET` and
`ACCESS_NETWORK_STATE`, including declarations from dependencies. Models are installed with the
APK and verified during build/device tests. Face crops, embeddings, OCR, corrections and thumbnail
caches stay in app-private storage; backup and device-transfer extraction are excluded.

Analysis never moves, edits or deletes originals. Sharing is user-initiated through Android with
a temporary read URI grant. Revoked or missing media is hidden and its derived content purged;
minimal correction records remain to restore confirmed decisions if identical content returns.
Reset removes those records too and preserves folder/item exclusions as privacy preferences.
Debug builds permit local ADB inspection and are for testing.

## Build

Requirements: JDK 17+ (validated toolchain uses JDK 21), Android SDK platform 37 and build-tools
36.0.0, accepted SDK licenses. Android Studio is optional. Gradle 9.4.1 is pinned by the wrapper
with a distribution checksum; Android Gradle Plugin 9.2.1 and dependency versions are pinned.

Set `ANDROID_HOME` or an ignored `local.properties` with your SDK path, then:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

On macOS/Linux use `./gradlew`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
For an x86_64 emulator, add `-PtargetAbi=x86_64` to the build/test command.
The recognition model is committed as a normal binary with a verified SHA-256. Bundled ML Kit
models are resolved from Maven during development; no first-run download is required.
See [model provenance and notices](MODELS.md).

## Install and verify

Enable USB debugging and authorize the computer, then select the intended device explicitly:

```powershell
adb devices -l
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Smart Gallery and grant the desired media access. Android 14+ can grant selected items;
analysis covers only accessible media. Charging-only analysis is enabled initially. Settings has
Scan now, pause/resume, thorough sampling, reindex, exclusions and reset.

To execute instrumented tests on one connected device:

```powershell
$env:ANDROID_SERIAL = 'YOUR_SERIAL'
.\gradlew.bat connectedDebugAndroidTest
```

Keep the same private debug keystore for subsequent updates. Another machine's debug signature
usually cannot update this installation; use a backed-up private release key for long-term use.
Signing material and APKs are intentionally not committed.

## Testing and evaluation

Unit tests cover support counting, ambiguity, weak bridges, quality, document-rule negatives,
search tokenization and bounded sampling. Instrumentation covers bundled inference, actual
alignment/embeddings, Room persistence, correction undo/negative constraints and schema migration.
It also exercises groups larger than one database page, repeated video faces, reindexing during
actual decoding, and corrections/undo across rediscovered face records. Older records without
face-location evidence may need confirmation after reanalysis.
NASA/US Navy public-domain fixtures are in the test APK only, with attribution in the notices.

The licensed two-identity micro-evaluation is a smoke check, not a held-out accuracy estimate.
For representative local evaluation, use the optional private fixture harness described in
[EVALUATION.md](EVALUATION.md). Private photos, labels, identities, results, crops and embeddings
must remain outside Git. Do not publish test APKs containing private evaluation assets.

## Limits and remaining acceptance

- A provisional cosine threshold is not a calibrated accuracy percentage. Similar relatives,
  age changes, masks, profiles, mirrors, posters and collages need representative evaluation.
- OCR is Latin script. Document categories are broad organization, not authenticity checks or
  guaranteed extraction; screenshots cannot automatically become receipts or IDs through text alone.
- Normal videos use at most 120 evenly distributed samples (about ten-second spacing for short
  clips); thorough mode uses at most 600 (about two-second spacing). Sampling can miss brief faces.
  MediaCodec decodes actual frame timestamps; results do not imply frame-perfect appearance intervals.
  Unsupported codecs and videos above the decoder's 16-megapixel budget remain browseable but can fail analysis.
- Automatic matching uses complete-link comparisons in bounded database pages. Reindex rebuilds
  unconfirmed groups, including fragments from the earlier 50-face cap, and preserves corrections.
- Byte-identical copies are deduplicated for support. Visually similar/recompressed copies are
  not treated as exact duplicates. Originals are always independently browsable.
- Background timing is controlled by Android and the manufacturer. No overnight completion-time,
  universal recognition accuracy or device battery claim is made.
- On Redmi Note 14 4G (24117RN76G), Android 16 / HyperOS 3.0.302.0, bundled inference and
  persistence tests passed during a first-launch airplane-mode run. Abrupt process recovery also
  passed with owned fixtures. Automated taps require HyperOS's USB debugging security setting;
  modern selected/revoked access, reboot, background policy and resource checks remain pending.

Application code is Apache-2.0; third-party SDKs and model weights retain their separate terms.
