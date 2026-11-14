# Nikon NEF import

## Report and fix

A Nikon Z6 II user reported `RAW unpack: decoder reported corrupt data`.
That exact message came from Theia's own `error_count() != 0` check after
`LibRaw::unpack()` returned success. The same cumulative check also ran after
`dcraw_process()`.

LibRaw 0.22.2 explicitly lists Nikon Z 6 II as supported. Its
[`error_count()` documentation](https://www.libraw.org/docs/API-CXX.html#error_count)
defines the value as a count of **non-fatal** data errors during unpack, such as
out-of-range samples. The Nikon decoder can report such a sample and continue
decoding. A nonzero count is not the failure status of `unpack()` or
`dcraw_process()`.

The shared preview/export decoder now checks each operation's actual return
status and logs non-fatal data warnings with camera identification. It still
rejects failed open/unpack/process calls, empty or unexpected output formats,
inconsistent output byte counts and allocation failures. It does not substitute
an embedded JPEG for RAW data, change demosaicing, white balance, color space,
preview resolution or export resolution.

The code change is deliberately limited to RAW decoder status handling and
formatting of the touched section. MIME/file detection is not the cause of the
reported screenshot: that file already reached the native RAW unpacker.

## Camera fixture verification

The user's failing file is not available. Six public, CC0 camera originals from
the [raw.pixls.us](https://raw.pixls.us/) repository provide Z6 II regression
coverage, not reproduction of that user's exact warning:

| Fixture ID | Repository mode | SHA-256 |
| --- | --- | --- |
| 4160 | 14-bit lossless compressed | `1b67d15817dbd4143a1f1089e2f09e319a2a2327c124e2643284509dd7621030` |
| 4161 | 14-bit lossy compressed | `c79fa1f8986ba059771f125af283dc3b0ed8bd27c46db9e4f83d2a0e3fb34059` |
| 4162 | 14-bit mode (10) | `4c75e683bd4e751615c93842f519579cde8e0e7fbee3cc58ba7f6a1a23f060dc` |
| 4163 | 12-bit lossless compressed | `04ad59c3b97609e72a54ad277ab433444b12392b032207ca7244571f60ab2514` |
| 4164 | 12-bit lossy compressed | `85e9ed4bce4feb439e9e05c3845d557b76d10080c239358821a00759afd9314b` |
| 4165 | 12-bit mode (6) | `b0c7a24a4efd41a45ce03bac7eae3056bc5f8fc9dfbd9bdb08b569ab42734205` |

Repository camera identifier: `Nikon / Z 6_2`. Original filenames are
`CZP_0299.NEF` through `CZP_0304.NEF`. Download URLs follow
`https://raw.pixls.us/getfile.php/<ID>/nice/<repository filename>.NEF`;
the [repository index](https://raw.pixls.us/json/getrepository.php) contains
the original links, checksums and CC0 notices.

These approximately 220 MB of camera files stay local under
`.tmp/nef-regression/`, outside Git and outside the APK. Push six files named
`z6ii-4160.NEF` through `z6ii-4165.NEF` to the target application's external
files directory under `nef-regression/`. Then run the opt-in instrumentation
audit:

```powershell
adb shell am instrument -w -e audit nef-import com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation
```

The audit checks finite ProPhoto preview/export samples for all six files,
full editor import/render for lossless and lossy files, rejection of an
incomplete NEF transfer, and cleanup of temporary RAW files. Per-file results
are written to `functional-audit/nef-camera-results.json` in the application's
external files directory.

The unmodified six files also decode successfully before the fix. They verify
that existing Z6 II support is preserved; the user screenshot and LibRaw API
semantics establish the incorrect warning-to-failure conversion. A readable
recovered output does not establish that an input file is pristine. Truly
damaged or incomplete originals may still need to be copied again from the
camera card. Testing is on an Android emulator, not the reporting user's device.
