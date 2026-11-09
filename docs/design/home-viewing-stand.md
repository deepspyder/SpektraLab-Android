# Home viewing-stand entry

Home pairs the filled amber **导入照片** action with a 52dp tonal **观景台** action and PhotoLibrary icon. Both use the existing Material theme and 12dp action shape; the stand action exposes the label **打开观景台**.

The stand opens from Home with or without an editing session. Its multi-photo picker is independent of the editor-session picker, and cancelling it leaves the user on the stand. The Home back arrow and system Back return to Home. Home and editor stand routes receive the same root-hoisted `FilmCanvasState`, preserving photo placement when moving between those entry points. Editing a photo from the Home stand opens the editor on its **拍摄** tab.

See [DESIGN.md](../../DESIGN.md) for the authoritative palette, typography, shapes, and Home component rules. The emulator review and configuration evidence are in `.impeccable/review/home-viewing-stand/`.
