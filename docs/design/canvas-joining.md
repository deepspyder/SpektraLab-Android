# Viewing-stand canvas joining

The viewing stand keeps photos as independent, full-aspect-ratio rectangles on the borderless canvas. A drag near a neighboring edge may preview a slim amber seam guide. On release, the nearest compatible top/center/bottom or left/center/right anchor joins when the screen-distance budget is 18dp and at least 30% of the shorter shared edge aligns. A deep overlap above 26% of the smaller rectangle remains a deliberate stack.

Joining uses a 240ms `FastOutSlowIn` translation and a fading seam guide. In an overlap, only the upper photo's covered region transitions toward 0.58 opacity over 120ms as overlap depth increases; uncovered pixels retain their original alpha and opacity. The effect uses existing decoded previews and rectangle intersections, with no bitmap composite or engine rerender.

Each photo remains independently draggable. Pulling either photo apart cancels the visual join without a group lock or stitched export. The saved state records final positions and order, not a transient animation frame. Interruption settles at the visible position, and Compose animation honors the system animation scale.

The floating loupe owns drags that begin inside its actual root bounds, so the underlying canvas does not pan or snap; its exclusion bounds are cleared when the loupe leaves the composition. In the immersive viewer, the loupe is positioned from the full-viewport origin and moves independently while the zoomed image remains stationary outside the lens and shadow.

The behavior was reviewed with the production ViewingStand on Android emulator captures in `design/viewing-stand-joining-preview.jpg`. Phone, large-font phone, tablet, landscape, and reduced-motion joining audits passed, as did the five-case gallery regression. These captures do not establish hardware frame-time performance.
