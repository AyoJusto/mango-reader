# Extension test fixtures

Bundle fixtures (the `.index.js` files and `versioning.json`) are not committed. They're
downloaded at test time from the pinned Inkdex 0.9 stable registry commit
(`https://raw.githubusercontent.com/inkdex/extensions/f094e9445762d3951c2f4dfb8d2ca3b0f801c3f5/0.9/stable/`)
into `core/build/fixture-cache`, and verified against the sha256s below (also present as
constants in `BundleLoaderTest.kt` and `SearchTestSupport.kt`). See `readFixture` in
`BundleLoaderTest.kt` for the download/cache/verify logic.

| File | Registry subpath | sha256 |
|---|---|---|
| `FlameComics.index.js` | `FlameComics/index.js` | `7bc0747ee748f812b9b42d585b83e6da0f9c45c6467a0044b22ad77ae144629a` |
| `MangaBat.index.js` | `MangaBat/index.js` | `ec9a212b2ebc2354619bc30ef24836e838eebc51a50a42eccd9e733211d3d08f` |
| `Toonily.index.js` | `Toonily/index.js` | `22aaade46f110a4eb27a9355d373c09d6ea4ad61f9af3478883fd6a38dd323ae` |
| `WebtoonXYZ.index.js` | `WebtoonXYZ/index.js` | `a15b60c2435f25fd17212f2c9cef24c63a413380371341e353f43008cc271a91` |
| `versioning.json` | `versioning.json` | `adc1923c9760b85e020fcdc1688f28956605b3415a4627c1a9ba26012e4a1e4d` |

Bumping to a newer registry build means picking a new pinned commit, updating the subpaths
and sha256s here and in the tests together, and clearing `core/build/fixture-cache`.

`fixtures/http/*.bin` are recorded HTTP responses used by the mocked source tests and remain
committed.
