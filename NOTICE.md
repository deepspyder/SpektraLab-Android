# Theia — attribution and license notices

## Application code

Theia, formerly SpektraLab Android, is distributed under **GPL-3.0-or-later**.
See [LICENSE](LICENSE) for the GNU General Public License version 3 text.
Existing upstream copyright and license notices remain in effect.

Repository: https://github.com/deepspyder/SpektraLab-Android

## spektrafilm

Theia is based on **spektrafilm by Andrea Volpato**.

- Canonical source: https://github.com/andreavolpato/spektrafilm
- Original spektrafilm profiles, LUTs and direct derivatives: **CC BY-SA 4.0**.
- The complete original notice, preamble and license are preserved verbatim in
  [licenses/SPEKTRAFILM_LICENSE.txt](licenses/SPEKTRAFILM_LICENSE.txt).

The license copy comes from the shared checkout's
`license/SPEKTRAFILM_LICENSE.txt`. Its existing attribution requirements and
distinction between legal terms and the author's additional requests are retained.
When distributing changed profiles or LUTs, preserve the source attribution and
identify the changes as required by their license.

The shared engine also contains separately authored product profiles under
`engine/resources_product/`. Their provenance is documented in that directory's
`NOTICE.md`; resource sync copies it into the application as
`app/src/main/assets/engine/resources/Product-profiles-NOTICE.md`.
Consult that notice and the notices shipped with each resource rather than
assuming every resource has the same origin or license.

## Fonts, icons and native dependencies

| Component | Attribution / terms | License location |
| --- | --- | --- |
| Resource Han Rounded CN | Cyano Hao; portions by Adobe. SIL Open Font License 1.1. | [Bundled OFL and copyrights](app/src/main/assets/licenses/Resource-Han-Rounded-OFL.txt); [font documentation](docs/fonts.md). |
| Lucide icons | Lucide Icons and Contributors. ISC; additional notices are retained in the bundled file. | [Bundled license](app/src/main/assets/licenses/lucide-LICENSE.txt). |
| LibRaw | RAW decoding dependency; distributed by its authors under LGPL 2.1 / CDDL 1.0 dual-license terms. | `deps/LibRaw-0.22.2/LICENSE.LGPL` and `LICENSE.CDDL` in the dependency source checkout. |
| volk | Arseny Kapoulkine. MIT. | `SpektraLab-Windows/engine/third_party/volk/LICENSE.md` in the shared checkout. |
| stb | Its authors' public-domain / MIT terms. | `SpektraLab-Windows/engine/third_party/stb/LICENSE`; resource sync also ships `stb-LICENSE.txt`. |
| Liberation imprint fonts | Copyright and SIL Open Font License notices supplied with the fonts. | `SpektraLab-Windows/engine/resources_product/fonts/LICENSE`; resource sync also ships `Imprint-fonts-LICENSE.txt`. |

The engine and dependency paths above describe the default sibling checkout
layout; those files are not all part of this Android repository. Preserve the
corresponding license and copyright files when distributing those components.
AndroidX and other build dependencies retain their own notices and licenses.

## Screenshots and names

The README uses the newly supplied device screenshots in `docs/images/*.jpg`.
Some screenshots retain the former application name, SpektraLab. They have not
been retouched to replace the branding.

Photographs, screenshots, fonts, logos and other non-code assets retain their
respective rights and applicable permissions; the application code license does
not replace their individual terms. Film, paper and company names identify the
simulated materials and belong to their respective trademark owners. They do
not imply endorsement or affiliation.
