# Android UI fonts

The application bundles **Resource Han Rounded CN (源柔黑 CN) v0.990** by
Cyano Hao, based on Adobe Source Han Sans. It supplies the same rounded face
for Chinese, Latin text, punctuation and numbers without a network request.

- Upstream: https://github.com/CyanoHao/Resource-Han-Rounded
- Release: https://github.com/CyanoHao/Resource-Han-Rounded/releases/tag/v0.990
- Archive: `RHR-CN-0.990.7z`
- License: SIL Open Font License 1.1; the upstream copyright and license are
  distributed in `app/src/main/assets/licenses/Resource-Han-Rounded-OFL.txt`.
- Bundled weights: Regular 400, Medium 500 and Bold 700, unmodified font files
  renamed on disk to comply with Android resource naming rules.

`SpektraFontFamily` in `ui/theme/Theme.kt` applies the family to all 15 Material 3
typography roles. `res/font/theia_rounded.xml` applies the same weights to
the Android window theme. Screens inherit the family rather than overriding it
with system monospace or serif fonts. System font scaling remains enabled.

Keep the full CN character set so user filenames and metadata are not limited
to characters present in the application's current UI strings. Unsupported
characters can still fall back through Android's normal font resolver.
