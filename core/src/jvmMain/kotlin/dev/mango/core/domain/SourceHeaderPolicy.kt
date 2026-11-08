package dev.mango.core.domain

/** Transport policy (cookies, User-Agent, header casing) for image fetches outside the extension's request pipeline. */
interface SourceHeaderPolicy {
    /** [headers] with jar cookies, User-Agent and canonical casing applied; a policy fault degrades to canonicalized [headers]. */
    suspend fun headersFor(sourceId: String, url: String, headers: Map<String, String>): Map<String, String>

    /** Cover headers, possibly including the source interceptor's, computed once per source. */
    suspend fun coverHeadersFor(sourceId: String, url: String): Map<String, String> =
        headersFor(sourceId, url, emptyMap())

    suspend fun withPolicyHeaders(sourceId: String, pages: List<Page>): List<Page>
}
