# LightShelf

[轻书架](https://www.lightnovel.app/home)的第三方 [Mihon](https://mihon.app/) 漫画扩展，支持浏览、搜索和阅读轻书架的中文漫画。

需要 **Android 8.0 及以上**和有效的轻书架账号。仅接入漫画区，不支持小说阅读；网站自身的访问限制仍然适用。

[下载最新版本](https://github.com/KimmyXYC/mihon-lightshelf/releases/latest) · [更新记录](CHANGELOG.md)

## 功能

- 浏览热门漫画、最近更新，按名称搜索漫画系列。
- 查看封面、作者、简介、标签和章节列表。
- 保留不同上传者提供的版本，按需加载章节图片。
- 保存邮箱和密码，支持使用刷新 Token 登录。
- 支持香港与 Cloudflare 线路切换，自动刷新登录状态。

## 安装

推荐添加软件源，方便在 Mihon 中安装和更新：

1. 打开 Mihon 的「浏览 → 扩展 → 扩展仓库」，添加以下地址：

   ```text
   https://raw.githubusercontent.com/KimmyXYC/mihon-lightshelf/repo/repo.json
   ```

2. 返回扩展列表并刷新，找到 **轻书架 / LightShelf**，安装后按提示信任仓库或扩展。

只支持旧版索引的客户端可将地址末尾的 `repo.json` 换成 `index.min.json`。也可以从 [Releases](https://github.com/KimmyXYC/mihon-lightshelf/releases/latest) 下载 APK 手动安装。

## 使用

1. 打开轻书架源的设置，填写轻书架邮箱和密码。
2. 返回漫画列表，通过热门、最新或搜索找到漫画。
3. 打开漫画详情，选择需要的上传版本和章节开始阅读。
4. 如果当前线路不可用，在源设置的「服务器线路」中切换香港（默认）或 Cloudflare。

### 登录设置

- **邮箱和密码**：填写后即可使用，密码会加密保存在本机，并在再次打开设置时回填。
- **自定义 Token**：登录成功后会自动填入刷新令牌，也可手动粘贴已有的 `RefreshToken`，无需同时填写邮箱和密码。请勿填写短期有效的访问 JWT。
- **切换登录方式**：清空自定义 Token 后恢复邮箱和密码登录。手动 Token 失效时需更新，不会自动切回已保存账号。

账号配置保存在 Mihon 的应用私有设置中，与网站 WebView 登录相互独立。切换服务器线路会保留登录配置。

### 更新

从 Mihon 扩展列表更新，或安装新版 Release APK，即可覆盖升级。

如果之前安装的是调试签名或其他密钥签名的 APK，需要先卸载旧扩展，再安装正式版本。正式版本之间使用同一签名，可直接升级。

## 开发与构建

需要 JDK 21、Android SDK Platform 36 和 Build Tools 35.0.0。项目自带 Gradle Wrapper；Android Studio 的 Gradle JDK 也应选择 JDK 21。通过 `ANDROID_HOME` 或未跟踪的 `local.properties` 中的 `sdk.dir` 指定 SDK。

```sh
./gradlew :extension:assembleDebug
./gradlew :extension:testDebugUnitTest :extension:lintDebug
./gradlew :extension:assembleRelease
```

- 调试 APK：`extension/build/outputs/apk/debug/extension-debug.apk`
- 未签名 Release APK：`extension/build/outputs/apk/release/extension-release-unsigned.apk`

包名为 `eu.kanade.tachiyomi.extension.zh.lightshelf`，扩展 API 为 `1.4`。宿主依赖使用 `compileOnly`，不打包进 APK。接口说明与测试范围见 [docs/API.md](docs/API.md)。

## 发布与签名

在 `extension/build.gradle.kts` 中更新 `versionName`（`1.4.x`）并递增 `versionCode`，提交并推送后，创建同版本 tag：

```sh
# 示例：发布下一版本
git tag v1.4.4
git push origin v1.4.4
```

`v数字.数字.数字` tag 会触发测试、签名、创建 Release，并更新 `repo` 分支的软件源。tag 必须与 APK 版本一致，不支持预发布后缀。若发布中断，可在 Actions 中重新运行；已公开的 APK 会被验证并复用，旧版本不会覆盖软件源中的新版。

签名由仓库的 `SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS` 和 `SIGNING_KEY_PASSWORD` 四项 Secrets 提供，证书指纹见 [repo.json](repo.json)。维护者的密钥备份位于 `~/.local/share/mihon-lightshelf/signing/`；请保留整份备份，后续发版复用同一密钥，勿提交私钥或密码。

从现有备份重新配置 Secrets：

```sh
python3 .github/scripts/init_signing.py --github-repo KimmyXYC/mihon-lightshelf
```

该脚本在无备份时会创建新密钥；新签名身份还需同步 `repo.json` 的证书指纹。发布和本地签名工具见 [.github/scripts/release.py](.github/scripts/release.py)，可通过 `--help` 查看用法。

## 许可与参考

本项目使用 [Apache-2.0](LICENSE) 许可。官方参考源码：[Flutter](https://github.com/LightNovelShelf/Flutter)、[Web](https://github.com/LightNovelShelf/Web)。
