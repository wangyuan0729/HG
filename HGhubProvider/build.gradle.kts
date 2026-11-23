version = 12

cloudstream {
    description = "黄果 + 森林/155/老色X/搜av/大X子"
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