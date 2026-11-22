version = 5

cloudstream {
    description = "黄果短剧 + 森林资源 · 列表/搜索/播放/封面解密"
    authors = listOf("hghub")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "zh"
    iconUrl = "https://huangguoai.io/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // CloudStream 宿主自带这两个库，编译期引用即可
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}