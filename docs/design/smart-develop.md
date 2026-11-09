# Smart develop

Mode: Operate. Extend the established rounded neutral-charcoal/amber darkroom.

The user explicitly chose three available styles: Natural, Cinema, Vintage.
Cinema is the first-run selection based on the earlier stated preference. The
choice is user-controlled and remembered; automatic processing can be switched
off. Home places a compact Smart Develop entry immediately before Import. The
editor's existing top photo queue has a 48dp rounded AutoAwesome action. The
sheet uses a matching emblem, three icon/radio rows and one import switch.
Natural uses a rounded sun, Cinema rounded film frames, Vintage a rounded
flower/aperture. Avoid decorative raster logos, extra full-width editor rows,
or imposing another modal before every import.

This version is deterministic local image analysis, not a semantic AI model
and not a guarantee of an objectively best photograph. It measures source
brightness percentiles, clipping, shadows, saturation, dominant broad colour
fractions and neutral samples from a small preview. It ranks a constrained
compatible set of existing film profiles for each style, pairs a compatible
paper/print film, and derives bounded tonal/colour/grain/halation/vignette
settings. Low-light scenes get restrained grain and limited shadow lift;
high-contrast scenes prioritize highlight-aware exposure; near-monochrome
sources receive no saturation boost, while split-toning may still add colour.
The engine retains authority for auto exposure
and neutral print filters; no solved exposure is added a second time.

Import analyzes newly edited photos individually in a serial queue with a
mutex shared by the active image loader. It decodes at max320px and samples
at most roughly25k pixels. It writes per-photo parameter sidecars but never
opens/renders the engine for background queue items. The active photo renders
normally with its recommendation. RAW uses embedded preview where available,
otherwise a320px native decoder preview with a guarded bounded float copy.
The full RAW frame never crosses to managed memory for this analysis path.
Batch export resolves any pending fresh-image recommendation before rendering.

Any existing sidecar wins over automatic import. Reimport restores edits,
regardless of the currently selected default style. Explicit Apply to current
photo is the only action that replaces an existing tone/material treatment;
it retains crop, rotation, flips and other geometry, and participates in
undo/redo. Automatic imports seed a baseline undo step. Originals stay untouched.
Changing the default style affects subsequent imports; it does not silently
rewrite the current queue. The sheet's Apply action is explicit and undoable.

Processing states disable style changes and Apply during active photo rendering
or export. Background queue progress is reported without blocking ordinary
editing. Analysis failures leave manual editing available. Generation checks
and cancellation keep results attached to the correct URI. Provenance/style
summary live in sidecars, not the native parameter wire schema.

Validation plan: exercise different synthetic daylight/low-light scenes, all
three user selections and preferences, saved-edit precedence, geometry and
undo/redo on explicit style application, automatic switch off, RAW preview,
rapid reimport cancellation and batch preparation. Final source logs pass on
phone, 1.3x enlarged text, tablet and landscape. The landscape run reaches
existing Home controls by accessibility scrolling before interaction; the
application APK was unchanged. The actual emulator composites are
`design/smart-develop-preview.jpg` and
`design/smart-develop-styles.jpg`. No human-rated aesthetic dataset,
face recognition, hardware latency benchmark or cloud upload claim is made.
