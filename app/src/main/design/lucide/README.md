# Editor toolbar icon sources

The Figma editor toolbar layers are named `arrow-left`, `undo-2`, `redo-2`, and `columns-2`.
These SVG files are the corresponding upstream Lucide assets, downloaded on 2026-11-04.
`provenance.json` records each source URL and the downloaded bytes' SHA-256 digest.

Android drawables `ic_darkroom_*.xml` retain the SVG path data, rounded 2-unit strokes,
and 24-unit viewBox. The rounded rectangle in `columns-2` is expressed as equivalent
quarter-ellipse path arcs for Android VectorDrawable. Directional arrows use Android
auto-mirroring; the comparison frame does not.

See `LICENSE`. A copy is included in the APK at `assets/licenses/lucide-LICENSE.txt`.
