# LightShelf

[轻书架](https://www.lightnovel.app/home)的第三方 Mihon 漫画扩展，只接入漫画区。

| 项目 | 值 |
| --- | --- |
| 项目名 | LightShelf |
| 仓库名 | mihon-lightshelf |
| 扩展名称 | 轻书架 / LightShelf |
| 包名 | `eu.kanade.tachiyomi.extension.zh.lightshelf` |
| 语言 | 中文（`zh`） |
| 扩展 API / 当前版本号 | `1.4` / `1.4.3` |
| 最低 Android 版本 | Android 8.0（API 26） |

## 功能

- 热门漫画、最近更新、漫画系列搜索及翻页。
- 系列封面、作者、简介和标签。
- 各上传版本的章节列表，保留上传者和版本名称。
- 漫画图片阅读：按网站每批 6 页的接口按需获取，缓存相邻页面的图片地址。
- 保存并回填邮箱和密码；登录后自动填入刷新 Token，也可手动填写 Token 登录。
- 香港（hk）与 Cloudflare 服务器线路选择；自动续期、清除登录信息。

1.4.3 缓存有效访问令牌并复用 SignalR HTTP 长轮询会话，避免每批图片重新认证。临时 502/503/504 有限重试，持续服务端故障会提示稍后重试。保留 1.4.2 的 Mihon 0.20.4 WebSocket 回调兼容性修复。可用同一签名的新版 APK 覆盖升级，原有源 ID、书架地址及登录配置不变。

不实现小说阅读。漫画区需要有效账号；网站自身的访问限制仍然适用。没有调用金币购买、官方打包下载或书架写入接口。

## 构建

需要 JDK 21、Android SDK Platform 36 和 Build Tools 35.0.0。项目包含 Gradle 8.13 Wrapper，无需单独安装 Gradle。Android Studio 的 Gradle JDK 也应选择 JDK 21。

通过 `ANDROID_HOME` 指定 Android SDK，或在未跟踪的 `local.properties` 中填写 `sdk.dir=/你的/Android/Sdk`。

```sh
./gradlew :extension:assembleDebug
./gradlew :extension:testDebugUnitTest :extension:lintDebug
```

调试 APK：`extension/build/outputs/apk/debug/extension-debug.apk`。

```sh
./gradlew :extension:assembleRelease
```

发布构建产物：`extension/build/outputs/apk/release/extension-release-unsigned.apk`。正式发布前需使用自己的固定密钥签名；调试密钥只用于本地测试。GitHub Actions 会构建、检查并保存 APK artifact，不会自动发布扩展仓库或 Release。

## 在 Mihon 中使用

1. 将调试 APK 安装到 Android 设备，在 Mihon 的扩展列表中信任此扩展。
2. 打开该源的设置，填写轻书架邮箱和密码，然后返回漫画列表。
3. 可在「自定义 Token」中查看已登录账号的刷新令牌，或粘贴已有的 `RefreshToken`。无需同时填写邮箱和密码；不要填写仅有效约 30 秒的访问 JWT。清空 Token 后恢复邮箱和密码登录。
4. 如当前线路不可用，可在「服务器线路」切换香港（默认）或 Cloudflare。
5. 使用热门、最新或搜索找到漫画，进入章节阅读。

登录配置保存在 Mihon 的应用私有设置中。新输入的密码使用 Android Keystore 的 AES-GCM 加密保存，打开密码对话框时自动填入，界面默认掩码显示；发送给登录接口的仍是 SHA-256 摘要。旧版密码摘要继续有效，但无法还原原密码，重新输入一次即可启用密码回填。刷新 Token 保存在应用私有设置中，属于敏感登录信息。修改邮箱、密码或 Token 会清除当前会话及图片缓存；切换线路保留登录配置。手动 Token 失效时会提示更新，不会自动切回另一个已保存账号。扩展设置与网站 WebView 登录相互独立。

## 开发

```text
extension/src/main/kotlin/eu/kanade/tachiyomi/extension/zh/lightshelf/
├── LightShelf.kt       # Mihon 入口、源设置和模型映射
├── LightShelfApi.kt    # 登录、续期和 SignalR JSON 通信
├── AccessTokenCache.kt # 内存令牌缓存与过期调度
├── HubSession.kt       # 可复用的 HTTP 长轮询会话
├── SavedPassword.kt   # Android Keystore 密码保存
├── ComicCatalog.kt    # 当前漫画系列和书籍详情接口
├── ComicChapters.kt    # 上传版本与章节排序
└── ComicPages.kt       # 图片分页校验及批次缓存
```

宿主提供的扩展 API、Kotlin、OkHttp、RxJava、Jsoup、Injekt 和 AndroidX 均使用 `compileOnly`，不会打包进 APK。扩展入口通过 Manifest 元数据加载，无独立启动界面。

接口依据与测试范围见 [docs/API.md](docs/API.md)。官方参考源码：[Flutter](https://github.com/LightNovelShelf/Flutter)、[Web](https://github.com/LightNovelShelf/Web)。本项目使用 Apache-2.0 许可；参考项目及依赖保留各自许可。
