package com.hghub

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 通用苹果CMS（MacCMS V10）资源站源。
 *
 *   GET {api}?ac=list                        分类
 *   GET {api}?ac=videolist&pg=N[&t=分类id]   列表
 *   GET {api}?ac=videolist&wd=关键词          搜索
 *   GET {api}?ac=detail&ids=ID               详情（vod_play_url 里是 m3u8 或 /play/xxx）
 *
 * 加新站只需在 HGhubPlugin 里多注册一个实例。
 */
open class CmsProvider(
    override var name: String,
    override var mainUrl: String,
    private val apiBase: String,
    private val referer: String,
    private val cats: List<Pair<String, String>> = emptyList(),
) : MainAPI() {

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override var lang = "zh"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to referer,
    )

    private suspend fun apiJson(query: String): JsonObject = withContext(Dispatchers.IO) {
        val text = app.get("$apiBase?$query", headers = headers()).text
        JsonParser.parseString(text).asJsonObject
    }

    // ------------------------------------------------------------ 字段
    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asJsonPrimitive?.asString

    private fun JsonObject.intOf(k: String): Int? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { runCatching { it.asInt }.getOrNull() }

    private fun itemsOf(root: JsonObject): List<JsonObject> =
        root.get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() } ?: emptyList()

    private fun toResult(o: JsonObject): SearchResponse? {
        val id = o.intOf("vod_id") ?: return null
        val title = o.str("vod_name") ?: return null
        val remarks = o.str("vod_remarks").orEmpty()
        val url = "$mainUrl/vod/$id"
        val many = remarks.contains("集") || remarks.contains("更新") || remarks.contains("连载")
        return if (many) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = o.str("vod_pic") }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = o.str("vod_pic") }
        }
    }

    // ------------------------------------------------------------ 主页
    override val mainPage = mainPageOf(
        *buildList {
            add("" to "最新")
            cats.forEach { add(it.first to it.second) }
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val t = request.data
        val q = if (t.isBlank()) "ac=videolist&pg=$page" else "ac=videolist&t=$t&pg=$page"
        val items = itemsOf(apiJson(q)).mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------ 搜索
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val wd = URLEncoder.encode(query, "UTF-8")
        return itemsOf(apiJson("ac=videolist&wd=$wd&pg=1")).mapNotNull { toResult(it) }
    }

    // ------------------------------------------------------------ 详情
    override suspend fun load(url: String): LoadResponse {
        val id = url.trimEnd('/').substringAfterLast('/')
        val o = itemsOf(apiJson("ac=detail&ids=$id")).firstOrNull()
            ?: throw RuntimeException("详情为空")

        val title = o.str("vod_name") ?: "视频 $id"
        val plot = o.str("vod_content")?.replace(Regex("<[^>]+>"), "")?.trim()

        // 多线路用 $$$ 分隔，剧集用 # 分隔，名称与地址用 $ 分隔
        val groups = (o.str("vod_play_url") ?: "").split("$$$")
        val group = groups.firstOrNull { it.contains(".m3u8") } ?: groups.firstOrNull().orEmpty()
        val eps = group.split("#").filter { it.isNotBlank() }.mapIndexedNotNull { idx, seg ->
            val parts = seg.split("$", limit = 2)
            val epName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "第 ${idx + 1} 集"
            val epUrl = parts.getOrNull(1) ?: return@mapIndexedNotNull null
            if (!epUrl.startsWith("http")) return@mapIndexedNotNull null
            newEpisode("$id|$idx") {
                this.name = epName
                this.episode = idx + 1
            }
        }

        return if (eps.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.plot = plot
                this.posterUrl = o.str("vod_pic")
                this.tags = o.str("vod_class")?.split(",", "，")?.map { it.trim() }?.filter { it.isNotBlank() }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$id|0") {
                this.plot = plot
                this.posterUrl = o.str("vod_pic")
                this.tags = o.str("vod_class")?.split(",", "，")?.map { it.trim() }?.filter { it.isNotBlank() }
            }
        }
    }

    // ------------------------------------------------------------ 播放
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = data.substringBefore("|")
        val idx = data.substringAfter("|", "0").toIntOrNull() ?: 0

        val o = try {
            itemsOf(apiJson("ac=detail&ids=$id")).firstOrNull()
        } catch (e: Exception) {
            HGhubCtx.toast("$name：详情请求失败 ${e.message?.take(50)}")
            null
        } ?: return false

        val groups = (o.str("vod_play_url") ?: "").split("$$$")
        val group = groups.firstOrNull { it.contains(".m3u8") } ?: groups.firstOrNull() ?: return false
        val segs = group.split("#").filter { it.isNotBlank() }
        val seg = segs.getOrNull(idx) ?: segs.firstOrNull() ?: return false
        var playUrl = seg.substringAfter("$", "").trim()

        if (!playUrl.startsWith("http")) {
            HGhubCtx.toast("$name：地址异常 ${playUrl.take(50)}")
            return false
        }

        // 有些站给的是网页播放器 /play/xxx，页面里通常就写着 m3u8
        if (!playUrl.contains(".m3u8") && !playUrl.contains(".mp4")) {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val html = app.get(playUrl, headers = headers()).text
                    Regex("https?://[^'\"\\s]+\\.m3u8[^'\"\\s]*")
                        .find(html)?.value
                }.getOrNull()
            }
            if (resolved != null) playUrl = resolved
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${seg.substringBefore("$").take(18)}",
                url = playUrl,
                type = if (playUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
