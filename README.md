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

发布构建产物：`extension/build/outputs/apk/release/extension-release-unsigned.apk`。普通分支和 PR 的 GitHub Actions 构建保留 APK artifact；正式版本由 tag 触发签名、GitHub Release 和 Mihon 软件源发布。

## 自动发布

1. 在 `extension/build.gradle.kts` 中更新 `versionName` 并递增 `versionCode`，同步版本说明。当前扩展 API 为 `1.4`，因此版本使用 `1.4.x`。
2. 提交并推送修改，再创建与 APK 版本完全一致的 tag。例如发布下一版：

   ```sh
   git tag v1.4.4
   git push origin v1.4.4
   ```

3. `Release LightShelf` 工作流完成测试、Lint、Release 构建、对齐和签名后，创建正式 Release，上传 `lightshelf-v1.4.4.apk` 与对应的 `.apk.sha256` 文件，再更新同仓库的 `repo` 分支。

仅 `v数字.数字.数字` 格式触发正式发布；预发布后缀不会发布。tag 与 APK 版本不符、密钥缺失、证书指纹不符或复用 versionCode 发布不同版本时会失败。Release 使用固定的 Android APK 签名密钥。

发布任务串行排队，避免同时修改软件源；较旧版本可以保留为历史 Release，但不会覆盖软件源中的较新版本。若 Release 已成功、软件源更新失败，在 Actions 中重新运行失败的工作流即可：脚本会验证并复用原 Release 的 APK 与校验文件，不替换已经公开的附件。

首次成功发布前，`repo` 分支及软件源下载地址尚不可用。工作流通过仓库自带的 `GITHUB_TOKEN` 写入 Release 和 `repo` 分支，不需要额外 PAT；仓库规则须允许该分支由 Actions 更新。

### 签名备份与 Secrets

固定签名证书的 SHA-256 指纹记录在根目录 `repo.json` 中。私钥保存在仓库外的 `~/.local/share/mihon-lightshelf/signing/`：`lightshelf.jks` 是 JKS 密钥库，`credentials.json` 保存别名和密码；`certificate.der` 与 `certificate.sha256` 用于核对公开证书。目录权限为 `700`，文件权限为 `600`。请将整个目录另行安全备份，后续版本继续使用同一密钥。

已配置以下 GitHub Actions Secrets：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | JKS 文件的 Base64 编码 |
| `SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名 `lightshelf` |
| `SIGNING_KEY_PASSWORD` | 私钥密码 |

从现有备份重新配置 Secrets：

```sh
python3 .github/scripts/init_signing.py --github-repo KimmyXYC/mihon-lightshelf
```

该脚本在完整备份存在时复用密钥；仅在密钥库和密码文件均不存在时生成新密钥，备份不完整时停止。全新签名身份还需将输出的公开证书指纹写入 `repo.json`；不要为日常发版重新生成密钥。密钥和密码不得提交到 Git 或作为 Release 附件上传。

本地签名可在环境中提供上述四个变量后执行（`ANDROID_HOME` 指向 SDK）：

```sh
python3 .github/scripts/release.py prepare \
  --apk extension/build/outputs/apk/release/extension-release-unsigned.apk \
  --tag v1.4.3 --output extension/build/release
python3 .github/scripts/release.py generate-repo \
  --tag v1.4.3 --output extension/build/release \
  --destination extension/build/mihon-repo
```

上述命令只生成并验证本地产物，不发布到 GitHub。测试发布工具使用 `python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v`。

软件源同时提供旧版 `index.json` / `index.min.json` 和新版 `index-v2.json`，`repo.json` 的 `index_v2` 指向新版 JSON。索引从 APK 读取版本、包名及扩展 API，从当前 Kotlin 入口提取源信息，并沿用 Mihon 的源 ID 算法。改动入口结构时须同步检查生成脚本。索引保留原有 NSFW 标记。

软件源 PNG 图标由当前 Android VectorDrawable 转换并随源码保存；修改 Android 图标后，在安装了 `CairoSVG==2.8.2` 的 Python 环境运行 `python3 .github/scripts/render_icon.py`，提交更新后的 `.github/assets/lightshelf.png`。正常发布不需要 CairoSVG。

## 在 Mihon 中使用

1. 在 Mihon 的「浏览 → 扩展 → 扩展仓库」中添加以下地址，随后从扩展列表安装 LightShelf，并按提示信任仓库或扩展：

   ```text
   https://raw.githubusercontent.com/KimmyXYC/mihon-lightshelf/repo/repo.json
   ```

   只支持旧版索引的客户端可使用同目录的 `index.min.json`。也可从 [Releases](https://github.com/KimmyXYC/mihon-lightshelf/releases) 手动下载签名 APK。
2. 打开该源的设置，填写轻书架邮箱和密码，然后返回漫画列表。
3. 可在「自定义 Token」中查看已登录账号的刷新令牌，或粘贴已有的 `RefreshToken`。无需同时填写邮箱和密码；不要填写仅有效约 30 秒的访问 JWT。清空 Token 后恢复邮箱和密码登录。
4. 如当前线路不可用，可在「服务器线路」切换香港（默认）或 Cloudflare。
5. 使用热门、最新或搜索找到漫画，进入章节阅读。

如果之前安装的是调试签名或其他密钥签名的 APK，需要先卸载旧扩展，再安装正式签名版本；正式版本之间可直接覆盖升级。开发测试仍可使用调试 APK，但不能覆盖正式签名安装包。

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
