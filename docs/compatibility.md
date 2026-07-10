# Source compatibility (M1.4)

Per-source, per-`MangaSource`-method status. Legend:

- **pass (fixture-verified)** — recorded HTTP fixtures replay through `PaperbackExtension` and the
  normalized domain values were asserted directly (title, tags, chapter numbers, page URLs, etc).
- **blocked-live** — the live site refused the request during the one budgeted recording run, so
  no fixtures exist; the engine-side machinery (boot, surface, offline challenge detection where
  noted) is verified, the site is not.
- **untested** — not exercised by any test in this repo.

| Source | search | getDetails | getChapters | getPages | Notes |
|---|---|---|---|---|---|
| FlameComics | pass (fixture-verified) | pass (fixture-verified) | pass (fixture-verified) | pass (fixture-verified) | `SearchTest`, `NormalizationTest`, `ReadPathTest` (pre-existing, M1.0–M1.3). |
| MangaBat | pass (fixture-verified) | pass (fixture-verified) | pass (fixture-verified) | pass (fixture-verified) | `ReadPathMatrixTest`, replaying the 2026-07-10 live recording. Cloudflare detection (`cf-mitigated: challenge` header) also verified offline in `ChallengeDetectionTest`. The recorded title lists no author on the site, so `authors=[]` is correct parsing, not a gap. |
| Toonily | blocked-live | blocked-live | blocked-live | blocked-live | Boots and exposes the full surface (`BundleMatrixTest`); Cloudflare detection (403 + "recaptcha" body) verified offline in `ChallengeDetectionTest`. The one budgeted live recording run got a non-200 on its first request (the search); the recorder's `status == 200` check fired before the bundle's interceptResponse could classify it, so the exact status is unknown. Challenge suspected (Madara/Cloudflare corpus). No fixtures recorded. |
| WebtoonXYZ | blocked-live | blocked-live | blocked-live | blocked-live | Boots and exposes the full surface (`BundleMatrixTest`). The one budgeted live request (2026-07-10) got 403 on `/?s=&post_type=wp-manga`, but the bundle's own challenge heuristic missed it — the response (as seen through `ApplicationHost.dispatch`) carried neither `cf-mitigated: challenge` nor "recaptcha" in the body, so the bundle threw its generic `Request failed with status 403` instead of `cloudflareError`, and the engine surfaced `ExtensionCallException` (correctly relaying the bundle). `CloudflareChallengeException` did NOT trigger live; see the header-case question below. |

## Engine note: `atob` (fixed)

`MangaBat.index.js`, `Toonily.index.js`, and `WebtoonXYZ.index.js` all hydrate an
HTML-entity-decoding trie at module top level (their inlined HTML parser; FlameComics's JSON-API
bundle has no such code) through an isomorphic base64 helper:

```js
typeof atob == "function" ? atob(e) : typeof Buffer.from == "function" ? Buffer.from(e, "base64").toString("binary") : new Buffer(e, "base64").toString("binary")
```

GraalJS's default `js` context has neither `atob` nor Node's `Buffer`, and `typeof Buffer.from`
dereferences the undeclared `Buffer` and throws — so all three bundles failed `context.eval()`
outright. Fixed in `ExtensionRuntime.withExtension`: an `atob` global is bound host-side next to
`Application`, implemented with `java.util.Base64` using browser semantics (base64 to Latin-1
"binary string" — deliberately different from `Application.base64Decode`'s UTF-8 convention).
All three bundles now evaluate, boot, and expose their full method surface (`BundleMatrixTest`).

## Open question: response header name case vs the bundles' lowercase lookups

The bundles read response headers with case-sensitive JS lookups (`t.headers?.["cf-mitigated"]`).
`ApplicationHost.dispatch` passes header names through with whatever case Ktor reports from the
wire. If a site (or an intermediary) delivers `Cf-Mitigated`, the bundle's challenge detection
silently misses. Whether this explains the WebtoonXYZ live miss above is unconfirmed (one request
budget, spent). Candidate M1.5 hardening: lowercase response header keys in `dispatch`, matching
the case-insensitive behavior bundles get on iOS.
