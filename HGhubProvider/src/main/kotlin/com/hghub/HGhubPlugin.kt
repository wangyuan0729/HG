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
        registerMainAPI(HGhubProvider())
        registerMainAPI(SenlinProvider())
    }
}
