# Android large-image / RAW export reproduction — 2026-11-03

## Environment

- Android x86_64 emulator `emulator-5554`, model `sdk_gphone64_x86_64`, cold-booted after the 50 MP render saturated the earlier emulator session.
- Vulkan: Goldfish GFXStream backed by NVIDIA GeForce RTX 5060.
- ART heap growth limit observed in crash: 201,326,592 bytes (192 MiB).
- The actual Xiaomi phone was not attached; validation used its supplied DNG files on the emulator.

## Reproduced failures before the fix

1. Generated textured JPEG: 3072×4096, 8,913,343 bytes. Original-size export crashed.
   `ColorPipeline.bitmapToLinearSrgb` attempted a 150,994,960-byte Java allocation
   while retaining the full-frame ARGB array and the editor preview.
2. Generated uncompressed Bayer DNG: 5200×3900, 40,560,586 bytes. Original-size
   export crashed in `RawDecoder.nativeDecodeRaw` allocating 243,360,016 bytes
   for a Java FloatArray. This synthetic DNG tests decoder/memory behavior; it
   does not validate every vendor-specific RAW codec.
3. Repository 50 MP `raw.dng`: opening its editor preview previously reached
   `ColorPipeline.proPhotoLinearToSrgbArgb` and attempted a 50,331,664-byte
   ARGB allocation while the ART heap had only about 25 MB free. The OOM
   happened during image import, before the user pressed Export.
4. User-supplied Xiaomi DNG (`.tiff` extension, 33,666,972 bytes, 8192×6144 CFA):
   extension-only classification treated it as a regular RGB TIFF. Its embedded
   TIFF DNGVersion tag identifies it as DNG; the old path produced only a
   1536×2048 decoded frame in the audit.

Both exceptions were uncaught `OutOfMemoryError` (an Error, not an Exception).
These are confirmed memory bugs, distinct from the user's `VkResult -4` device-loss
report. The device-loss error was not reproduced on the emulator.

## Fix

- Export input conversion/RAW decoding remains in native-owned float storage.
  `spk_open` copies synchronously, then input storage is released with `use`/finally.
- RAW decoder releases LibRaw working planes before expanding RGB16 to float RGB.
- DNGVersion is detected in the TIFF IFD for `.tif/.tiff` imports, routing renamed
  DNGs through LibRaw rather than the regular RGB bitmap decoder.
- The interactive RAW preview is resampled in native code to a 2048 px long
  edge before any full-frame Java ARGB preview allocation. Full export still
  decodes the complete source and retains its original output dimensions.
- Half-size demosaic is selected from actual source dimensions only if it still
  covers the requested output long edge. Original exports keep full resolution.
- Native RGBA16 export results are read in 32-row strips. No full-frame Java
  ShortArray or ARGB IntArray is created in the single/batch save path.
- Close the preview session before source decoding, and close the export GPU
  session before output encoding; retained native result storage remains valid.
- Release temporary Bitmap/native storage deterministically; publish only
  completed MediaStore entries and clean up failed entries.
- Log `SpkExport` memory reports at input-open/render-complete/failure and
  JNI engine errors for future real-device GPU diagnosis.

## Verified results

| Case | Result | Saved dimensions |
| --- | --- | --- |
| JPEG original | PASS | 3072×4096 |
| JPEG original with 135 film border | PASS | 3955×4282 (border expands canvas) |
| Synthetic 40.56 MB DNG original | PASS | 5200×3900 |
| Synthetic DNG at 2560 | PASS | 2560×1920 |
| Repository 13.65 MB JPEG original | PASS | 6144×8192 |
| Repository 50 MP RAW single export | PASS | 6144×8192; editor preview 1536×2048 |
| Repository JPEG + RAW original-resolution batch | PASS | Both 6144×8192; 41.6 s on emulator |
| User Xiaomi DNG/TIFF with `.tiff` extension | PASS | 6144×8192 through LibRaw |
| User Xiaomi DNG/TIFF + JPEG original-resolution batch | PASS | Both 6144×8192 |
| PNG original | PASS | 3072×4096 |
| Display P3 TIFF original | PASS | 3072×4096 |
| Native/managed bitmap input parity, including translucent pixels | PASS | maximum rendered difference ≤1/65535 |
| Native/managed RAW input parity | PASS | exact rendered RGBA16 |
| TIFF native/array writer parity, partial final strip | PASS | exact bytes |
| Native result read after closing GPU session | PASS | result lifetime retained |
| Export progress, single/batch cancellation, missing-input cleanup | PASS | progress clears on all paths |

Debug APK and instrumentation build succeed for arm64-v8a and x86_64. Only
x86_64 was executed. No release artifacts were replaced by this task.

## Remaining GPU pressure

Before closing the export session, engine-tracked GPU buffer storage was
1366.6 MiB for the 12.6 MP JPEG and 2188.8 MiB for the 20.3 MP RAW. These values
are buffer-ledger measurements at render completion, not total process/RSS
measurements. Scratch live high-water was 1056.0/1702.0 MiB respectively.
The 50 MP emulator batch's tracked transient scratch high-water reached about
3.94 GiB and its free Vulkan pool about 4.13 GiB. This is the engine buffer
ledger on an x86 emulator backed by a discrete RTX 5060, not a measurement of
physical phone VRAM or available mobile memory. Full-size exports passed there,
but the original phone's `VkResult -4` device-loss report was not reproduced or
verified on Xiaomi hardware. Large-frame GPU pressure remains a device-specific
risk; this change fixes confirmed ART preview OOM and DNG/TIFF routing, not the
unreproduced Vulkan driver/device loss. A size-bounded Vulkan pool-reuse
experiment did not reduce total allocations and was reverted. No shared engine
rendering changes remain.

## Repeat

The real-photo audits require local fixtures in `app/src/androidTest/assets`:
`13mb.jpg`, `raw.dng`, and `xiaomi-dng.tiff`. These user-supplied photos are
not included in this commit. With those files in place, use `-e audit assets-batch`
for the JPEG + renamed DNG full-resolution batch check. The `export-memory`
parity audit also requires `raw.dng`.

Generate RAW with `python tools/make_audit_dng.py --width 5200 --height 3900
--output /path/to/repro-40mb-5200x3900.dng`. Push the chosen fixture to
`/sdcard/Android/data/com.spektralab.android/files/functional-audit/`.

Run instrumentation `com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation`
with `-e audit large-export -e input <fixture-name> -e exportRes 0`. Optional
arguments: `-e edges true`, `-e format PNG`, `-e format TIFF`. For numerical
regressions use `-e audit export-memory`; for progress/cancellation use
`-e audit export`.

Local reproduction logs and JSON reports: `E:/Code/SpektraLab/.tmp/export-repro/`.
Key current files: `repo-jpeg-preview-fix-full-results.json`,
`repo-raw-preview-fix-full-results.json`, `batch-real-dng-tiff-final-results.json`,
`xiaomi-tiff-native-dng-results.json`, `parity-dng-tiff-final-results.json`,
`final-assets-build.txt`, and `release-with-dng-tiff-fix.log`.

On this host Gradle's Windows Unix-domain loopback failed with the default
temporary path. A process-local `JAVA_TOOL_OPTIONS` using a short writable
`java.io.tmpdir` and `jdk.net.unixdomain.tmpdir` allowed the cached offline
Gradle 8.13 build; project-wide Java settings were not modified.
