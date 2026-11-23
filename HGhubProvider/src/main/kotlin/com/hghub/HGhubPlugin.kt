package com.hghub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 持有宿主 Context：用于弹诊断信息、写封面缓存、写注册诊断日志。 */
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

    /** 把注册过程写到外部文件，release 版拿不到 logcat 时靠它排查。 */
    fun log(line: String) {
        val c = appCtx ?: return
        try {
            val dir = c.getExternalFilesDir(null) ?: c.filesDir
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            File(dir, "hghub_register.log").appendText("[$ts] $line\n")
        } catch (_: Throwable) {
        }
    }

    fun resetLog() {
        val c = appCtx ?: return
        try {
            val dir = c.getExternalFilesDir(null) ?: c.filesDir
            File(dir, "hghub_register.log").writeText("")
        } catch (_: Throwable) {
        }
    }
}

@CloudstreamPlugin
class HGhubPlugin : Plugin() {
    override fun load(context: Context) {
        HGhubCtx.init(context)
        HGhubCtx.resetLog()
        HGhubCtx.log("=== plugin load start ===")

        fun register(tag: String, factory: () -> MainAPI) {
            try {
                val api = factory()
                HGhubCtx.log("created $tag: name=${api.name} mainUrl=${api.mainUrl}")
                registerMainAPI(api)
                HGhubCtx.log("registered OK: $tag")
            } catch (e: Throwable) {
                HGhubCtx.log("FAILED $tag: ${e.javaClass.name}: ${e.message}")
                HGhubCtx.log(e.stackTraceToString().take(1200))
                HGhubCtx.toast("源「$tag」注册失败：${e.javaClass.simpleName}")
            }
        }

        register("HGHub") { HGhubProvider() }

        HGhubCtx.log("=== plugin load end ===")
    }
}
