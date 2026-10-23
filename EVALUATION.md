# Private evaluation

Keep fixtures and results under ignored `.ai/evaluation/` or another private directory. Use only
consented media or clearly licensed fixtures. The app does not upload evaluation media or results.

For a stalled personal installation, `IndexDiagnosticsTest` accepts the explicit argument
`-e indexDiagnostics true`. Run only that class with the matching test APK. It opens the existing
database read-only and reports aggregate media/stage counts and fixed indexing settings; it never
reconciles, resets, or exports records, identifiers, OCR, embeddings or images. Instrumentation
restarts the app process, so reopen the app afterward and do not treat its elapsed time as an
indexing benchmark. This opt-in diagnostic skips during the ordinary fixture suite.

`FailedPhotoDiagnosticsTest#reproduceFailedPhotoStagesWithAggregateExceptionSitesOnly` accepts
`-e failedPhotoDiagnostics true` on the personal debug installation. It reproduces up to eight
failed photo stages on the phone and reports only aggregate phases, exception classes, public
code locations and SDK error codes. Exception messages, raw stacks, media, names, URIs, OCR and
embeddings are not exported. This diagnostic does not write analysis records or preferences;
ordinary indexing can continue concurrently. It is not an accuracy or throughput benchmark.

For an exact before/after check, pass `failureCohortRun` with a fresh UUID and
`failureCohortMode=record`, then `replay` or `status`. References and original digests stay in
the target app's `no_backup/failure-diagnosis/UUID.json`; only completion/digest-match counts
leave the phone. Never pull or print that private file. Run `failureCohortMode=cleanup` with
the same UUID afterward. A malformed cohort is rejected without attaching parser messages
that might expose its contents. The ordinary suite uses only a synthetic malformed-input
regression and skips the opt-in reproduction.

The source-detail repair keeps the existing natural-pixel quality threshold and embedding version.
It requeues eligible failed photo-face stages once after updating; completed work, inaccessible or
excluded media, content/video failures and manual corrections are preserved. Later failures keep
the normal bounded retry policy. Native regressions cover both tiny excluded crops and successful
real source-detail recovery, plus the one-time repair's eligibility and persistence behavior.

The first unpaused foreground refresh also replaces the legacy queued request once, recording
that reset only after enqueue succeeds. Automatic refresh otherwise keeps existing work;
explicit Scan now and reindex replace the current request while retaining Gallery database
checkpoints. Charging, battery and storage constraints still apply. Native tests exercise an
actual 30-minute delayed WorkManager request, default KEEP behavior and explicit replacement
with zero initial delay and unchanged constraints.

Per-item failures keep three persisted attempts but continue in fresh slices after a fixed
10-second delay, avoiding cumulative global backoff across unrelated bad files. Resource and
provider deferrals still use WorkManager retry/backoff. A real-worker regression retries a
truncated public PNG to its limit, verifies fresh delayed requests, then completes a valid photo
without changing either original fixture. Retry-state diagnostics expose only fixed job names,
state/attempt/count aggregates, without work identifiers, payloads or timestamps.

For redistributable fixtures, the optional `PrivateEvaluationTest` reads `evaluation/manifest.json`
from test-only assets. Add a directory through `-PprivateEvalAssets=.ai/evaluation/assets`, build
the test APK and run that class on a selected phone. This packages those assets in the ignored
test APK; never publish it. The normal app APK does not include androidTest assets.

For private media, use the validation app's private storage instead of APK assets. Create a
fresh UUID directory at `no_backup/private-evaluation/UUID/evaluation/` under the validation UID,
stream the manifest and anonymous fixture files directly with `adb exec-in run-as` (no shared
temporary files), then pass `-e privateEvalRun UUID` to this test class. File reads are confined
to that UUID root. A supplied run with missing files fails rather than silently skipping.
Remove those exact staged files after evaluation. Keep source/label mappings and results locally
under ignored directories. Do not package consented private media into an installed APK.

For consented photos already on the phone, `tools/test-private-folder.ps1` runs a separate opt-in
aggregate-only diagnostic against an explicitly supplied `Pictures/.../` folder, using the personal
app's existing media permissions. It queries that exact MediaStore relative path, samples a bounded
number of images in ID order, and performs the photo worker's detection/source-detail retries:

```powershell
.\tools\test-private-folder.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -Folder 'Pictures/ConsentedSet/' -LogDirectory .ai/consented-folder -SampleLimit 5
```

