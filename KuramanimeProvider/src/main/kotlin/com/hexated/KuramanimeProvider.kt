package com.hexated

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Calendar

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v18.kuramanime.ing"
    override var name = "Kuramanime"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true


    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Ongoing" -> ShowStatus.Completed
                "Completed" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private fun getCurrentSeason(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val season = when (month) {
            in 0..2 -> "winter"
            in 3..5 -> "spring"
            in 6..8 -> "summer"
            else -> "fall"
        }
        return "$season-$year"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Ongoing",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Completed",
        "$mainUrl/properties/season/${getCurrentSeason()}?order_by=most_viewed&page=" to "Most Viewed This Season",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Movies",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/anime?search=$query&order_by=latest").document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
                .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()
        for (i in 1..30) {
            val doc = app.get("$url?page=$i").document
            val eps = Jsoup.parse(doc.select("#episodeLists").attr("data-content"))
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episode }
                }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()?.lowercase() ?: "tv", episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val context = CloudStreamApp.context ?: return false
        var found = false

        val snapshots = withTimeoutOrNull(150_000L) {
            withContext(Dispatchers.Main) {
                captureServerSnapshots(context, data)
            }
        } ?: emptyMap()

        snapshots.forEach { (server, html) ->
            val doc = Jsoup.parse(html, data)

            doc.select("video#player > source").forEach { source ->
                val link = fixUrl(source.attr("src"))
                if (link.isBlank()) return@forEach
                val quality = source.attr("size").toIntOrNull()
                found = true
                callback.invoke(newExtractorLink(fixTitle(server), fixTitle(server), link, INFER_TYPE) {
                    this.quality = quality ?: Qualities.Unknown.value
                })
            }

            doc.selectFirst("div.iframe-container iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { iframeSrc ->
                runCatching {
                    loadExtractor(fixUrl(iframeSrc), "$mainUrl/", subtitleCallback) {
                        found = true
                        callback.invoke(it)
                    }
                }
            }

            if (server.contains(Regex("(?i)kuramadrive|archive"))) {
                doc.select("div#animeDownloadLink a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotBlank()) {
                        runCatching {
                            loadExtractor(href, "$mainUrl/", subtitleCallback) {
                                found = true
                                callback.invoke(it)
                            }
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun captureServerSnapshots(context: Context, url: String): Map<String, String> {
        val snapshots = linkedMapOf<String, String>()
        val webView = try {
            WebView(context)
        } catch (_: Throwable) {
            return snapshots
        }

        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = MOBILE_USER_AGENT
            }

            val loaded = withTimeoutOrNull(25_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    webView.loadUrl(url)
                }
            }
            if (loaded == null) return snapshots

            delay(3000)

            val servers = readServerList(webView)
            val loopDeadline = System.currentTimeMillis() + 100_000L

            for (server in servers) {
                if (System.currentTimeMillis() > loopDeadline) break

                val switched = withTimeoutOrNull(5_000L) {
                    webView.evalJs(
                        """
                        (function(){
                            var el = document.getElementById('changeServer');
                            if(!el) return false;
                            el.value = ${server.toJsStringLiteral()};
                            el.dispatchEvent(new Event('change', {bubbles:true}));
                            return true;
                        })();
                        """.trimIndent()
                    )
                }
                if (switched?.contains("true") != true) continue

                delay(3500)

                val rawHtml = withTimeoutOrNull(5_000L) { webView.evalJs("document.documentElement.outerHTML") }
                val html = rawHtml?.let { tryParseJson<String>(it) }
                if (!html.isNullOrBlank()) {
                    snapshots[server] = html
                }
            }
        } catch (_: Throwable) {
        } finally {
            webView.stopLoading()
            webView.destroy()
        }

        return snapshots
    }

    private suspend fun readServerList(webView: WebView): List<String> {
        val raw = withTimeoutOrNull(5_000L) {
            webView.evalJs(
                "JSON.stringify(Array.from(document.querySelectorAll('#changeServer option')).map(function(o){return o.value}))"
            )
        }
        val innerJson = raw?.let { tryParseJson<String>(it) }
        val servers = innerJson?.let { tryParseJson<List<String>>(it) }?.filter { it.isNotBlank() }
        return if (servers.isNullOrEmpty()) listOf("kuramadrive") else servers
    }

    private fun String.toJsStringLiteral(): String =
        "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private suspend fun WebView.evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(result)
            }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
