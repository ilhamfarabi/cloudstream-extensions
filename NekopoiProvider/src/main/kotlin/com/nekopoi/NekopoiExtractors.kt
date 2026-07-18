package com.nekopoi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class Playmogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, referer = referer).text
            
            val title = Regex("""\[(\d+)[Pp]\]""").find(doc)?.groupValues?.getOrNull(1)
            val pageQuality = title?.toIntOrNull() ?: 0

            val directSource = Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8.*?)['"]""").find(doc)?.groupValues?.getOrNull(1)
            if (directSource != null) {
                val q = if (pageQuality > 0) pageQuality else Qualities.Unknown.value
                callback.invoke(
                    newExtractorLink(name, name, directSource, ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.quality = q
                        this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
                    }
                )
                return
            }

            val passMd5 = Regex("""/pass_md5/([^/]+)/([^/\s"']+)""").find(doc) ?: return
            val passPath = passMd5.value
            
            val req = app.get("$mainUrl$passPath", referer = url)
            var videoBase = req.text.trim()
            
            if (videoBase.isBlank()) return

            if (videoBase.startsWith("{")) {
                videoBase = Regex("""['"]url['"]\s*:\s*['"]([^'"]+)['"]""").find(videoBase)?.groupValues?.getOrNull(1) ?: return
            }

            val token = passMd5.groupValues[2]
            val expiry = (System.currentTimeMillis() + 86400000).toString()
            val videoUrl = if (videoBase.endsWith("~")) "$videoBase$token?token=$token&expiry=$expiry" else videoBase

            val q = if (pageQuality > 0) pageQuality else when {
                Regex("""\b(?:2160|4k)\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P2160.value
                Regex("""\b(?:1080|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P1080.value
                Regex("""\b720\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P720.value
                Regex("""\b480\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P480.value
                Regex("""\b360\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = q
                    this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class Streampoi : ExtractorApi() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text

            var fileUrl = Regex("""['"]file['"]\s*:\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)

            if (fileUrl == null) {
                val packed = Regex(
                    """\}\s*\(\s*'((?:[^'\\]|\\.)*+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\s*\.split\s*\(\s*'\|\s*'\s*\)""",
                    RegexOption.IGNORE_CASE
                ).find(html)

                if (packed != null) {
                    val encoded = packed.groupValues[1]
                    val radix = packed.groupValues[2].toIntOrNull() ?: return
                    val count = packed.groupValues[3].toIntOrNull() ?: return
                    val dictionary = packed.groupValues[4].split("|")

                    var result = encoded
                    for (i in (count - 1) downTo 0) {
                        val replacement = dictionary.getOrNull(i)
                        if (!replacement.isNullOrEmpty()) {
                            val word = i.toString(radix)
                            result = result.replace(Regex("\\b" + Regex.escape(word) + "\\b"), replacement)
                        }
                    }
                    
                    fileUrl = Regex("""['"]file['"]\s*:\s*['"]((?:[^'"]|\\.)*+)['"]""").find(result)?.groupValues?.getOrNull(1)
                }
            }

            if (fileUrl == null) return

            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = fileUrl,
                referer = url,
                headers = mapOf("Referer" to url, "Origin" to mainUrl)
            )
            
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
            } else {
                callback.invoke(
                    newExtractorLink(name, name, fileUrl) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = mapOf("Referer" to url, "Origin" to mainUrl)
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
