// Anizm (anizm.net) — Türkçe altyazılı anime, CloudStream provider.
//
// The site is Cloudflare-protected; all anizm.net requests go through a
// CloudflareKiller interceptor so the on-device WebView solves the challenge.
// Stream chain (reverse-engineered against live traffic):
//   search -> /<slug> (episode list) -> /<slug>-N-bolum-izle
//     -> /episode/<epId>/translator/<transId>  (fansub mirror buttons)
//     -> /video/<vid> -> /player/<vid> (302) -> anizmplayer.com/video/<hash>
//     -> POST anizmplayer.com/player/index.php?data=<hash>&do=getVideo -> master.m3u8

package com.bandaju

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder

class Anizm : MainAPI() {
    override var mainUrl            = "https://anizm.net"
    override var name               = "Anizm"
    override val hasMainPage        = false          // search-only for v1
    override var lang               = "tr"
    override val hasQuickSearch     = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie)

    private val cfKiller = CloudflareKiller()
    private val playerBase = "https://anizmplayer.com"
    private val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")

    // How many fansubs / mirrors to attempt before giving up (knobs).
    private val maxTranslators = 4
    private val maxMirrorsPerTranslator = 3

    /* -------------------------------------------------------------- search */

    data class SearchRoot(@JsonProperty("data") val data: List<SearchItem>? = null)

    data class SearchItem(
        @JsonProperty("info_title")   val title: String?  = null,
        @JsonProperty("info_slug")    val slug: String?   = null,
        @JsonProperty("info_poster")  val poster: String? = null,
        @JsonProperty("info_year")    val year: String?   = null,
    )

    private fun posterUrl(file: String?): String? =
        if (file.isNullOrBlank()) null else "$mainUrl/storage/pcovers/$file"

    // Cloudflare's managed challenge is solved by CloudflareKiller on a full
    // HTML page (the WebView cannot settle a bare JSON endpoint). Warming the
    // main page first caches cf_clearance for the anizm.net host, so the
    // /searchAnime AJAX call afterwards carries the cookie.
    private suspend fun warmCloudflare() {
        runCatching { app.get(mainUrl, interceptor = cfKiller) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        warmCloudflare()
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/searchAnime?query=$q&page=1&type=detailed&limit=24" +
                  "&priorityField=info_title&orderBy=info_year&orderDirection=ASC"
        val resp = app.get(url, headers = ajaxHeaders, referer = "$mainUrl/", interceptor = cfKiller)
        val root = resp.parsedSafe<SearchRoot>()

        val results = root?.data.orEmpty().mapNotNull { item ->
            val slug  = item.slug ?: return@mapNotNull null
            val title = item.title ?: slug
            newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) {
                this.posterUrl = posterUrl(item.poster)
            }
        }

        // TEMP diagnostic: if nothing parsed, surface what the device actually
        // received so the failure mode (CF challenge / parse / empty) is visible.
        if (results.isEmpty()) {
            val body = resp.text.replace("\n", " ").take(70)
            return listOf(
                newAnimeSearchResponse("DBG code=${resp.code} len=${resp.text.length} $body", mainUrl, TvType.Anime)
            )
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /* ---------------------------------------------------------------- load */

    override suspend fun load(url: String): LoadResponse? {
        val res  = app.get(url, interceptor = cfKiller)
        val html = res.text
        val doc  = res.document

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val title   = ogTitle?.substringBefore(" izle")?.substringBefore(" |")?.trim()
            ?: doc.title().substringBefore(" izle").substringBefore(" |").trim()
        val poster  = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot    = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodes = parseEpisodes(html)
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    // Episode list. Primary: numbered "N. Bölüm" episodeBlock anchors.
    // Fallback: movies/specials that expose only shortcut buttons (may carry a
    // "-special-" infix or a doubled-slug movie url). "-fragman" (trailers) are
    // deliberately excluded — they are not the episode.
    private fun parseEpisodes(html: String): List<Episode> {
        val map = LinkedHashMap<Int, String>()

        val blockRe = Regex(
            """<a\s+href="([^"]+)"[^>]*>\s*<div\s+class="[^"]*episodeBlock[^"]*"[^>]*>\s*(\d+)\.\s*B[öoOÖ]l[üuUÜ]m""",
            RegexOption.IGNORE_CASE
        )
        for (m in blockRe.findAll(html)) {
            val href = absUrl(m.groupValues[1])
            val num  = m.groupValues[2].toIntOrNull() ?: continue
            if (!map.containsKey(num) || href.contains("-bolum-izle")) map[num] = href
        }

        if (map.isEmpty()) {
            val btnRe = Regex("""<a\s+href="([^"]+)"[^>]*class="[^"]*anizmEpisodeButton""", RegexOption.IGNORE_CASE)
            for (m in btnRe.findAll(html)) {
                var href = m.groupValues[1]
                if (href.contains("-fragman", ignoreCase = true)) continue   // skip trailers
                href = absUrl(href)
                val num = Regex("""-(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val withIzle = if (href.endsWith("-izle")) href else "$href-izle"
                if (!map.containsKey(num)) map[num] = withIzle
            }
        }

        return map.entries.sortedBy { it.key }.map { (num, href) ->
            newEpisode(href) {
                this.name    = "$num. Bölüm"
                this.episode = num
                this.season  = 1
            }
        }
    }

    private fun absUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/")    -> "$mainUrl$href"
        else                    -> "$mainUrl/$href"
    }

    /* ----------------------------------------------------------- loadLinks */

    data class GetVideo(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeHtml = app.get(data, interceptor = cfKiller).text

        val epId = Regex("""/episode/(\d+)/translator/\d+""").find(episodeHtml)
            ?.groupValues?.get(1) ?: return false

        // Distinct translator (fansub) ids on the episode page.
        val translators = Regex("""data-translator-id="(\d+)"""").findAll(episodeHtml)
            .map { it.groupValues[1] }.distinct().take(maxTranslators).toList()
        if (translators.isEmpty()) return false

        var found = false
        for (transId in translators) {
            val transName = Regex(
                """data-translator-id="$transId"[^>]*?data-translator-name="([^"]*)""""
            ).find(episodeHtml)?.groupValues?.get(1)?.trim().orEmpty()

            val mirrorHtml = app.get(
                "$mainUrl/episode/$epId/translator/$transId",
                headers = ajaxHeaders, referer = "$mainUrl/", interceptor = cfKiller
            ).text

            val videoIds = Regex("""video="[^"]*/video/(\d+)"""").findAll(mirrorHtml)
                .map { it.groupValues[1] }.distinct().take(maxMirrorsPerTranslator).toList()

            for (vid in videoIds) {
                val m3u8 = resolveStream(vid, data) ?: continue
                val label = if (transName.isNotBlank()) "$name - $transName" else name
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = label,
                        url    = m3u8,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$playerBase/"
                        this.quality = Qualities.Unknown.value   // CloudStream reads variants from the master
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                found = true
                break // one working mirror per fansub is enough
            }
        }
        return found
    }

    // /player/<vid> 302s to anizmplayer.com/video/<hash>; then getVideo -> m3u8.
    private suspend fun resolveStream(videoId: String, episodeUrl: String): String? {
        // /player only 302s to anizmplayer when the request looks like the
        // embedded iframe (top-level requests get a placeholder page). Mimic
        // Chrome's iframe subrequest headers so okhttp receives the redirect.
        val playerResp = app.get(
            "$mainUrl/player/$videoId",
            headers = mapOf(
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "User-Agent"     to USER_AGENT
            ),
            referer = episodeUrl, interceptor = cfKiller, allowRedirects = true
        )
        val hash = Regex("""/video/([a-f0-9]{16,})""").find(playerResp.url)?.groupValues?.get(1)
            ?: Regex("""anizmplayer\.com/video/([a-f0-9]{16,})""").find(playerResp.text)?.groupValues?.get(1)
            ?: return null

        val gv = app.post(
            "$playerBase/player/index.php?data=$hash&do=getVideo",
            headers = ajaxHeaders,
            referer = "$mainUrl/",
            data = mapOf("hash" to hash, "r" to "$mainUrl/")
        ).parsedSafe<GetVideo>() ?: return null

        return (gv.videoSource ?: gv.securedLink)?.replace("\\/", "/")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
