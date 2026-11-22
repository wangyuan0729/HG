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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 森林资源（苹果CMS / MacCMS V10 采集接口）
 *
 *   GET {api}?ac=list                     分类
 *   GET {api}?ac=videolist&pg=N           列表（加 &t=分类id 可过滤，&wd=关键词 搜索）
 *   GET {api}?ac=detail&ids=ID            详情，含 vod_play_url → 直接是 m3u8
 *
 * 这是通用格式：任何苹果CMS资源站换个 baseUrl 就能用。
 */
class SenlinProvider : MainAPI() {
    override var mainUrl = "https://beiyong.slapibf.com"
    override var name = "森林资源"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override var lang = "zh"

    override val hasMainPage = true
    override val hasQuickSearch = true

    private val api = "$mainUrl/api.php/provide/vod/"

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://senlinzy1.com/",
    )

    private suspend fun apiJson(query: String): JsonObject = withContext(Dispatchers.IO) {
        val text = app.get("$api?$query", headers = headers()).text
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
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = o.str("vod_pic")
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = o.str("vod_pic")
            }
        }
    }

    // ------------------------------------------------------------ 主页
    override val mainPage = mainPageOf(
        "" to "最新",
        "20" to "精品推荐",
        "22" to "国产自拍",
        "24" to "中文字幕",
        "30" to "欧美精品",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val t = request.data
        val q = if (t.isBlank()) "ac=videolist&pg=$page" else "ac=videolist&t=$t&pg=$page"
        val root = apiJson(q)
        val items = itemsOf(root).mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------ 搜索
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val wd = URLEncoder.encode(query, "UTF-8")
        val root = apiJson("ac=videolist&wd=$wd&pg=1")
        return itemsOf(root).mapNotNull { toResult(it) }
    }

    // ------------------------------------------------------------ 详情
    override suspend fun load(url: String): LoadResponse {
        val id = url.trimEnd('/').substringAfterLast('/')
        val root = apiJson("ac=detail&ids=$id")
        val o = itemsOf(root).firstOrNull() ?: throw RuntimeException("详情为空")

        val title = o.str("vod_name") ?: "视频 $id"
        val plot = o.str("vod_content")?.replace(Regex("<[^>]+>"), "")?.trim()
        val remarks = o.str("vod_remarks").orEmpty()

        // vod_play_from / vod_play_url 多线路用 $$$ 分隔，剧集用 # 分隔，名称与地址用 $ 分隔
        val froms = (o.str("vod_play_from") ?: "").split("$$$")
        val urlGroups = (o.str("vod_play_url") ?: "").split("$$$")
        val group = urlGroups.firstOrNull { it.contains(".m3u8") } ?: urlGroups.firstOrNull().orEmpty()
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
                this.tags = o.str("vod_class")?.split(",", "，")?.map { it.trim() }
                this.year = o.str("vod_year")?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$id|0") {
                this.plot = plot
                this.posterUrl = o.str("vod_pic")
                this.tags = o.str("vod_class")?.split(",", "，")?.map { it.trim() }
                this.year = o.str("vod_year")?.toIntOrNull()
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

        val root = try {
            apiJson("ac=detail&ids=$id")
        } catch (e: Exception) {
            HGhubCtx.toast("森林资源：详情请求失败 ${e.message?.take(60)}")
            return false
        }
        val o = itemsOf(root).firstOrNull() ?: return false
        val groups = (o.str("vod_play_url") ?: "").split("$$$")
        val group = groups.firstOrNull { it.contains(".m3u8") } ?: groups.firstOrNull() ?: return false
        val segs = group.split("#").filter { it.isNotBlank() }
        val seg = segs.getOrNull(idx) ?: segs.firstOrNull() ?: return false
        val playUrl = seg.substringAfter("$", "").trim()

        if (!playUrl.startsWith("http")) {
            HGhubCtx.toast("森林资源：地址异常 ${playUrl.take(60)}")
            return false
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${seg.substringBefore("$").take(20)}",
                url = playUrl,
                type = if (playUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = "https://senlinzy1.com/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
