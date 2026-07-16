package dev.mango.core.domain

/**
 * The per-source transport policy — cookie jar, pinned or default User-Agent, canonical header
 * casing — for image fetches (reader pages, downloads, covers) that never go through the
 * extension's own request pipeline, so they would otherwise ship with none of a source's cookies
 * or UA.
 */
interface SourceHeaderPolicy {
    /**
     * [headers] with the wire-boundary precedence applied: canonical header-name casing, a
     * User-Agent (a caller-supplied one wins untouched, else the pinned or default one), and a
     * Cookie header built from the source's jar with a caller-supplied Cookie pair overriding the
     * jar on a name collision. Any failure reading the jar, resolving the User-Agent, or parsing
     * [url] degrades to the canonicalized [headers] — a policy fault here must never sink an
     * image the site would otherwise have served.
     *
     * Deliberately runs no source interceptor: covers load one-by-one per composable, and a
     * fresh interceptor context per cover is real cost for a hypothetical — no known source needs
     * intercepted covers. [withPolicyHeaders] is the batched path that does.
     */
    suspend fun headersFor(sourceId: String, url: String, headers: Map<String, String>): Map<String, String>

    /** [headersFor] applied to every page's own url and headers. */
    suspend fun withPolicyHeaders(sourceId: String, pages: List<Page>): List<Page>
}
