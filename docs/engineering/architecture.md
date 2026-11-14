# Theia code organization

The Android app uses packages within the existing `:app` Gradle module. Package
boundaries organize responsibilities without adding runtime services, wrappers,
dispatchers, dependency injection or new build dependencies.

## Packages

| Package under `com.spektralab.android` | Responsibility |
| --- | --- |
| Root | Android manifest entry points and compatibility identities for saved navigation enums. |
| `app` | Application routes, photo-picker routing and startup content. |
| `feature.home` | Home workspace and session entry points. |
| `feature.editor` | Existing editor state, history and render/export coordination. |
| `feature.editor.ui` | Editor screen, preview, photo tray, metadata and parameter layout. |
| `feature.editor.ui.panels` | Separate capture, print and grading controls. |
| `feature.editor.crop` | Crop workspace and gesture overlay. |
| `feature.library` | Film/paper browsing and bounded stock-artwork cache. |
| `feature.lighttable` | Viewing workspace, free canvas, gestures, joining and magnifier. |
| `feature.smartdevelop` | Smart Develop presentation and style selection. |
| `feature.export` | Export settings, batch selection and progress presentation. |
| `feature.about` | About screen and project/source links. |
| `core.model` | Editing parameters and export-recipe values, including existing JSON contracts. |
| `core.geometry` | Pure crop/gate aspect calculations. |
| `core.color` | Display P3 profile generation. |
| `core.develop` | Existing per-photo analysis and Smart Develop recommendations. |
| `data.local` | Sidecar and recipe file persistence. |
| `data.export` | EXIF/GPS metadata, TIFF writing, atomic output publication and export diagnostics. |
| `ui.components` | Reusable controls, chrome, comparison and source-photo labels. |
| `ui.theme` | Existing colors, typography and shapes. |
| `engine` | Existing Kotlin/JNI interfaces, native pixel ownership and color pipeline. |

Application routing composes feature screens. Features reuse the editor state,
core calculations, data helpers, engine and shared UI. Shared packages do not
import feature screens or application routing. The stateful photo tray belongs
to the editor UI rather than the reusable component package.

`EditorViewModel` remains the single lifecycle owner of its state machine and
native handles. The package refactor preserved method behavior, coroutine scopes,
dispatchers and render ordering. Subsequent import/export performance changes
are documented in [full-resolution-performance.md](../performance/full-resolution-performance.md).
Publication helpers do not own editor state or native sessions.

## Compatibility and behavior

- Keep `applicationId`, namespace, manifest activity/application names,
  FileProvider authority, JNI class names and native library names unchanged.
- Preserve the existing fully qualified names of enums saved by Compose:
  `AppRoute`, `EditorTab` and `FilmFilter`. Their source is grouped in the root
  `SavedNavigationState.kt` to avoid changing serialized enum identities.
- Keep preference keys, sidecar/recipe filenames, JSON keys, URI identities and
  exported names/paths unchanged.
- Keep UI function bodies, state/callback ordering, layout dimensions, animation
  timing, drawing, asset decoding and render algorithms unchanged. Only package
  declarations, imports and visibility needed across extracted files change.
- Keep native source, resources, fonts, shaders, Gradle dependencies and release
  settings unchanged. No startup or APK-size optimization is part of this work.

## Working in the codebase

Use explicit imports for dependencies between application packages. Put new
controls with their feature unless they are reusable and independent of a
feature's ViewModel. Keep model serialization and data persistence separate
from presentation. Do not move JNI owners without coordinating native symbol
names and lifecycle contracts.

Run the source-layout check from the repository root:

```powershell
python tools/check_package_boundaries.py
```

For Android validation, build `:app:assembleDebug`,
`:app:assembleDebugAndroidTest` and `:app:assembleRelease`. Existing on-device
audits exercise import, automatic development, film/paper selection, crop,
history, metadata, batch export and light-table interactions. Their runner
identity remains `com.spektralab.android.FunctionalAuditInstrumentation`.

## Refactor validation — 2026-11-10

- Debug, instrumentation APK and signed Release builds passed, including
  release vital lint. Application ID, version, signing certificate, entry-point
  classes and the three saved-navigation enum identities were retained.
- A before/after source comparison checked all 130 original top-level
  declarations. Implementations were identical after excluding package/import
  declarations, required file-private-to-internal visibility changes and comments.
- The initial covering installation preserved hashes for 217 existing
  preference/sidecar files; the final repeated upgrade check preserved all 248
  files then present after the audit fixtures had been added.
- The 149 native checks and four effect checks passed on both versions, with
  identical result details after excluding timings.
- All 15 audit suites passed: `native`, `effect-baselines`, `theme`,
  `import-selection`, `smart-develop`, `stock-library`, `editing-feedback`,
  `batch-selection`, `photo-stage`, `home-viewing-stand`, `canvas-joining`,
  `gallery`, `export-layout`, `theia-brand` and `ui-layout`.
- Two existing audit helpers needed updates: the photo-management action now
  uses its current accessibility label, and the layout audit scrolls virtualized
  stock choices before clicking. Both old failures were reproduced on the
  original APK. Functional assertions were retained; the other nine existing
  audit files changed imports only.
- APK comparison found 190 identical native/resource/asset/manifest entries,
  including all six native libraries. Generated ART profile files were rebuilt
  for the relocated DEX references. Both signed APKs were 54,949,898 bytes.
- App content in the session, About, attribution and export captures was
  pixel-identical after excluding the Android status/navigation bars.

Runtime evidence comes from the Android emulator. It checks behavior and render
equivalence, rather than claiming identical benchmark timings on every device.
Some existing screenshot audits use the ignored local fixture
`app/src/androidTest/assets/13mb.jpg`; supply that fixture before reproducing
those audits. Local APKs, detailed logs, fixture images and screenshot evidence
remain outside version control.
