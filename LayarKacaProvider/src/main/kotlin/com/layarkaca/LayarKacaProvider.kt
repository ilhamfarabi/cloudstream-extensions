package com.layarkaca

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

class LayarKacaProvider : MainAPI() {

    override var mainUrl = "https://tv12.lk21official.cc/"
    private var seriesUrl = "https://tv5.nontondrama.my"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Most Popular Movies",
        "$mainUrl/rating/page/" to "Movies Based on IMDb Rating",
        "$mainUrl/most-commented/page/" to "Films With the Most Comments",
        "$seriesUrl/latest-series/page/" to "Latest Series",
        "$seriesUrl/series/asian/page/" to "Latest Asian Series",
        "$mainUrl/latest/page/" to "Latest Uploaded Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("li.slider article, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getProperLink(url: String): String {
        if (url.startsWith(seriesUrl)) return url
        val res = app.get(url).document
        return if (res.select("title").text().contains("Nontondrama", true)) {
            res.selectFirst("a#openNow")?.attr("href")
                ?: res.selectFirst("div.links a")?.attr("href")
                ?: url
        } else {
            url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.poster-title, h3")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        val isSeries = this.selectFirst("span.episode") != null
        val posterheaders = mapOf("Referer" to getSafeBaseUrl(posterUrl))

        return if (isSeries) {
            val episode = this.selectFirst("span.episode strong")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
            }
        } else {
            val quality = this.selectFirst("span.label")?.text()?.trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                quality?.let { addQuality(it) }
            }
        }
    }

    // ==================== PERBAIKAN UTAMA DI SINI ====================
    override suspend fun search(query: String): List<SearchResponse> {
        // 1. Coba cari di mainUrl (untuk film)
        val url = "$mainUrl/search?s=$query"
        val response = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Referer" to mainUrl
            ),
            allowRedirects = true
        )
        val document = response.document

        // Ambil hasil dengan selektor yang sama seperti di main page
        var results = document.select("li.slider article, article")
            .mapNotNull { it.toSearchResult() }

        // 2. Jika hasil kosong (misal karena redirect atau tidak ada film), coba di seriesUrl
        if (results.isEmpty()) {
            val seriesSearchUrl = "$seriesUrl/search?s=$query"
            val docSeries = app.get(
                seriesSearchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Referer" to seriesUrl
                ),
                allowRedirects = true
            ).document
            results = docSeries.select("li.slider article, article")
                .mapNotNull { it.toSearchResult() }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fixUrl = getProperLink(url)
        val document = app.get(fixUrl).document
        val baseurl = fetchURL(fixUrl)

        val title = document.selectFirst("div.movie-info h1, h1.poster-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.tag-list span, .genre a").map { it.text() }
        val posterheaders = mapOf("Referer" to getSafeBaseUrl(poster))

        val yearRegex = Regex("\\d, (\\d{4})|\\((\\d{4})\\)").find(title)
        val year = yearRegex?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()

        val tvType = if (document.selectFirst("#season-data") != null || url.contains(seriesUrl)) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div.meta-info, .synopsis")?.text()?.trim()
        val trailer = document.selectFirst("ul.action-left > li:nth-child(3) > a, a.trailer")?.attr("href")
        val rating = document.selectFirst("div.info-tag strong, .rating strong")?.text()

        val recommendations = document.select("li.slider article").mapNotNull {
            it.toSearchResult()
        }

        return if (tvType == TvType.TvSeries) {
            val json = document.selectFirst("script#season-data")?.data()
            val episodes = mutableListOf<Episode>()
            if (json != null) {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        val href = fixUrl("$baseurl/" + ep.getString("slug"))
                        val episodeNo = ep.optInt("episode_no")
                        val seasonNo = ep.optInt("s")
                        episodes.add(
                            newEpisode(href) {
                                this.name = "Episode $episodeNo"
                                this.season = seasonNo
                                this.episode = episodeNo
                            }
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterheaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                trailer?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("ul#player-list > li a, .player-list a").mapNotNull {
            fixUrlNull(it.attr("href"))
        }.amap { url ->
            try {
                val iframe = url.getIframe()
                val referer = getSafeBaseUrl(url)
                if (iframe.isNotBlank()) {
                    Log.d("Phisher", iframe)
                    loadExtractor(iframe, referer, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    private suspend fun String.getIframe(): String {
        return app.get(this, referer = "$seriesUrl/").document
            .selectFirst("div.embed-container iframe, iframe")?.attr("src") ?: ""
    }

    private suspend fun fetchURL(url: String): String {
        val res = app.get(url, allowRedirects = false)
        val href = res.headers["location"]

        return if (href != null) {
            try {
                val it = URI(href)
                "${it.scheme}://${it.host}"
            } catch (e: Exception) {
                url
            }
        } else {
            url
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("src") -> this.attr("src")
            this.hasAttr("data-src") -> this.attr("data-src")
            else -> this.attr("src")
        }
    }

    private fun getSafeBaseUrl(url: String?): String {
        if (url.isNullOrBlank()) return mainUrl
        return try {
            val it = URI(url)
            "${it.scheme}://${it.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }
}