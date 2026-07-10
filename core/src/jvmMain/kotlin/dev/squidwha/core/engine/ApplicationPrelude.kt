package dev.squidwha.core.engine

/**
 * The in-engine half of the `Application` surface. Selector tokens, the selector
 * registry, and interceptor bookkeeping never cross the Kotlin boundary; only state,
 * user agent, sleep, and (from M0.4) network do, via the `__host` bindings.
 *
 * This is the project's one deliberate exception to "no hand-authored JS": callback
 * plumbing must live where the callbacks live. Kept minimal on purpose; if it grows
 * past a screen, vendor the official @paperback/runtime-polyfills build instead.
 *
 * Unknown Application members throw instead of returning undefined, so shim gaps
 * surface as named errors, never as silent misbehavior.
 */
internal val APPLICATION_PRELUDE = """
"use strict";
globalThis.Application = (function () {
  var selectors = new Map();
  var nextSelectorId = 1;
  var interceptors = [];
  var app = {
    isResourceLimited: false,
    filterAdultTitles: false,
    filterMatureTitles: false,
    getDefaultUserAgent: function () { return __host.getDefaultUserAgent(); },
    getState: function (key) { return __host.getState(key); },
    setState: function (value, key) { __host.setState(value, key); },
    sleep: function (seconds) { return __host.sleep(seconds); },
    scheduleRequest: function (request) { return __host.scheduleRequest(request); },
    Selector: function (obj, method) {
      var id = "sel-" + nextSelectorId++;
      selectors.set(id, function () { return obj[method].apply(obj, arguments); });
      return id;
    },
    SelectorRegistry: {
      selector: function (id) {
        var fn = selectors.get(id);
        if (!fn) { throw new Error("unknown selector " + id); }
        return fn;
      },
    },
    registerInterceptor: function (id, requestSelector, responseSelector) {
      interceptors.push({ id: id, requestSelector: requestSelector, responseSelector: responseSelector });
    },
    unregisterInterceptor: function (id) {
      interceptors = interceptors.filter(function (i) { return i.id !== id; });
    },
    __interceptorCount: function () { return interceptors.length; },
  };
  return new Proxy(app, {
    get: function (target, prop) {
      if (typeof prop === "symbol" || prop === "then" || prop === "toJSON") { return undefined; }
      if (prop in target) { return target[prop]; }
      throw new Error("Application." + prop + " is not implemented");
    },
  });
})();
undefined;
""".trimIndent()
