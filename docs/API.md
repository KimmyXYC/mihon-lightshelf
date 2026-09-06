# 漫画接口与验证

核对日期：2026-09-05。

## 参考版本

- [官方 Flutter 源码](https://github.com/LightNovelShelf/Flutter/tree/cc06958d432da29bb7e98bdb1dcfb159d35d5b89)：`cc06958d432da29bb7e98bdb1dcfb159d35d5b89`。
  - `lib/core/network/signalr_connection.dart`：JSON 握手、WebSocket 与帧分隔符。
  - `lib/data/api/api_client_catalog.dart`：漫画列表、系列、内容接口。
  - `lib/data/api/api_client_account.dart` 和 `lib/data/session/auth_controller.dart`：登录、密码摘要和续期。
- [官方 Web 源码](https://github.com/LightNovelShelf/Web/tree/2c294262477b7b387ccc442ad43d87a581235605)：`2c294262477b7b387ccc442ad43d87a581235605`。
  - `src/services/manga/`：请求与响应字段。
  - `src/services/book/`、`src/pages/Manga/data.ts`：漫画已共用 `GetBookInfo`，通过 `Type=Comic` 限定系列查询。
  - `src/services/apiServer.ts`：香港和 Cloudflare 线路；`src/pages/Login/Login.vue`：手动刷新 Token。
- [Mihon 扩展加载器](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt)：扩展 API 1.4、Manifest 元数据和入口类。

官方源码本地参考位于 `.reference/Flutter` 和 `.reference/Web`，已通过 `.gitignore` 排除，仅用于核对协议；APK 不依赖这些本地检出。

## 传输与登录

网站：`https://www.lightnovel.app`；默认 API：`https://api.lightnovel.life`；可选：`https://cf-api.lightnovel.life`。

邮箱保存于源设置。新密码通过 Android Keystore AES-GCM 加密后保存，登录时解密并计算 SHA-256；旧版摘要作为兼容回退。自定义 Token 字段对应长效 `RefreshToken`，打开对话框时读取最新已保存值。手动 Token 优先，明确失效时不回退到已保存账号；清空 Token 或修改邮箱/密码恢复账号登录。线路修改会关闭旧连接、清空访问令牌和图片缓存，保留刷新凭据。

1. `POST /api/user/login`：`email`、小写十六进制 SHA-256 `password`。响应包含 `Token` 和 `RefreshToken`。
2. `POST /api/user/refresh_token`：`token` 为刷新令牌。访问令牌缓存在内存，不持久化。有 JWT `exp` 时按剩余有效期提前 10%（最多 30 秒）刷新；不透明令牌采用官方 Web `.env` 的 `VUE_SESSION_TOKEN_VALIDITY=30000`，复用 30 秒。缓存计时使用单调时钟。
3. `POST /hub/api/negotiate?negotiateVersion=1` 协商并检查 `LongPolling` / `Text` 支持。使用返回的私密 `connectionToken` 作为 `/hub/api?id=...` 参数，所有请求携带 Bearer 访问令牌。先用 GET 激活连接，再 POST `{"protocol":"json","version":1}` 握手，GET 接收握手响应。
4. SignalR JSON 帧以 U+001E 分隔。调用 `type=1`，参数为 `[业务参数, {"UseGzip":false}]`；从 `type=3` 完成消息中解包 `result`。
5. Envelope 包含 `Success`、`Status`、`Msg`、`Response`，JSON 模式通常返回 camelCase。两种字段大小写均可读取。

使用 POST 发送、GET 接收 SignalR 消息。连续调用复用会话，每次调用使用递增 ID；一个后台长轮询读取响应，每 15 秒发送心跳，空闲 30 秒后停止心跳、取消轮询并异步 DELETE。异步清理最多等待 3 秒，修改账号时也可安全从设置界面关闭。并发业务请求串行共享认证与会话，避免同时发起多次令牌刷新。

令牌到期后刷新并更换会话；明确的 HTTP 401、业务认证失效或 Hub unauthorized 错误会清除访问令牌，再刷新并重试一次。自动登录获得的刷新凭据确实失效后才重新登录；手动提供的刷新 Token 失效则提示用户更新。仅漫画只读方法允许自动重试。

### 502 处理

新日志在 2026-09-05 23:07:33 显示图片页加载期间的账号登录 POST 返回 HTTP 502；不能据此断定是图片 CDN 故障，也不能断定是账号错误。排查时主 API 协商端点也返回 502，官方备用端点返回 403，官网显示离线。

1.4.3 对认证接口返回的 502/503/504 等待 500 毫秒后重试一次，不清除已有刷新令牌；漫画会话遇到这些状态、断线或失效连接时重建会话并重试一次，仍复用有效访问令牌。持续故障停止重试并显示失败阶段和 HTTP 状态。HTTP 403/404 本身不再触发删除刷新令牌；只有明确的认证失效或业务响应中的失效状态才会重新登录。服务端持续不可用时，客户端无法恢复服务。

### Mihon 0.20.4 兼容性

1.4.1 在真实设备中触发 `HubCall$socket$1.onClosing(...) overrides final method in okhttp3.WebSocketListener`。Mihon 的 [R8 配置](https://github.com/mihonapp/mihon/blob/v0.20.4/app/proguard-rules.pro)允许优化宿主 OkHttp；因此，Maven 中可覆盖的方法在宿主 APK 中可能已经变为 final，单纯使用 Maven 依赖进行 JVM 测试不能验证此边界。

1.4.2 移除 WebSocketListener 继承，采用服务器实际提供的 HTTP 长轮询。协议依据为 [ASP.NET Core SignalR Transport Protocols](https://github.com/dotnet/aspnetcore/blob/main/src/SignalR/docs/specs/TransportProtocols.md)。新增生产字节码检查，防止重新引入宿主 WebSocket API 依赖。

## 只使用漫画接口

| 方法 | 参数 | 用途 |
| --- | --- | --- |
| `GetComicList` | `Page`, `Size=24`, `Order=view/latest` | 热门 / 更新列表 |
| `SearchComicSeries` | `KeyWords`, `Mode=fuzzy`, `Page`, `Size=24` | 漫画搜索 |
| `GetBooksBySeries` | `Type=Comic`, `SeriesName`, `Order=new`, `Page`, `Size=24` | 分页读取漫画系列的所有上传版本 |
| `GetBookInfo` | `Id` | 漫画详情与章节，检查返回的 `Book.Type=Comic` |
| `GetComicContent` | `Cid`, `Skip`, `Take=6` | 单批图片 |

新版官网详情路由已改为 `/manga/{BookId}`；扩展内部仍保留 `/manga/{编码后的系列名}` 作为稳定的系列标识，避免升级后产生重复书架条目。该旧式系列地址不再是官网的有效详情页地址。章节使用 `/manga/{BookId}/read/{ChapterId}`，不把系列标题误当作书本数字 ID。按上传版本分组、按 `SortNum` 排序后，以 Mihon 要求的逆序返回，上传者和书名写入 `scanlator`。

图片响应中的 `Total` 是整章页数，`Images` 仅为本批图片。先按 `Total` 创建完整页列表，再根据图片页下标计算 `Skip=floor(index/6)*6`。同批图片共享 5 分钟缓存，最多保留 24 批。修改或清除账号时清空缓存。保留图片 URL 的原始查询参数。

官方 Web `2c29426` 移除了 `GetComicSeriesInfo` 和 `GetComicInfo`。旧方法在真实服务端已返回 `Method does not exist`；本扩展改用漫画类型过滤的系列查询和共用详情接口，不接入小说正文。

## 验证范围

- 初版真实站点：账号登录成功；当时的漫画列表、系列搜索、系列详情和章节返回正常。
- 1.4.3 最新接口：已在真实站点确认 `GetBooksBySeries(Type=Comic)`、`GetBookInfo` 返回漫画版本、章节与上传者。使用实际编译的 `LightShelfApi` 和 `ComicCatalog` 在 JVM 连续读取列表、搜索、系列与两批图片，仅 1 次 refresh、1 次 negotiate；166 页章节两批各 6 张图片。等待 32 秒后再次请求，累计为 2 次 refresh、2 次 negotiate，验证有效期内复用和到期恢复。
- 真实章节：166 页章节的 `Skip=0` 与 `Skip=6` 均返回 6 页；首张图片成功解码为 1445 × 2048。
- 1.4.2 真实站点：已使用测试账号验证长轮询协商、JSON 握手、漫画列表读取和 DELETE 清理。
- 单元测试：全部 166 页映射、批次缓存、异常分页、上传版本排序、SHA-256、JWT/不透明令牌缓存和到期、并发认证合并、会话复用和递增调用 ID、认证失败恢复、502 有限重试及凭据保留、403/404 区分、空闲释放、超时清理和 WebSocket ABI 回归检查；新增仅 Token 登录、手动 Token 失效不切换账号、服务器切换、漫画系列分页和小说类型拒绝。
- 真实 Android/Mihon 设备上的安装、设置交互、连续阅读仍需设备验收；本地没有连接的 Android 设备。

测试代码只使用合成账号和本地 MockWebServer，不包含真实账号、密码或令牌。日常构建和 CI 不依赖真实站点凭据。
