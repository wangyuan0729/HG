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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HGHub —— 黄果短剧
 *
 *   GET  /api/videos?page=&size=            列表
 *   GET  /api/videos/{id}                   详情（含 episodes）
 *   GET  /api/search?q=                     搜索（参数名是 q）
 *   POST /api/videos/{id}/episodes/{ep}/playback   播放地址 → data.streams.main
 */
class HGhubProvider : MainAPI() {
    override var mainUrl = "https://hust.zekpkeusp.cc"
    override var name = "HGHub"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "zh"

    override val hasMainPage = true
    override val hasQuickSearch = true

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
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

    /** 播放接口必须 POST + application/json；CloudStream 的 app.post 只能发表单，所以用 okhttp 直连。 */
    private suspend fun postJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$mainUrl$path")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .apply { headers().forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${txt.take(120)}")
            }
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

    private fun toSearchResult(o: JsonObject): SearchResponse? {
        val id = o.intOf("id") ?: return null
        val title = o.str("title") ?: return null
        val count = o.intOf("episode_count") ?: 0
        val url = "$mainUrl/video/$id"
        return if (count > 1) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = o.str("cover") }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = o.str("cover") }
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
        // CloudStream 会把相对路径补全成绝对 URL，所以按「最后一段纯数字」取 id，别用固定分隔符
        val id = url.trimEnd('/').split("/").lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: throw RuntimeException("无法从 url 解析 id: $url")
        val d = getJson("/api/videos/$id").obj("data") ?: throw RuntimeException("详情为空")

        val title = d.str("title") ?: "视频 $id"
        val plot = d.str("description")
        val tags = d.arr("tags")?.mapNotNull { runCatching { it.asString }.getOrNull() }
        val eps = d.arr("episodes")?.mapNotNull { el ->
            val e = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val num = e.intOf("ep_num") ?: return@mapNotNull null
            val dur = e.intOf("duration") ?: 0
            // 用相对路径形式，避免 CloudStream 把 "id|ep" 当成相对地址补全
            newEpisode("/ep/$id/$num") {
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
            newMovieLoadResponse(title, url, TvType.Movie, "/ep/$id/1") {
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
        // data 已被 CloudStream 补全成 https://host/ep/<id>/<ep>，取末尾两段数字
        val segs = data.trimEnd('/').split("/").filter { it.isNotEmpty() }
        val ep = segs.lastOrNull()?.takeIf { it.all(Char::isDigit) } ?: "1"
        val id = segs.dropLast(1).lastOrNull { it.all(Char::isDigit) }
            ?: run {
                HGhubCtx.toast("无法解析 episode data: " + data.take(120))
                return false
            }
        val diag = StringBuilder("id=$id ep=$ep")

        // 诊断 1：DNS
        try {
            val addrs = withContext(Dispatchers.IO) {
                java.net.InetAddress.getAllByName("hust.zekpkeusp.cc").joinToString(",") { it.hostAddress ?: "?" }
            }
            diag.append(" | dns=").append(addrs.take(40))
        } catch (e: Exception) {
            diag.append(" | dns失败:").append(e.message?.take(40))
        }

        // 诊断 2：能不能 GET（复用宿主网络栈）
        try {
            val g = app.get("$mainUrl/api/videos/$id", headers = headers()).text
            diag.append(" | GET=").append(g.length).append("B")
        } catch (e: Exception) {
            diag.append(" | GET失败:").append(e.javaClass.simpleName).append(" ").append(e.message?.take(60))
        }

        // 请求播放地址
        val root = try {
            postJson("/api/videos/$id/episodes/$ep/playback")
        } catch (e: Exception) {
            HGhubCtx.toast("请求播放地址失败｜$diag｜POST: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            return false
        }

        val streams = root.obj("data").obj("streams")
        if (streams == null) {
            HGhubCtx.toast("接口无 streams｜$diag｜返回: ${root.toString().take(120)}")
            return false
        }

        // 优先 main，其次任何字符串型流
        val candidates = buildList {
            streams.get("main")?.takeIf { it.isJsonPrimitive }?.let { add(it.asString) }
            streams.entrySet().forEach { e ->
                if (e.value.isJsonPrimitive && e.value.asJsonPrimitive.isString) add(e.value.asString)
            }
        }.filter { it.startsWith("http") }.distinct()

        if (candidates.isEmpty()) {
            HGhubCtx.toast("streams 里没有地址｜$diag｜${streams.toString().take(120)}")
            return false
        }

        candidates.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 第${ep}集",
                    url = link,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
