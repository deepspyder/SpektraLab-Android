# Top edit-queue workbench

Mode: Operate. This is the editing session queue, separate from the free-canvas
viewing stand.

The primary path is see the current photo, tap another thumbnail, continue
adjusting. The thumbnail workbench is always visible directly below the editor
toolbar in Shoot, Print and Grade, on phone and expanded layouts. It is outside
the scrollable parameter area and occupies layout space rather than covering
the photograph. The old misleading image overlay labelled Fit to screen is
removed; photo preview remains ContentScale.Fit.

The strip is 72dp tall normally (60dp image targets + 6dp vertical padding),
56dp in windows shorter than 500dp (48dp image targets + 4dp padding). It uses
shared rounded shapes, dark surface tones, amber current-photo border, source
aspect-preserving thumbnails, numeric indices and no repeated filenames.
The main toolbar already shows the active filename. A left count button opens
photo management; the right 48dp Add button appends photos and selects the
newly added photo. Cancelling the picker retains the queue and current edit.

Horizontal lazy scrolling keeps the selected image visible after switching,
queue changes and long-session navigation. Selection, numbering and accessible
labels follow the view model's actual currentIndex. Visible items reuse small
already-developed previews or cached thumbnails; opening the workbench does
not render the engine. Busy/exporting/batch states disable photo switching
and adding as before.

Management remains the existing bottom sheet for metadata, previous/next,
removal and empty-session recovery. It is optional for ordinary switching.
Expanded rail layouts require at least 500dp window height; shorter landscape
windows use the bottom navigation so every destination stays visible.
Cropping and immersive preview prioritize the photograph; export and the
independent viewing stand retain their dedicated layouts.

Validate direct switching without the sheet, top-strip bounds above preview,
parameter-tab visibility, 24-photo auto-scroll and long names, minimum 48dp
targets, edit restoration, both picker-cancel paths, source-preserving removal,
single/empty state and Back. Capture phone, large text, phone landscape,
tablet and tablet landscape from an Android emulator. No new web detector or
hardware performance claims apply to this native layout change.
