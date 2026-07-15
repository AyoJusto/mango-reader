// First-party test bundle: not derived from any real extension. Mirrors the Paperback 0.9
// shape the engine consumes (source[sourceId], initialise(), getSearchResults,
// getMangaDetails, getChapters, getChapterDetails) and the Cloudflare-challenge error shape
// real Madara-corpus bundles throw, so the engine's challenge-detection path has offline
// coverage that doesn't depend on a live, Cloudflare-fronted third party.
var source = (function () {
    var DOMAIN = "https://synthetic.example";

    // Same shape ExtensionRuntime.cloudflareChallengeIn recognizes: an object with
    // type === "cloudflareError" and a resolutionRequest.url.
    function cloudflareError(resolutionRequest) {
        var error = new Error("Cloudflare bypass is required");
        error.type = "cloudflareError";
        error.resolutionRequest = resolutionRequest;
        return error;
    }

    var interceptor = {
        id: "synthetic-interceptor",
        interceptRequest: async function (request) {
            return request;
        },
        interceptResponse: async function (request, response, data) {
            var body = Application.arrayBufferToUTF8String(data);
            var cfMitigated = response.headers && response.headers["cf-mitigated"] === "challenge";
            var recaptchaChallenge = response.status === 403 && body.indexOf("recaptcha") !== -1;
            if (cfMitigated || recaptchaChallenge) {
                throw cloudflareError({
                    url: DOMAIN,
                    method: "GET",
                    headers: {
                        referer: DOMAIN + "/",
                        "user-agent": await Application.getDefaultUserAgent(),
                    },
                });
            }
            return data;
        },
    };

    // <manga id="..." title="..." cover="..."/> — cover is optional
    function parseEntries(html) {
        var entries = [];
        var re = /<manga id="([^"]*)" title="([^"]*)"(?: cover="([^"]*)")?\s*\/>/g;
        var match;
        while ((match = re.exec(html)) !== null) {
            entries.push({ mangaId: match[1], title: match[2], imageUrl: match[3] || null });
        }
        return entries;
    }

    // <chapter id="..." num="..." title="..."/> — title is optional
    function parseChapters(html) {
        var chapters = [];
        var re = /<chapter id="([^"]*)" num="([^"]*)"(?: title="([^"]*)")?\s*\/>/g;
        var match;
        while ((match = re.exec(html)) !== null) {
            chapters.push({
                chapterId: match[1],
                chapNum: Number(match[2]),
                title: match[3] || null,
                langCode: "en",
                publishDate: null,
            });
        }
        return chapters;
    }

    // <page url="..."/>
    function parsePages(html) {
        var pages = [];
        var re = /<page url="([^"]*)"\s*\/>/g;
        var match;
        while ((match = re.exec(html)) !== null) {
            pages.push(match[1]);
        }
        return pages;
    }

    var Synthetic = {
        initialise: async function () {
            Application.registerInterceptor(
                interceptor.id,
                Application.Selector(interceptor, "interceptRequest"),
                Application.Selector(interceptor, "interceptResponse"),
            );
        },

        getSearchResults: async function (query) {
            var result = await Application.scheduleRequest({
                url: DOMAIN + "/search?q=" + encodeURIComponent(query.title || ""),
                method: "GET",
            });
            var html = Application.arrayBufferToUTF8String(result[1]);
            return { items: parseEntries(html) };
        },

        getMangaDetails: async function (mangaId) {
            var result = await Application.scheduleRequest({
                url: DOMAIN + "/manga/" + mangaId,
                method: "GET",
            });
            var html = Application.arrayBufferToUTF8String(result[1]);
            var titleMatch = /<title>([^<]*)<\/title>/.exec(html);
            return {
                mangaId: mangaId,
                mangaInfo: {
                    primaryTitle: titleMatch ? titleMatch[1] : mangaId,
                    thumbnailUrl: null,
                    author: null,
                    synopsis: null,
                    status: "ONGOING",
                    tagGroups: [],
                },
            };
        },

        getChapters: async function (request) {
            var result = await Application.scheduleRequest({
                url: DOMAIN + "/manga/" + request.mangaId + "/chapters",
                method: "GET",
            });
            var html = Application.arrayBufferToUTF8String(result[1]);
            return parseChapters(html);
        },

        getChapterDetails: async function (request) {
            var result = await Application.scheduleRequest({
                url: DOMAIN + "/manga/" + request.sourceManga.mangaId + "/" + request.chapterId,
                method: "GET",
            });
            var html = Application.arrayBufferToUTF8String(result[1]);
            return { pages: parsePages(html) };
        },
    };

    return { Synthetic: Synthetic };
})();
