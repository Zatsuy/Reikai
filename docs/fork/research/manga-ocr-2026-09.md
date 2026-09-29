# Manga lookup: user experience, models and cost (2026-09-28)

Research for Phase 6 (manga lookup), done before the owner chose its design (D-034). Two passes:
the reference apps' code in `../refs/` (Yomihon v0.4.2, Chimahon v2.4.5) and the public model and
runtime landscape (Hugging Face and GitHub APIs, release files downloaded to measure). "est." marks
an estimate; UNVERIFIED marks what could not be confirmed.

## What the closest apps do

| | Yomihon (Apache-2.0) | Chimahon (GPL-3.0) |
|---|---|---|
| Gesture | One tap, but only on chapters scanned beforehand; otherwise long-press and drag a box, then a sheet with the text (tap a character to look up from it) | One tap on a word after switching OCR on once; every page is read as it loads, the chapter around it 2 pages at a time |
| Finding text on-device | None: `UnavailableDetOcrEngine() // TODO` (`data/.../ocr/OcrRepositoryImpl.kt:150-160`); full-page scans go to Google Lens (unofficial endpoint, scraped key) | Only through downloaded native code: Google's closed on-device Lens engine (56 MB zip, memory-patching JNI shim) or a prebuilt `libpaddle_ocr.so` running PP-OCRv6 manga (17 MB zip, source not public) |
| Default reader | manga-ocr TFLite ports on LiteRT, 140 MB (fp32) or 21 MB (fp16), bundled: 164 MB of assets, APKs 188-256 MB | Google Lens (cloud) |
| Tap to character | The bubble's text laid out again inside its box, then the hit guessed (`ReaderOcrOverlayRenderer.kt:95-133`) | Real line boxes, characters spread evenly along each line (`chimahon/.../ocr/OcrHitTester.kt:80-159`); lookup text runs to the next punctuation; the matched length is highlighted on the page |
| Cache | `ocr_cache.db`; scans in a WorkManager foreground job | 120 pages in memory; JSON beside downloads or in `filesDir/ocr_cache/` |
| `.mokuro` | No | Yes, including a mokuro.moe source |
| Known problems | Coordinates shifted by auto-crop, AVIF, an arm32 crash | Coordinates after rotation, split and crop; text cut at webtoon page seams; out of memory while downloading models; a battery and heat warning in settings |

Neither app reads a page on the device with licence-clean parts. Both use the YOLO26n panel model,
which became AGPL-3.0 in 2026-09.

## Models

