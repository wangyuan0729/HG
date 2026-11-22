# HGhub CloudStream 插件

CloudStream 4.9.1 的视频源插件。装上之后多一个 **HGHub** 源：主页、搜索、详情、整季播放、封面。

当前版本 **4**，产物 `HGhubProvider.cs3`（23KB）。

---

## 编译

本机环境跑不了 Gradle（Java 加载本地库、写工作区外文件都被系统策略拒绝），所以交给 GitHub Actions：

```yaml
# .github/workflows/build.yml 已包含
- run: ./gradlew HGhubProvider:make --no-daemon
- uses: actions/upload-artifact@v4
  with:
    path: |
      **/*.cs3
      **/*.apk
```

推上去自动跑，Artifacts 里拿 `.cs3`。

> 注意：CloudStream 的产物是 **`.cs3`** 不是 `.apk`，只匹配 `*.apk` 会得到空 artifact。

---

## 接口

| 能力 | 请求 |
|---|---|
| 主页 | `GET /api/videos?page=&size=20` |
| 搜索 | `GET /api/search?q=<kw>`（参数是 `q`，不是 `keyword`） |
| 详情 | `GET /api/videos/{id}` → `data.episodes[]` |
| 播放 | `POST /api/videos/{id}/episodes/{ep}/playback` → `data.streams.main` |
| 封面 | 密文，见下节 |

播放地址是 HLS（m3u8 + AES-128 分片），CloudStream 内置 ExoPlayer 直接播。

---

## 封面解密

站点封面不是图片，是密文：174KB、熵 8.00 bit/byte、头 `09 3d e3 b1`。

网页由 Web Worker 解密，密钥直接写在前端：

```
/static/web/js/plugins/crypto-image-protocol.js
window.CryptoImageProtocol = {
  "decryptEnabled": true,
  "decryptKey": "f5d965df75336270",
  "decryptIV":  "97b60394abc2fbe1"
}
```

- 算法：**AES-128-CBC / NoPadding**（key/iv 都是 16 字节 UTF-8）
- 解密后是标准 JPEG（`ff d8 ff e0`），尾部补齐到 16 字节对齐，需按 PKCS7 去填充

插件里的实现（`coverUrl()`）：下载密文 → AES 解密 → 去填充 → 写入 `cacheDir/hghub_covers/` → `posterUrl = "file://..."`。列表 20 张并发解，磁盘按 URL 哈希缓存。

---

## 踩过的三个坑

1. **Kotlin 元数据版本**
   `cloudstream3` 用 Kotlin 2.4 编译，模板是 2.1，读不了类元数据，所有 import 都 `Unresolved reference`。
   → Kotlin **2.4.21**、AGP **8.13.2**、Gradle **8.14.3**

2. **episode data 被当成相对地址**
   `newEpisode("2916|3")` 会被补全成 `https://host/2916|3`，导致请求变成 `/api/videos/https://...` → 404。
   → 改成 `/ep/$id/$num`，loadLinks 里按「末尾两段纯数字」解析

3. **CORS 预检不放行 content-type**
   服务端 OPTIONS 只回 `Allow-Origin: *` 和 `Allow-Methods`，没有 `Allow-Headers`；而播放接口强制 `Content-Type: application/json`（发表单会回「播放参数无效」）。
   → 浏览器端必须走同源代理；CloudStream 是原生请求，不受影响，但 `app.post` 只能发表单，所以用 OkHttp 直连发 JSON

---

## 维护

| 情况 | 改哪里 |
|---|---|
| 线路失效 | `mainUrl`（38 条候选：19 个高校前缀 × `zekpkeusp.cc` / `kplsifomz.cc`） |
| 封面全变灰 | 重新取 `crypto-image-protocol.js` 的 key/iv，改 `aesKey` / `aesIv` |
| 播放报「播放参数无效」 | 接口要求 body 是 `{}` + JSON content-type，检查 `postJson` |
| CloudStream 提示不更新 | 版本号必须递增（`HGhubProvider/build.gradle.kts` 的 `version`） |

测线路可用性：

```bash
python hghub.py lines          # 仓库外，38 条全测
```
