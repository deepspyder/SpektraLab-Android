# CineStill 800T Android model extension

The CineStill alias, Gaussian PSF constants and highlight-headroom formula are
ported from the local SpektraLab reference revision
1952c63537ec576d24422f1065b12495631346a5, under GPL-3.0-or-later.

`profiles/cinestill_800t.json` contains no measured arrays of its own. It inherits
the unmodified Kodak Vision3 500T profile at runtime and overrides only its
display name, still-camera use and absent anti-halation backing. It is not a
claim of an independently measured CineStill sensitometric data set.

The original profile data and their CC BY-SA 4.0 attribution remain unchanged.
Brand names and trademarks belong to their respective owners.
