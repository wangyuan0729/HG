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

        // 每个源单独 try，一个站出问题不影响其它源注册
        fun register(tag: String, p: () -> com.lagradost.cloudstream3.MainAPI) {
            try {
                registerMainAPI(p())
            } catch (e: Throwable) {
                HGhubCtx.toast("源「$tag」注册失败：${e.javaClass.simpleName} ${e.message?.take(40)}")
            }
        }

        register("HGHub") { HGhubProvider() }
        register("森林资源") { SenlinProviderImpl() }
        register("老色X资源") { LaoseProvider() }
        register("155资源") { Api155Provider() }
        register("搜av资源") { SouavProvider() }
        register("大X子资源") { DaxziProvider() }
    }
}
