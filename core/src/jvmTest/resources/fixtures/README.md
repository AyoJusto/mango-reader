# Extension test fixtures

Downloaded 2026-07-10 from the Inkdex 0.9 stable registry
(`https://inkdex.github.io/extensions/0.9/stable/`), built with `@paperback/types 1.0.0-alpha.92`.

| File | Source URL | sha256 |
|---|---|---|
| `FlameComics.index.js` | `.../FlameComics/index.js` | `7bc0747ee748f812b9b42d585b83e6da0f9c45c6467a0044b22ad77ae144629a` |
| `versioning.json` | `.../versioning.json` | `adc1923c9760b85e020fcdc1688f28956605b3415a4627c1a9ba26012e4a1e4d` |

Downloaded 2026-07-10, M1.4 (MangaBat, Toonily, WebtoonXYZ), same registry base URL.

| File | Source URL | sha256 |
|---|---|---|
| `MangaBat.index.js` | `.../MangaBat/index.js` | `ec9a212b2ebc2354619bc30ef24836e838eebc51a50a42eccd9e733211d3d08f` |
| `Toonily.index.js` | `.../Toonily/index.js` | `22aaade46f110a4eb27a9355d373c09d6ea4ad61f9af3478883fd6a38dd323ae` |
| `WebtoonXYZ.index.js` | `.../WebtoonXYZ/index.js` | `a15b60c2435f25fd17212f2c9cef24c63a413380371341e353f43008cc271a91` |

These are committed verbatim so engine tests are reproducible offline. Re-downloading gets a
newer registry build; update the checksums here and in the tests together.
