# Extension test fixtures

Downloaded 2026-07-10 from the Inkdex 0.9 stable registry
(`https://inkdex.github.io/extensions/0.9/stable/`), built with `@paperback/types 1.0.0-alpha.92`.

| File | Source URL | sha256 |
|---|---|---|
| `FlameComics.index.js` | `.../FlameComics/index.js` | `7bc0747ee748f812b9b42d585b83e6da0f9c45c6467a0044b22ad77ae144629a` |
| `versioning.json` | `.../versioning.json` | `adc1923c9760b85e020fcdc1688f28956605b3415a4627c1a9ba26012e4a1e4d` |
| `UToon.source.js` | Downloaded 2026-07-10 from `https://thenetsky.github.io/extensions-generic-0.8/madara/UToon/source.js` (0.8 compat test source, M1.3; Toonily was tried first and returned 403/Cloudflare on the live run, see docs/compatibility.md) | `6c77e880444136b064e810e7c4ca8db4d0d20d862a082aa6586b8648b2dc080b` |

These are committed verbatim so engine tests are reproducible offline. Re-downloading gets a
newer registry build; update the checksums here and in the tests together.
