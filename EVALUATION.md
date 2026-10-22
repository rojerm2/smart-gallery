# Private evaluation

Keep fixtures and results under ignored `.ai/evaluation/` or another private directory. Use only
consented media or clearly licensed fixtures. The app does not upload evaluation media or results.

The optional `PrivateEvaluationTest` reads `evaluation/manifest.json` from test-only assets.
Add a directory through `-PprivateEvalAssets=.ai/evaluation/assets`, build the test APK and run
that test class on a selected phone. This packages private assets only in the ignored test APK;
never publish that APK. The normal app APK does not include androidTest assets.

Manifest structure:

```json
{
  "items": [
    {
      "file": "evaluation/consented-example.jpg",
      "split": "heldout",
      "identity": "local-anonymous-label",
      "expectedFaces": 1,
      "labelsPresent": ["Dog"],
      "labelsAbsent": ["Receipt", "ID / document"],
      "ocrContains": [],
      "screenshot": false
    }
  ]
}
```

Identity matching evaluation requires exactly one usable face; use single-person images for that
measurement. Multiple usable detections are unresolved rather than arbitrarily assigning a face.
Content/document rules run before face eligibility, matching the photo worker's document-portrait
filter. Quality and document-portrait exclusions are counted separately. Sources are decoded at
their supplied bounded resolution; this harness does not measure MediaStore source-region retries.
Separate detection coverage, quality exclusions, missed matches, false identity
merges, review volume, category precision/recall and resource measurements. For multi-person
detection, `expectedFaces` measures count coverage but does not substitute for annotated bounding
boxes/IoU evaluation. Per-category counts are reported only over explicitly annotated labels.
The harness also runs the production grouping repository in isolated in-memory databases using
the manifest, reversed and seeded-shuffle orders. It reports mixed-identity groups, minority
media within those groups, split identities, People groups and default-review media. These are
dataset-scoped grouping results; they do not estimate an unseen library's review volume.

Use `calibration` and `heldout` split values. Select thresholds from calibration only, freeze the
configuration, then evaluate heldout data. A tiny or unrepresentative sample does not establish
reliability. Record sampling limitations, device/OS, model/config versions and test date locally.
Calibration reports a cutoff sweep; heldout reports only the frozen current cutoff.
Pair reports distinguish all labeled pairs from actually compared pairs; unresolved detections
are missed positive pairs, and are not evidence of correct negative matches. Optional instrumentation
argument `evaluationGeometry=true` writes only anonymous item indices, detection boxes/landmarks,
duplicate-comparison cosine scalars and reasons
to app-private `cache/evaluation-geometry.json` for annotation QA. It writes no images, names,
OCR or embeddings and prints no geometry to logs. Remove this test-created export after inspection.
The default matching cutoff remains provisional after local calibration. The configuration is
versioned independently from embeddings; reindex rebuilds automatic groups with the current
configuration while preserving explicit decisions. A cutoff is not an accuracy percentage.
Detector hypotheses with strongly overlapping boxes are suppressed only when all five physical
landmarks agree within a small fraction of eye separation. This does not compare identities or
assume that all faces in a photo are distinct. Record detector fixes discovered during evaluation
as post-hoc fixes; repeated runs on those same photos are regression checks, not new heldout samples.
Include relatives, children across age, different poses, low light, blur, glasses, masks, collages,
posters, mirrors, small document portraits, animals, scenes, receipts, IDs and text screenshots.

The harness outputs aggregate metrics without filenames, OCR text or embeddings. It does not
measure battery drain or full-library background throughput. Those require an explicitly recorded
device session with charging, thermal state, interruptions and library size controlled.

## Actual process interruption

On a developer-authorized Android 10+ phone with the matching debug app installed, build the
instrumentation APK and run the Windows host orchestrator:

```powershell
.\gradlew.bat assembleDebugAndroidTest
.\tools\test-process-recovery.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -TestApk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk -LogDirectory .ai/process-recovery
```