It temporarily pauses ordinary indexing and restores the prior pause preference, including an
explicit host cleanup after a crash. It does not reconcile/reset the personal database or copy
images off the phone. No filenames, URIs, OCR, crops or embeddings enter reports. Outputs are
aggregate counts, elapsed time and sampled process memory/battery/thermal snapshots. Without
ground-truth labels these are not detection recall, matching/category accuracy, battery-drain
measurements or whole-library benchmarks. Existing folder permissions and originals are preserved.

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
`__refrigerator` channel. The Redmi also held a private-folder evaluation in `do_freezer_trap`;
foregrounding the existing app let the same evaluation finish. This helper detects those states and foregrounds the existing
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

## Controlled resource constraints

With matching validation app/test APKs on an authorized USB-charging Android 10+ phone, the opt-in resource
helper tests the real production scheduler and worker, using one owned public-domain image:

```powershell
.\tools\test-resource-constraints.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/charging -Mode charging
.\tools\test-resource-constraints.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/thermal -Mode thermal
# Also exercise the observed stale CHARGING status while no source is connected:
.\tools\test-resource-constraints.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/stale-charging -Mode charging -StaleChargingStatus
# Exercise the real OS low-storage constraint without filling the phone:
.\tools\test-resource-constraints.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/storage -Mode storage
```

The helper refuses existing sensor overrides. Charging mode temporarily reports unplugged power
and discharging status consistently (HyperOS can retain a charging status after debug unplug alone),
requires an enqueued job and no hash progress for15 seconds, then restores physical battery
reporting and requires real inference to complete. It also directly invokes the real worker
against the controlled signal to check its independent guard. The worker independently checks power at
boundaries because scheduler constraints and sensor signals can briefly disagree. Thermal mode temporarily overrides Android's
thermal status to severe, directly executes the real production worker against PowerManager
to require a retry without hash progress (JobScheduler may itself suppress a severe-thermal job), then
restores physical thermal reporting and requires inference to finish. It does not heat the device.
Both modes restore and verify physical reporting in `finally`, cancel only validation work and
remove the exact test-created fixture. These controlled API/OS checks do not measure natural
overheating, battery drain, low-storage behavior or unattended real-library throughput.
The validation activity stays active with a temporary keep-screen-on flag during these resource
checks. Unlock normally before running them; background/locked-phone scheduling is a separate check.

Storage mode requires an initially unforced, normal storage monitor. It briefly forces Android's
low-storage scheduling signal, observes an enqueued production request with no hash progress for
15 seconds, then resets the monitor and requires actual queued inference and unchanged fixture
bytes. The host restores the monitor in `finally` and verifies unforced normal reporting; it
refuses to overwrite existing logs or an existing forced/low state. Only fixed state/byte counts
are recorded, without volume identifiers. This does not fill storage, test natural out-of-space
behavior or exercise the worker's separate 200 MiB `StatFs` free-space guard.

The worker requires both a connected source and charging/full status from the sticky battery
intent. Android's `BatteryManager.isCharging` can remain false while physically charging because
its background-work signal is debounced; resource diagnostics record that signal separately.
No charging debounce or manufacturer power policy is changed by these tests.

## Modern media permission lifecycle

On an unlocked Android 14+ phone with the matching validation APKs installed and that disposable
installation denied shared-gallery access, run:

```powershell
.\tools\test-media-permissions.ps1 -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial YOUR_SERIAL -LogDirectory .ai/media-permissions
```

This opt-in check uses the production access buttons and real system denial/full/selected dialogs.
A test-only activity under a separate UID creates two licensed photos and one video in a unique
fixture album. The target app does not own those rows, so owner access cannot masquerade as a
permission grant. Selection chooses only that public album, then adds another item through Manage
selection. Production reconciliation must reflect the selected membership and hide stale rows
after revocation. Accessible originals are checked against their digests.
Full access is verified by the system grant and exact fixture reads; no extra full-library scan
is performed just to count fixtures. Ordinary indexing stays paused. No screenshots, private picker
text, gallery names or media are exported. The helper revokes/clears prompt flags only for the
fixed disposable validation package, deletes exact fixtures through their owner and resets only
validation derived data in `finally`. The personal installation is never granted, revoked or reset.
System picker UI varies by OS; a missing expected control fails the test rather than selecting
arbitrary private media. This harness is skipped by the normal suite.
If the system picker does not expose custom fixture albums, add `-ManualSelection`: select one
new public fixture and confirm, then add a second fixture through the next picker. Each selection
wait is bounded to six minutes; reconciliation rejects any selection outside the fixture album.
The validation normal-suite and permission helpers opt into a bounded test-only screen hold,
released at run completion or its timeout. This does not unlock keyguard or change system settings.
