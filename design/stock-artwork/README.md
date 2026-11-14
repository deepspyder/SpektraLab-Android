# Stock artwork

32 remastered square paper labels cover the application's 25 film profiles and
9 paper / print-film profiles. Portra 800 Push +1 and +2 reuse the base label.
The user's P1 Fujifilm and P2 Kodak reference sheets define the label palette,
large film-speed numerals and brand hierarchy. Thirteen matching panels were
extracted into `reference-panels/` for comparison; final artwork is reconstructed
with clean color blocks and outlined type so collage artifacts and watermarks
are not included. Missing film and paper labels use the same visual families.
Softly rounded faces (12px radius on a 200px face) clip all color bands consistently.
Only a thin right/bottom paper edge and directional shadow add relief. There
are no cartridge bodies, spools, film leaders or heavy perspective effects.
Names and trademarks belong to their owners; reconstructed labels are
illustrative identifiers rather than official packaging photographs.

- `svg/`: transparent editable vectors with text outlined as paths.
- `png/`: 960×1120 transparent PNG masters.
- `stock-artwork-board.svg`: complete vector board suitable for Figma import.
- `preview-dark.png`: review sheet composited over the application's dark surface.
- `artwork-manifest.json`: stock-to-source mapping.
- `reference-panels/`: separated matching user-provided label panels, for reference only.
- `format-comparison.json`: per-image runtime WebP and equivalent PNG byte sizes.

The current user direction explicitly drops the cartridge/Figma construction
attempt in favor of the supplied square labels. These local remasters are the
authoritative sources, not exports from a completed Figma canvas.

Android uses only 384×448 transparent lossless WebP in `drawable-nodpi/`.
Combined size is 388,958 bytes with provenance metadata, versus 719,494 bytes
for equivalent PNGs. Decoded RGBA storage is 688,128 bytes per visible bitmap;
`StockArtworkImages` limits its reusable cache to 6 MB and decodes on IO.
Visible Compose nodes can retain bitmaps beyond that cache limit, so it is a
cache budget, not a claim that all image memory is bounded to 6 MB.

Rebuild sources with `tools/design_stock_icons.py`, then render via
`tools/render_stock_icons.cjs`. The authoring scripts use FontTools and Sharp
from the existing workspace tool environment. Masters stay outside the APK.

The CineStill 800T label extends the established paper-label family. No official
CineStill packaging reference was supplied; its warm white, black and amber
face is an authored illustrative identifier, not a packaging photograph. The
model inherits Vision3 500T data, but the label is separate so the catalogue
does not misidentify one stock as the other. Existing runtime labels are unchanged.

To rebuild only this addition and its review board, use
`python tools/design_stock_icons.py --stock cinestill_800t`, then
`node tools/render_stock_icons.cjs cinestill_800t --board`. The one-stock path
preserves every other runtime WebP and master.
