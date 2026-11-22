package com.hghub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** 持有宿主 Context，用于把错误显示在屏幕上（否则只能翻 logcat）。 */
object HGhubCtx {
    @Volatile
    private var ctx: Context? = null

    fun init(c: Context) {
        ctx = c.applicationContext ?: c
    }

    fun toast(msg: String) {
        val c = ctx ?: return
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
    }
}
