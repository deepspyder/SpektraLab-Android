# Android large-image export reproduction — 2026-11-02

## Environment

- Android x86_64 emulator `emulator-5554`, model `sdk_gphone64_x86_64`.
- Vulkan: Goldfish GFXStream backed by NVIDIA GeForce RTX 5060.
- ART heap growth limit observed in crash: 201,326,592 bytes (192 MiB).
- Actual Xiaomi device and original camera RAW were not connected/provided.

## Reproduced failures before the fix

1. Generated textured JPEG: 3072×4096, 8,913,343 bytes. Original-size export crashed.
   `ColorPipeline.bitmapToLinearSrgb` attempted a 150,994,960-byte Java allocation
   while retaining the full-frame ARGB array and the editor preview.
2. Generated uncompressed Bayer DNG: 5200×3900, 40,560,586 bytes. Original-size
   export crashed in `RawDecoder.nativeDecodeRaw` allocating 243,360,016 bytes
   for a Java FloatArray. This synthetic DNG tests decoder/memory behavior; it
   does not validate every vendor-specific RAW codec.

Both exceptions were uncaught `OutOfMemoryError` (an Error, not an Exception).
These are confirmed memory bugs, distinct from the user's `VkResult -4` device-loss
report. The device-loss error was not reproduced on the emulator.

## Fix

- Export input conversion/RAW decoding remains in native-owned float storage.
  `spk_open` copies synchronously, then input storage is released with `use`/finally.
- RAW decoder releases LibRaw working planes before expanding RGB16 to float RGB.
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
Thus mobile GPU pressure remains substantial even after the managed-heap
crashes are fixed. No claim is made that the Xiaomi Vulkan device-loss issue
is fully solved. A size-bounded Vulkan pool-reuse experiment did not reduce
total allocations and was reverted. No shared engine rendering changes remain.

## Repeat

Generate RAW with `python tools/make_audit_dng.py --width 5200 --height 3900
--output /path/to/repro-40mb-5200x3900.dng`. Push the chosen fixture to
`/sdcard/Android/data/com.spektralab.android/files/functional-audit/`.

Run instrumentation `com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation`
with `-e audit large-export -e input <fixture-name> -e exportRes 0`. Optional
arguments: `-e edges true`, `-e format PNG`, `-e format TIFF`. For numerical
regressions use `-e audit export-memory`; for progress/cancellation use
`-e audit export`.

Local reproduction logs and JSON reports: `E:/Code/SpektraLab/.tmp/export-repro/`.
Key files: `current-before-crash.txt`, `raw-before-crash.txt`,
`jpeg-final-results.json`, `raw-final-results.json`, `final-logcat.txt`,
`parity-verified-results.json`, `progress-verified.txt`, `build-verified.txt`.

On this host Gradle's Windows Unix-domain loopback failed with the default
temporary path. A process-local `JAVA_TOOL_OPTIONS` using a short writable
`java.io.tmpdir` and `jdk.net.unixdomain.tmpdir` allowed the cached offline
Gradle 8.13 build; project-wide Java settings were not modified.
