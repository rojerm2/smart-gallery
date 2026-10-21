# Model provenance

See [packaged model notices](app/src/main/assets/notices/MODELS.md) for the pinned source,
SHA256, tensor contract, license distinction and test-fixture attribution. The 4 MB recognition
model is checked into Git as an ordinary binary, not an LFS pointer. All ML Kit models come
from the bundled Maven coordinates in app/build.gradle.kts. No runtime model fetch is used.

Model weights are third-party assets under their stated terms; the root LICENSE applies to
application code, not Google's proprietary pretrained models. Recognition configuration is
versioned separately from the embedding format. Performance on the target Redmi is pending.
