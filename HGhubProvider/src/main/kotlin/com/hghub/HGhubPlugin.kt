package com.hghub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** 持有宿主 Context：用于弹诊断信息，以及写封面缓存目录。 */
object HGhubCtx {
    @Volatile
    private var appCtx: Context? = null

    val context: Context?
        get() = appCtx

    fun init(c: Context) {
        appCtx = c.applicationContext ?: c
    }

    fun toast(msg: String) {
        val c = appCtx ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
        }
    }
}

@CloudstreamPlugin
class HGhubPlugin : Plugin() {
    override fun load(context: Context) {
        HGhubCtx.init(context)

        // 黄果短剧（自有 API + 封面解密）
        registerMainAPI(HGhubProvider())

        // 苹果CMS 资源站（成人向，实测均可用）
        registerMainAPI(
            CmsProvider(
                name = "森林资源",
                mainUrl = "https://beiyong.slapibf.com",
                apiBase = "https://beiyong.slapibf.com/api.php/provide/vod/",
                referer = "https://senlinzy1.com/",
                cats = listOf(
                    "20" to "精品推荐",
                    "22" to "国产色情",
                    "23" to "主播直播",
                    "24" to "中文字幕",
                    "30" to "欧美精品",
                ),
            )
        )
        registerMainAPI(
            CmsProvider(
                name = "155资源",
                mainUrl = "https://155api.com",
                apiBase = "https://155api.com/api.php/provide/vod",
                referer = "https://155api.com/",
                cats = listOf(
                    "1" to "无码专区",
                    "2" to "麻豆传媒",
                    "3" to "制服诱惑",
                    "4" to "中文字幕",
                    "5" to "国产自拍",
                ),
            )
        )
        registerMainAPI(
            CmsProvider(
                name = "老色X资源",
                mainUrl = "https://apilsbzy1.com",
                apiBase = "https://apilsbzy1.com/api.php/provide/vod/",
                referer = "https://apilsbzy1.com/",
                cats = listOf(
                    "20" to "精品推荐",
                    "21" to "国产精品",
                    "22" to "主播秀色",
                    "24" to "日本无码",
                    "25" to "中文字幕",
                ),
            )
        )
        registerMainAPI(
            CmsProvider(
                name = "搜av资源",
                mainUrl = "https://api.souavzyw.net",
                apiBase = "https://api.souavzyw.net/api.php/provide/vod",
                referer = "https://api.souavzyw.net/",
                cats = listOf(
                    "1" to "中文传媒",
                    "2" to "国产",
                    "3" to "欧美AV",
                    "4" to "日本AV",
                ),
            )
        )
        registerMainAPI(
            CmsProvider(
                name = "大X子资源",
                mainUrl = "https://apidanaizi.com",
                apiBase = "https://apidanaizi.com/api.php/provide/vod/",
                referer = "https://apidanaizi.com/",
                cats = listOf(
                    "1" to "视频一区",
                    "2" to "视频二区",
                    "3" to "视频三区",
                    "4" to "视频四区",
                ),
            )
        )
        )
    }
}
