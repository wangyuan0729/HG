package com.hghub

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HGHub —— 黄果短剧
 *
 * 站点 API（匿名即可，无需登录）：
 *   GET  /api/videos?page=&size=            列表
 *   GET  /api/videos/{id}                   详情（含 episodes）
 *   GET  /api/search?q=                     搜索（向量检索，参数名是 q）
 *   POST /api/videos/{id}/episodes/{ep}/playback   播放地址 → data.streams.main
 */
class HGhubProvider : MainAPI() {
    override var mainUrl = "https://hust.zekpkeusp.cc"
    override var name = "HGHub"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "zh"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    // ------------------------------------------------------------ 网络
    private suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        val text = app.get("$mainUrl$path", headers = headers()).text
        JsonParser.parseString(text).asJsonObject
    }

    /** 播放接口必须 POST + application/json，CloudStream 的 app.post 只能发表单，所以直连 okhttp。 */
    private suspend fun postJson(path: String, body: String = "{}"): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$mainUrl$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { headers().forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${txt.take(160)}")
            JsonParser.parseString(txt).asJsonObject
        }
    }

    // ------------------------------------------------------------ 解析工具
    private fun JsonObject?.obj(key: String): JsonObject? =
        this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject?.arr(key: String) =
        this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let {
            if (it.isJsonPrimitive) it.asJsonPrimitive.asString else null
        }

    private fun JsonObject.intOf(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
            runCatching { it.asInt }.getOrNull()
        }

    /** 数据行 → 卡片 */
    private fun toSearchResult(o: JsonObject): SearchResponse? {
        val id = o.intOf("id") ?: return null
        val title = o.str("title") ?: return null
        val count = o.intOf("episode_count") ?: 0
        val url = "$mainUrl/video/$id"
        return if (count > 1) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = o.str("cover")
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = o.str("cover")
            }
        }
    }

    // ------------------------------------------------------------ 主页
    override val mainPage = mainPageOf("" to "最新")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val root = getJson("/api/videos?page=$page&size=20")
        val items = root.obj("data").arr("items")
            ?.mapNotNull { runCatching { toSearchResult(it.asJsonObject) }.getOrNull() }
            ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------ 搜索
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val root = getJson("/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=1&size=40")
        val data = root.get("data")
        val arr = when {
            data == null || data.isJsonNull -> null
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> data.asJsonObject.arr("items")
            else -> null
        }
        return arr?.mapNotNull { runCatching { toSearchResult(it.asJsonObject) }.getOrNull() } ?: emptyList()
    }

    // ------------------------------------------------------------ 详情
    override suspend fun load(url: String): LoadResponse {
        val id = url.trimEnd('/').substringAfterLast('/')
        val d = getJson("/api/videos/$id").obj("data")
            ?: throw RuntimeException("详情为空")

        val title = d.str("title") ?: "视频 $id"
        val plot = d.str("description")
        val tags = d.arr("tags")?.mapNotNull { runCatching { it.asString }.getOrNull() }
        val eps = d.arr("episodes")?.mapNotNull { el ->
            val e = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val num = e.intOf("ep_num") ?: return@mapNotNull null
            val dur = e.intOf("duration") ?: 0
            newEpisode("$id|$num") {
                this.name = "第 $num 集"
                this.episode = num
                if (dur > 0) this.runTime = dur / 60
            }
        } ?: emptyList()

        return if (eps.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.plot = plot
                this.tags = tags
                this.posterUrl = d.str("cover")
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$id|1") {
                this.plot = plot
                this.tags = tags
                this.posterUrl = d.str("cover")
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
        val ep = data.substringAfter("|", "1").ifBlank { "1" }

        val root = postJson("/api/videos/$id/episodes/$ep/playback")
        val streams = root.obj("data").obj("streams") ?: return false

        var found = false
        // 优先 main，其次任何字符串型流
        val ordered = listOfNotNull(streams.get("main")?.let { "main" to it })
            .plus(streams.entrySet().filter { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
                .map { it.key to it.value })

        for ((_, value) in ordered) {
            val link = runCatching { value.asString }.getOrNull() ?: continue
            if (!link.startsWith("http")) continue
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} 第${ep}集",
                    url = link,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
            break
        }
        return found
    }
}
