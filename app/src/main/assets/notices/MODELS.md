# Bundled models and software

MobileFaceNet recognition weights: foamliu/MobileFaceNet, exported by Qualcomm AI Hub Models
v0.64.0. Publisher model card identifies the exported model as Apache-2.0. Original weights
are in the Apache-2.0 foamliu repository. Training provenance: ArcFace loss, MS-Celeb-1M
according to the publisher. A code license alone does not establish a model license: the
export's own model card is the basis for redistribution here. The dataset's demographic,
consent and generalization limitations remain; this is local visual grouping, not identity verification.

Source: https://huggingface.co/qualcomm/MobileFaceNet/tree/main
Export: https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.64.0/mobile_facenet-tflite-float.zip
Original: https://github.com/foamliu/MobileFaceNet
SHA256 (uncompressed TFLite): 2046ab776fba2ec7e420767ffe7eb74a7402a8412d3d7ea8b9f4ca8420e5f3d7
Input: two float32 RGB NCHW [1,3,112,112] tensors in [0,1]. Supply the same aligned face
to both. Export internally applies ImageNet normalization and horizontal flip/sum augmentation.
Output: [2,128], first row used, L2-normalized by this app; cosine comparison, versioned.
Alignment fits a similarity transform over five detected landmarks to the publisher's square
112-pixel reference (the source's 112-pixel early return precedes its padding code).
This CPU TFLite export does not require Qualcomm hardware.

Google ML Kit face-detection 16.1.7, image-labeling 17.0.9, text-recognition 16.0.1 (Latin):
bundled Android SDK distributions including models; proprietary Google API/ML Kit terms,
not Apache-licensed pretrained weights. https://developers.google.com/ml-kit/terms
https://developers.google.com/terms
Used through supported APIs, not extracted or redistributed separately. Bundled SDK terms
allow inclusion in applications. Google documents on-device media processing and metrics
network behavior. This application removes INTERNET and ACCESS_NETWORK_STATE permissions
and cannot upload through its own UID. Offline behavior is tested separately.

TensorFlow Lite 2.17.0, AndroidX/Compose/Room/WorkManager/Media3: Apache-2.0.
Kotlin/coroutines: Apache-2.0. Coil 2.7.0: Apache-2.0.
See APACHE-2.0.txt. Upstream copyright notices in redistributed dependency packages apply.

Test fixture (test APK only): astronaut.png from scikit-image v0.25.2, NASA photograph
of Eileen Collins, public domain (US government work). https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut
No gallery media or private identity evaluation data is committed.

Additional test fixture: grace_hopper.jpg from matplotlib v3.10.0 sample_data, official US Navy
portrait by James S. Davis, 20 January 1984, public domain (US government work), NH 96919-KN.
later-appearance.mp4 is generated from the NASA astronaut image and synthetic blank frames
using tools/make_video_fixture.py; no private media. All three are test-APK-only assets.

Additional test-only images from scikit-image v0.25.2: chelsea.png (Stefan van der Walt)
and coffee.png (Rachel Michetti, courtesy of Pikolo Espresso Bar). Both photographers
dedicated these images to CC0; https://scikit-image.org/docs/stable/api/skimage.data.html.
