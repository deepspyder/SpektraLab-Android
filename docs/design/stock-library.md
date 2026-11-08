# Stock library

Mode: Operate. Scope: film and print-material selection inside the existing Android darkroom.

## Direction contract

THESIS: Choose film and paper like filters in a photo editor: the photograph dominates the screen, while small horizontal material thumbnails support rapid visual comparison. No large catalogue cards or paper list.

OWN-WORLD: Retain neutral charcoal, paper-colored text, rounded type and amber selection. Square paper labels from the user-provided P1/P2 style sit in a compact horizontal strip. Their face, paper edge and shadow share subtly rounded corners (12/200 source units). Their only relief is a thin paper edge and directional shadow; the outside remains transparent. Material and category tabs are text controls without bulky outlined chips; only the selected thumbnail has a border.

STORY: Open film or paper from the editor, inspect the live photograph, scroll material thumbnails and tap to try a stock. Compare with the original using a preview toggle. Switch to compatible paper in the same strip and finish from the upper-right action. Selected full stock name remains visible below the strip.

FIRST VIEWPORT: Compact 52dp header with back/title/info/Done; large Fit photo preview; bottom panel containing film/paper tabs, optional text category row, 76dp-wide stock choices with 88dp artwork and two short name lines, followed by the selected full name. At width >=700dp and landscape proportions, place a scrollable 340dp panel beside the preview. Preserve text scaling and lazy horizontal loading.

FORM: The user's specified photo-editor filter picker; code-led. Signature interaction: tap a material to update the same large preview without leaving selection; original/effect comparison reuses bitmaps without rendering. Selected material scrolls into view on entry and after compatibility changes.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Asset constraint: the user's final correction replaces the cartridge direction with remastered square labels from P1/P2. Thirteen matching panels are extracted as references; all31 labels use outlined type and clean color blocks. Missing profiles use matching brand-family labels. Every film/paper maps to its own material, with shared Portra800 artwork for push variants. Subtle paper edges replace the rejected fake3D canisters.

Delivery: portable outlined SVG and960×1120 PNG masters under `design/stock-artwork/`;384×448 lossless WebP runtime resources. WebP package bytes:376,912 versus697,202 for matching PNGs. Decode off the UI thread; bounded decoded cache:6MB. User explicitly stopped the cartridge/Figma attempt in favor of label extraction/remastering; local masters are authoritative. Existing photo-editor layout and rendering behavior are preserved.

Verification: the `stock-library` instrumentation audit uses the local user-provided `app/src/androidTest/assets/13mb.jpg` fixture. It is intentionally excluded from Git, as documented in `docs/export-memory-reproduction.md`. Supply that fixture locally before running this audit; the default synthetic `audit.dng` remains versioned for the general functional audit.
