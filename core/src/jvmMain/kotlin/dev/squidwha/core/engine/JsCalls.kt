package dev.squidwha.core.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.evaluate

class ExtensionCallException(message: String) : Exception(message)

/**
 * Evaluates [expression], waits for its promise to settle, and returns the fulfilled
 * value as a JSON string ("null" for undefined). quickjs-kt drains pending jobs during
 * evaluate but returns promises unresolved, so the settled value is passed out through
 * a mailbox global instead. Rejections come back as [ExtensionCallException].
 *
 * [expression] must be host-authored code; anything user-supplied crosses via bindings.
 */
internal suspend fun QuickJs.callJson(expression: String): String {
    evaluate<Any?>(
        "globalThis.__mail = { done: false, ok: null, err: null };" +
            "Promise.resolve($expression).then(" +
            "function (r) { __mail.ok = JSON.stringify(r) ?? 'null'; __mail.done = true; }," +
            "function (e) { __mail.err = String((e && e.stack) || e); __mail.done = true; });" +
            "undefined;"
    )
    if (!evaluate<Boolean>("__mail.done")) {
        throw ExtensionCallException("extension call did not settle: $expression")
    }
    evaluate<String?>("__mail.err")?.let { throw ExtensionCallException(it) }
    return evaluate<String?>("__mail.ok")
        ?: throw ExtensionCallException("extension call produced no result: $expression")
}