| Task | Model | Licence (code / weights) | Size | Evidence | Notes |
|---|---|---|---|---|---|
| Lines | **PP-OCRv6_manga det v0.2** ([HF](https://huggingface.co/Kellenok/PP-OCRv6_manga)) | Apache-2.0 tag; trained on Manga109-s and AnimeText (CC BY-NC-SA) | 1.8 MB fp32, 1.0 MB fp16 | manga recall 94.7% (stock small 90.7%) | line quads; created 2026-09-02, one author; Chimahon ships it through ncnn |
| Lines | PP-OCRv6 tiny / small (stock) | Apache-2.0 | 1.8 / 9.9 MB | tiny 317 ms at 2048 px on a Xeon; PP-OCRv5 mobile det about 9 ms at 640² on a Pixel 8a GPU | Japanese detection Hmean 76.6 / 82.3 |
| Blocks, lines, mask | comic-text-detector (mokuro's) | GPL-3.0 / GPL-3.0 | 76 MB .pt, 90-95 MB ONNX | houri-engine dropped it: DBNet finds 1.6-2.5x more text on device | GPL is compatible with the fork; untouched since 2023 |
| Bubbles | ogkalu RT-DETR-v2 | Apache-2.0 | 168 MB; int8 43.8 MB; v4-s int8 11.1 MB | none | bubble / text_bubble / text_free |
| Blocks | YOLO26n | AGPL-3.0 (leoxs22) or unclear (Kiuyha, Ultralytics-trained) | 2.8 MB int8 | 100-180 ms CPU at 640 px | blocks only |
| Lines, characters | meiki.text.detect / meiki.txt.recognition v0 | Apache code / LGPL-3.0 weights | 10.6-41.6 MB / 12.9-18.6 MB | desktop CPU 30-70 ms | the only true per-character boxes; trained on games; vertical is beta; manga accuracy UNVERIFIED |
| Line reader (CTC) | **PP-OCRv6_manga rec v0.2** | as its detector | 21.2 MB, 10.6 MB fp16 | manga CER 5.63% (hayai-v2.5 7.02%, stock small 10.04%) | CTC time steps give approximate character positions (PaddleOCR `return_word_box`) |
| Line reader (CTC) | PP-OCRv6 small / tiny (stock) | Apache-2.0 | 21.2 / 4.5 MB | PP-OCRv5 mobile rec about 9 ms per line on a Pixel 8a GPU | tiny's Japanese support contradictory (UNVERIFIED) |
| Bubble reader | manga-ocr base and ports | Apache / Apache | 444 MB; ONNX int8 117 MB; mobile fp16 21 MB | 1.6-3.4 s per page on an arm64 emulator | text only, no positions; invents text on empty crops |
| Bubble reader | Baberu OCR | Apache | about 121 MB int4+int8 | CER 3.5% (manga-ocr 4.2%) | text only |

Too large: Hayai v2.5-nova (628 MB), PaddleOCR-VL-For-Manga (1.9 GB). Excluded: manga-ocr-nar-preview
(CC BY-NC-SA weights). MangaDReC shows a PP-OCRv6 detector plus a small CTC reader matching
manga-ocr (exact match 81.8% against 81.5%).

## Runtimes (arm64, uncompressed / compressed; the fork packages native libraries compressed)

| Runtime | Licence | Size | Notes |
|---|---|---|---|
| **ncnn** 20260526 | BSD-3 | CPU 5.6 / 2.4 MB; Vulkan 10.0 / 3.7 MB | proven with PP-OCRv6 manga (Chimahon); small models often slower on the GPU |
| LiteRT 2.2.0 | Apache-2.0 | 8.6 / 3.7 MB with GPU | OpenCL on Xclipse goes through ANGLE-CL, garbage output reported on Xclipse 530/960; Exynos NPU only on 2500/2600 |
| ONNX Runtime 1.30.0 | MIT | 33.0 / 12.4 MB (a reduced-operator build: a few MB) | NNAPI deprecated since Android 15 |
| MNN 3.6.1 | Apache-2.0 | about 3.2 / 1.3 MB core | OpenCL, Vulkan |

Native code downloaded into `filesDir` and loaded with `System.load` works for sideloaded apps
(Chimahon does it); from targetSdk 37 the file must be read-only first. Google Play forbids it.
houri-engine found Vulkan and XNNPACK computing some models wrongly and an int8 detector finding
nothing: stay on fp16 or fp32 on the CPU.

## `.mokuro`

mokuro runs comic-text-detector, then manga-ocr per line. The file is JSON: `pages[]` with
`img_width`, `img_height` and `blocks[]` of `box`, `vertical`, `font_size`, `lines_coords` (4-point
quads) and `lines` (text). Its web reader places each block as a `writing-mode: vertical-rl` div at
`box` with `font_size` and lets Yomitan pick the character under the pointer; no per-character data.

## Estimates for the owner's devices (est.; no published Exynos 1380/1580 figures)

- Detection, 1 MB model, 960 px long side, 4 threads: 100-300 ms (Tab S10 FE), 150-400 ms (A54).
- Line reading: 10-40 ms a line, 50-200 ms a bubble; a full page of 30-60 lines 0.7-2.5 s on CPU.
- houri-engine measured about 1.7 s a page for detection and reading on a Snapdragon 8 Gen 3.
- Reading pages in the background costs about 1-2 s of CPU per page against 20-60 s of reading one:
  a few percent; battery and heat must be measured on the tablet.

## The design chosen (D-034)

1. When a page is shown, and 1-2 pages ahead at low priority, PP-OCRv6_manga det finds the text
   lines (960-1120 px long side); lines are grouped into blocks by distance and orientation, and
   furigana dropped by a short-side threshold (comictxt used about 18 px).
2. PP-OCRv6_manga rec reads each line (vertical lines rotated first) and keeps each character's
   CTC time step as its position; even spacing along the line is the fallback.
3. Per page: blocks, lines, text and character boxes cached. A tap: line, then character, then the
   block's text from there through the Yomitan engine, the same popup as novels, with the matched
   word highlighted. A tap on a page not read yet reads only that region.
4. Fallback: long-press and drag a box around text the detector missed (sound effects, handwriting).
5. ncnn on the CPU in the APK (about 2.4 MB per ABI); about 12 MB of models downloaded on request
   from the model's author, deletable in one tap (D-031).

Expected: under 50 ms from tap to popup on a page already read, about 0.3-0.6 s when the tap comes
first. First step of the phase: measure detection and reading on the tablet and phone before
building (as the Phase 2 spike did for Yomitan).

## Risks

- Licence chain: the manga model is tagged Apache-2.0 but trained partly on CC BY-NC-SA data. The
  owner chose it (never bundled, the app is not sold); the clean fallback is stock PP-OCRv6 small
  (manga CER about 10% instead of 5.6%). Manga109-s asks for attribution.
- A four-week-old model with one author; no Exynos benchmarks for any candidate.
- Character positions from CTC are approximate; sound effects still read at 26% CER; touching
  columns can merge.
- GPU paths unreliable on Xclipse and Mali, NNAPI deprecated, no NPU for these chips: CPU only.

Corrections to [landscape-2026-09.md](landscape-2026-09.md): comic-text-detector being GPL is not a
blocker for the GPL fork; Chimahon also has a PP-OCRv6 manga engine; Yomihon's default model is
local, but its full-page scans go through Lens.

Sources: [PP-OCRv6_manga](https://huggingface.co/Kellenok/PP-OCRv6_manga),
[comic-text-detector](https://github.com/dmMaze/comic-text-detector),
[PP-OCRv6](https://www.paddleocr.ai/main/en/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html),
[LiteRT PP-OCR models](https://github.com/john-rocky/LiteRT-Models/tree/main/ppocr),
[manga-ocr](https://github.com/kha-white/manga-ocr),
[manga-ocr TFLite](https://huggingface.co/bluolightning/manga-ocr-tflite),
[MangaDReC](https://github.com/muscgab/MangaDReC),
[CTC word boxes](https://github.com/PaddlePaddle/PaddleOCR/discussions/17150),
[mokuro](https://github.com/kha-white/mokuro), [mokuro-reader](https://github.com/ZXY101/mokuro-reader),
[Chimahon models](https://github.com/Chimahon/chimahon-local-models),
[houri-engine](https://github.com/PineappleTwilight/houri-engine),
[meiki](https://huggingface.co/rtr46/meiki.text.detect.v0),
[RT-DETR bubbles](https://huggingface.co/ogkalu/comic-text-and-bubble-detector),
[YOLO26n panels](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n),
[Android 17 native loading](https://developer.android.com/about/versions/17/behavior-changes-17),
[LiteRT on Samsung](https://developers.google.com/edge/litert/next/samsung),
[NNAPI migration](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide),
[AnimeText](https://huggingface.co/datasets/deepghs/AnimeText),
[Manga109](https://manga109.github.io/manga109-project-website/en/index.html),
[comictxt](https://github.com/Ceynou/comictxt).
