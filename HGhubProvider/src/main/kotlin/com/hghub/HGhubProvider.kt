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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HGHub —— 黄果短剧
 *
 *   GET  /api/videos?page=&size=            列表
 *   GET  /api/videos/{id}                   详情（episodes）
 *   GET  /api/search?q=                     搜索
 *   POST /api/videos/{id}/episodes/{ep}/playback   播放地址 → data.streams.main
 *
 * 站点封面图是 AES-128-CBC/NoPadding 加密的密文（熵 8.00），
 * 密钥由前端 /static/web/js/plugins/crypto-image-protocol.js 下发，
 * 这里自行解密并落到缓存目录，用 file:// 交给 CloudStream 显示。
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

    // 封面解密参数（来自 crypto-image-protocol.js）
    private val aesKey = "f5d965df75336270".toByteArray(Charsets.UTF_8)
    private val aesIv = "97b60394abc2fbe1".toByteArray(Charsets.UTF_8)

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

    private fun imageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Referer" to "$mainUrl/",
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

    // ------------------------------------------------------------ 封面上解密
    /** 下载密文 → AES-128-CBC 解密 → 去填充 → 写缓存 → 返回 file:// 地址。失败返回 null。 */
    private suspend fun coverUrl(url: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val ctx = HGhubCtx.context ?: return@withContext null
            val dir = File(ctx.cacheDir, "hghub_covers").apply { mkdirs() }
            val f = File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
            if (f.exists() && f.length() > 512) return@withContext "file://" + f.absolutePath

            val req = Request.Builder().url(url)
                .apply { imageHeaders().forEach { (k, v) -> header(k, v) } }
                .build()
            val raw = client.newCall(req).execute().use { it.body?.bytes() } ?: return@withContext null
            if (raw.isEmpty() || raw.size % 16 != 0) return@withContext null

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            var plain = cipher.doFinal(raw)

            val pad = plain.last().toInt() and 0xFF
            if (pad in 1..16 && plain.size > pad &&
                plain.copyOfRange(plain.size - pad, plain.size).all { (it.toInt() and 0xFF) == pad }
            ) {
                plain = plain.copyOf(plain.size - pad)
            }
            if (plain.size < 4) return@withContext null

            f.writeBytes(plain)
            "file://" + f.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun withPoster(objs: List<JsonObject>): List<SearchResponse> = coroutineScope {
        objs.map { o ->
            async(Dispatchers.IO) {
                runCatching {
                    val r = toSearchResult(o) ?: return@runCatching null
                    coverUrl(o.str("cover"))?.let { r.posterUrl = it }
                    r
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
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
            newTvSeriesSearchResponse(title, url, TvType.TvSeries)
        } else {
            newMovieSearchResponse(title, url, TvType.Movie)
        }
    }

    // ------------------------------------------------------------ 主页
    override val mainPage = mainPageOf("" to "最新")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val root = getJson("/api/videos?page=$page&size=20")
        val objs = root.obj("data").arr("items")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
            ?: emptyList()
        return newHomePageResponse(request.name, withPoster(objs))
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
        val objs = arr?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() } ?: emptyList()
        return withPoster(objs)
    }

    // ------------------------------------------------------------ 详情
    override suspend fun load(url: String): LoadResponse {
        // CloudStream 会把相对路径补全成绝对 URL，所以按「最后一段纯数字」取 id
        val id = url.trimEnd('/').split("/").lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: throw RuntimeException("无法从 url 解析 id: $url")
        val d = getJson("/api/videos/$id").obj("data") ?: throw RuntimeException("详情为空")

        val title = d.str("title") ?: "视频 $id"
        val plot = d.str("description")
        val tags = d.arr("tags")?.mapNotNull { runCatching { it.asString }.getOrNull() }
        val poster = coverUrl(d.str("cover"))

        val eps = d.arr("episodes")?.mapNotNull { el ->
            val e = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val num = e.intOf("ep_num") ?: return@mapNotNull null
            val dur = e.intOf("duration") ?: 0
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
                this.posterUrl = poster
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "/ep/$id/1") {
                this.plot = plot
                this.tags = tags
                this.posterUrl = poster
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
        val segs = data.trimEnd('/').split("/").filter { it.isNotEmpty() }
        val ep = segs.lastOrNull()?.takeIf { it.all(Char::isDigit) } ?: "1"
        val id = segs.dropLast(1).lastOrNull { it.all(Char::isDigit) }
            ?: run {
                HGhubCtx.toast("无法解析 episode data: " + data.take(120))
                return false
            }

        val root = try {
            postJson("/api/videos/$id/episodes/$ep/playback")
        } catch (e: Exception) {
            HGhubCtx.toast("请求播放地址失败｜id=$id ep=$ep｜POST: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            return false
        }

        val streams = root.obj("data").obj("streams")
        if (streams == null) {
            HGhubCtx.toast("接口无 streams｜id=$id ep=$ep｜返回: ${root.toString().take(120)}")
            return false
        }

        val candidates = buildList {
            streams.get("main")?.takeIf { it.isJsonPrimitive }?.let { add(it.asString) }
            streams.entrySet().forEach { e ->
                if (e.value.isJsonPrimitive && e.value.asJsonPrimitive.isString) add(e.value.asString)
            }
        }.filter { it.startsWith("http") }.distinct()

        if (candidates.isEmpty()) {
            HGhubCtx.toast("streams 里没有地址｜id=$id ep=$ep｜${streams.toString().take(120)}")
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
