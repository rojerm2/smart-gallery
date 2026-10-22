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

Identity matching evaluation uses the first usable face only; use single-person images for that
measurement. Separate detection coverage, quality exclusions, missed matches, false identity
merges, review volume, category precision/recall and resource measurements. For multi-person
detection, `expectedFaces` measures count coverage but does not substitute for annotated bounding
boxes/IoU evaluation. Per-category counts are reported only over explicitly annotated labels.

Use `calibration` and `heldout` split values. Select thresholds from calibration only, freeze the
configuration, then evaluate heldout data. A tiny or unrepresentative sample does not establish
reliability. Record sampling limitations, device/OS, model/config versions and test date locally.
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
