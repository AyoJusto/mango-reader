# Application surface audit (Paperback 0.9)

Every member of the 0.9 `Application` API, as implemented by `ApplicationHost.ApplicationProxy`
(`core/src/jvmMain/kotlin/dev/squidwha/core/engine/ApplicationHost.kt`). Status legend:

- **implemented** — real Kotlin behavior backing it
- **stub (no-op)** — present so extensions that call it don't crash, does nothing
- **unsupported (throws)** — present but explicitly rejects use, out of scope for this app

| Member | Status | Notes |
|---|---|---|
| `scheduleRequest` | implemented | Runs registered request interceptors, dispatches over Ktor, runs registered response interceptors — composed as a native JS promise chain (see M1.2 design note below). |
| `getState` / `setState` | implemented | Per-host, JSON-serialized, keyed map; survives across contexts within one `ApplicationHost`. |
| `getSecureState` / `setSecureState` | implemented | Same pattern as `getState`/`setState`, separate map. No OS keychain backing — "secure" only in the sense of being a distinct namespace, matching what a desktop/JVM host can actually offer. |
| `resetAllState` | implemented | Clears both state maps. |
| `registerInterceptor` / `unregisterInterceptor` | implemented | Stores `(id, requestSelectorId, responseSelectorId)`; invoked by `scheduleRequest` in registration order. |
| `setRedirectHandler` | stub (no-op) | Selector id is stored; nothing currently reads it — this app's HTTP client follows redirects itself and no fixture source needs custom redirect handling yet. |
| `getDefaultUserAgent` | implemented | Returns the configured UA string (plain mobile Safari default; see `ApplicationHost` constructor doc). |
| `sleep` | implemented | Blocks the calling thread for the given seconds (`Thread.sleep`); safe because each context owns its thread for the call's duration. |
| `decodeHTMLEntities` | implemented | The five XML entities (`&amp; &lt; &gt; &quot; &apos;`) plus numeric `&#NNN;` / `&#xHH;` forms. No dependency. |
| `base64Encode` / `base64Decode` | implemented | `java.util.Base64`, string<->string over UTF-8 bytes (matches the native app's `Data(str.utf8)` convention rather than browser `btoa`/`atob` Latin-1 semantics, since Paperback extensions run inside a native iOS host, not a browser). |
| `crypto_md5Hash` | implemented | `MessageDigest.getInstance("MD5")` over UTF-8 bytes, lowercase hex. |
| `arrayBufferToUTF8String` | implemented | Pre-existing (M1.0/M1.1). |
| `arrayBufferToASCIIString` | implemented | `US_ASCII` decode of the buffer. |
| `arrayBufferToUTF16String` | implemented | `UTF_16LE` decode of the buffer. |
| `Selector` | implemented | Pre-existing; issues an opaque id for a `(target, method)` pair. |
| `SelectorRegistry` | implemented | Pre-existing; `.selector(id)` returns a callable that invokes the stored `(target, method)`. |
| `registerDiscoverSection` / `unregisterDiscoverSection` / `registeredDiscoverSections` | implemented | Stored/removed/listed in a per-proxy map keyed by the section's `id`. Discover sections aren't wired into any UI yet (out of scope for M1.2/M1.3) but the storage contract is real. |
| `invalidateDiscoverSections` | stub (no-op) | No cache to invalidate until discover sections are actually consumed. |
| `executeInWebView` | unsupported (throws) | `UnsupportedOperationException` — no WebView in this app, and explicitly out of scope per the M1.2/M1.3 plan (Cloudflare bypass etc. are separate work). |
| `formDidChange` | stub (no-op) | Settings-form UI isn't built yet; the member exists so bundle code that calls it (e.g. `reloadForm()` helpers) doesn't throw. |
| `isResourceLimited` | implemented | Pre-existing; always `false`. |
| `filterAdultTitles` | implemented | Pre-existing; always `false`. |
| `filterMatureTitles` | implemented | Pre-existing; always `false`. |

## M1.2 design note: why `scheduleRequest` returns a promise

Interceptor invocation can't synchronously block on a JS promise's settled state when
`scheduleRequest` is called from deep inside a bundle's own async call graph (e.g.
`getSearchResults -> getCandidates -> refreshCandidateCache -> ... -> scheduleRequest`).
GraalJS only auto-drains its microtask queue on return from the single *outermost*
host-to-guest call (see `ExtensionHandle.invokeAwait`/`awaitPromise` in `ExtensionRuntime.kt`);
a promise awaited from a nested call never settles, because nothing ever drives its
resolution. Confirmed empirically: `SearchTest` failed with
`ExtensionCallException: interceptor interceptRequest returned a promise that never settled`
when interceptor invocation first used the same synchronous `.then()`-and-poll pattern as the
outermost call, and adding a manual "pump the queue" loop via repeated `context.eval` made no
difference (nested `context.eval` calls don't trigger the outermost-exit drain either).

The fix: `scheduleRequest` builds and returns a genuine native JS promise chain (via
`.invokeMember("then", ...)`, never hand-authored JS text) instead of trying to read
intermediate results back into Kotlin. Interceptor calls that themselves return a promise are
returned directly from a `.then()` callback, so JS's own promise-flattening handles waiting on
them — no Kotlin-side polling required. The only synchronous, blocking Kotlin work (the actual
Ktor HTTP call) happens inside a `.then()` callback fired by JS's engine once the
request-interceptor chain has already settled, which is a normal synchronous host-function
call, not a nested await. This composes correctly because it never needs an out-of-band drain:
resolution is entirely internal to GraalJS's own engine until the *true* outermost call
(`ExtensionHandle.invokeAwait` for `getSearchResults` et al.) finally returns to Kotlin.
