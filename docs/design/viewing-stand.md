# Unframed photo table

Mode: Experience inside the existing Android darkroom.

The latest user correction replaces continuous six-frame film strips with a
free canvas. Show the actual images directly: no synthetic film stock edges,
perforations, paper border, curled edge, equal gate, or fixed six-column layout.
A future optional border must have believable curled material edges; that
feature is deliberately not implemented or enabled in this version.

Photographs keep their complete original aspect ratios. Landscape, portrait,
square and panorama photographs have different natural rectangles. The initial
placement is loosely staggered only to make first entry usable. It does not
snap or constrain subsequent placement. Each image can be dragged independently;
dragging empty table space pans the plane, and a two-finger gesture zooms/pans
the whole canvas. Zoom spans 35–600%. Double-tap zoom, zoom buttons, viewport
reset and the spatial loupe remain. Dragged photos come to the front. Placement,
stacking and viewport transform survive immersive viewing and saved-state
recreation. Reset changes the viewport, preserving the user's arrangement.

The image is never stretched, centre-cropped, rounded-masked, or rewritten by
the table. The viewer shows original file type and pixel dimensions (with EXIF
orientation accounted for), separate from the bounded decoded display preview.
Source files retain their actual size and encoding. Already-developed/current
previews retain their edited appearance rather than being replaced by a decoded
original. Transparent imagery is drawn with its alpha against the table.

Only visible photographs are drawn. When zoom/loupe display demand exceeds the
thumbnail resolution, after a 140ms pause the closest at most four visible
frames request detail serially. They share the existing six-entry 1400px cache.
There is no canvas-sized bitmap and moving/zooming does not render the photo
engine. Reusable-cache count is not a total memory claim.

Lamp switching animates the diffuser and photo light output over 360ms. Window
brightness is activity scoped (90% on, 20% off) and restored on exit; the system
brightness setting is not changed. This remains a display simulation, without
physical-device lighting or performance claims.

Accessibility supplies photo name, original format/dimensions, selection and
view actions, plus directional move actions per photograph. No row/column
collection is exposed for a spatial canvas. Existing immersive paging, sharing
of an explicitly labelled preview, source-preserving removal, error recovery
and import cancellation remain available.

Verification uses mixed ratios with four coloured corner markers, independent
photo-drag versus blank-table pan pixel checks, pinch/detail/cache assertions,
source-correct clicks, lamp/window brightness, rotation, viewer/loupe/removal,
and phone/large-font/tablet emulator captures.
