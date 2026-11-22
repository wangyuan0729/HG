# HGhub CloudStream 插件

CloudStream 4 的视频源插件。装上之后，CloudStream 里就多一个 **HGHub** 源：主页列表、搜索、分集、整季连播，全部走站点 API。

## 为什么用 GitHub Actions 编译

本机环境跑不了 Gradle：Java 加载 Gradle 的本地库、以及往工作区外写锁文件都被系统策略拒绝（`Failed to initialise native integration` / `拒绝访问`）。这不是代码问题，是这台机器的执行环境限制。

所以插件源码在这里，编译交给 GitHub 的免费 runner。你只需要 fork 或上传这个目录。

## 上传步骤

1. GitHub 新建一个仓库（public 或 private 都行）。
2. 把本目录**里面的内容**全部传上去（`.github` 目录也要，里面有 workflow）。
   - 网页版可以直接拖拽上传；
   - 或者 `git init && git add . && git commit -m init && git push`。
3. 推上去后，仓库 **Actions** 标签页会自动跑 `Build CloudStream plugin`。
4. 跑完点进那次运行，页面底部 **Artifacts** 下载 `HGhubProvider-apk`，解压就是插件 APK。

整个过程 3~5 分钟，不需要本地装 Android SDK。

## 手机安装

1. 装 **CloudStream 4.9.1**（`cloudstream-4.9.1.apk`，已放在下载页）。
2. 把编译出来的插件 APK 传到手机并安装（会和 CloudStream 一样弹「未知来源」，允许即可）。
3. 打开 CloudStream → 设置 → **Extensions / 插件** → 确认列表里出现 **HGHub** 并启用。
4. 回首页，源筛选里选 HGHub。

## 插件实现要点

| 能力 | 实现 |
|---|---|
| 主页 | `GET /api/videos?page=&size=20` |
| 搜索 | `GET /api/search?q=`（参数名是 `q`，不是 `keyword`） |
| 详情 | `GET /api/videos/{id}` → `episodes[]` |
| 播放 | `POST /api/videos/{id}/episodes/{ep}/playback` → `data.streams.main` |
| 播放器 | 返回 `ExtractorLinkType.M3U8`，CloudStream 内置 ExoPlayer 直接播 |

**一个坑**：CloudStream 的 `app.post()` 只能发 `application/x-www-form-urlencoded`，而这个播放接口强制要求 `Content-Type: application/json`（发表单会返回「播放参数无效」）。所以插件里用 OkHttp 直连发 JSON，见 `HGhubProvider.kt` 的 `postJson()`。

## 文件

```
hghub-cloudstream/
├── .github/workflows/build.yml   GitHub Actions 编译流程
├── build.gradle.kts              根构建脚本（AGP 8.7.3 + Kotlin 2.1.0）
├── settings.gradle.kts           自动 include 子项目
├── gradle/wrapper/               Gradle 8.12 wrapper
└── HGhubProvider/
    ├── build.gradle.kts          插件元信息（名称/图标/语言/类型）
    └── src/main/kotlin/com/hghub/
        ├── HGhubPlugin.kt        注册入口（@CloudstreamPlugin）
        └── HGhubProvider.kt      全部逻辑
```

线路写死在 `mainUrl = "https://hust.zekpkeusp.cc"`；站点换线路时改这一行重新编译即可。

## 已知限制

- 封面图：站点封面字节是加密的（下载下来不是图片），所以卡片只有标题，没有海报。
- 线路失效：`hust.zekpkeusp.cc` 打不开时，换 `pku.kplsifomz.cc`、`scu.zekpkeusp.cc` 等 38 条中的任意一条。
