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