This opt-in check repeats the existing public-domain video fixture into a temporary two-minute
clip and uses an isolated private database/preferences. It waits for real frame/checkpoint commits,
adds a manual identity/category correction, verifies the app PID, requests that test process to
call Android's self-SIGKILL API, then starts a new process. Recovery must find an incomplete running
checkpoint, preserve the corrections, finish real inference, avoid duplicate records and leave
the fixture bytes unchanged.
Only the run's exact newly inserted MediaStore URI and isolated private files are cleaned up.
The normal instrumentation suite skips this host-dependent test and never signals a process.

The host explicitly starts recovery work. This check does not prove automatic WorkManager/OS
rescheduling, reboot recovery, charging constraints, airplane-first-launch behavior or Redmi/HyperOS
acceptance. It does not grant gallery permissions, reset existing app data or use private media.

## Normal device suite and observed OPPO freezing

If the personal installation has gallery access, build a separate installation for fixture tests:

```powershell
.\gradlew.bat -PdeviceValidation=true assembleDebug assembleDebugAndroidTest
```

Install both resulting APKs. They target `dev.smartgallery.validation` and its `.test` runner,
with the launcher label “Smart Gallery Validation”. This uses the same application code with a
separate UID, database and permission state. Pass `-Package dev.smartgallery.validation` to both
host helpers. Rebuild without the property for the deliverable; do not distribute the validation APK.
Tests that reconcile MediaStore or capture gallery UI skip when the target has full or selected
gallery access, including Android 13+ media permissions. They never revoke the personal app's grants.

On Windows, install matching debug app and test APKs, then run the normal suite with:

```powershell
.\tools\run-device-tests.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/device-tests
```

On the available OPPO, a live instrumentation process was observed waiting in the kernel
`__refrigerator` channel. Foregrounding Smart Gallery thawed that same PID and its existing suite
continued to completion. This helper detects that specific state and foregrounds the existing
app; it does not restart tests merely because an observation takes a long time. Logs retain
skips separately from passes. It does not change system power settings or prove background
reliability on ColorOS/HyperOS; manufacturer background and resource acceptance remain separate.

## Temporary sharing grants

`SharingIntegrationTest` creates one public-domain MediaStore fixture owned by the target app.
It sends the production ACTION_SEND payload directly to a test-only activity in the test APK,
which runs under a separate UID and has no gallery permission. The receiver must fail to read
without a grant, read the exact original with the read grant, receive no write grant, and fail
again after the granted activity finishes. The fixture is then removed and reconciled.
This checks Android URI access across processes without sending media to an external service.
The receiver is absent from the production manifest; Android chooser UI navigation is separate.

## OS reboot recovery

`OsRecoveryTest` is an opt-in check restricted to the disposable validation installation. It
resets only that installation's derived data, creates a repeated licensed video, and starts the
production scheduler with charging-only enabled. Keep the phone charging. The normal suite skips it.
The host helper has separate phases so a reboot is an explicit action on an authorized test device:

```powershell
.\tools\test-os-recovery.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/os-recovery -Phase Prepare
.\tools\test-os-recovery.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/os-recovery -Phase Reboot
# Unlock normally after boot, leaving the gallery apps closed.
.\tools\test-os-recovery.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/os-recovery -Phase Observe
.\tools\test-os-recovery.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/os-recovery -Phase Finish
```

Preparation must reach an incomplete real frame checkpoint. A framework-only Java `app_process`
probe reads the exact owned fixture's SQLite row without constructing Application, Room,
WorkManager or a worker. Observe requires a changed boot ID and a checkpoint beyond the value
read immediately before reboot, plus progress between two post-boot probes, before any host
activity/instrumentation launch. This excludes frames committed during shutdown. Finish then
verifies persisted corrections/settings and original bytes, and cleans up the exact fixture.
Use `-Phase Cleanup` after a failed run when the device is accessible. Logs distinguish no observed
advancement from success. An unlock or manufacturer background setting may be needed; this does
not promise unattended timing, battery life or recovery while credential storage is locked.
