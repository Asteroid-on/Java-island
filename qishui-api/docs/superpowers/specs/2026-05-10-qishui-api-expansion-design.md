# qishui-api 全量分层扩展设计

日期：2026-05-10
项目路径：`D:/Android/AndroidStudioProjects/ai/qishui/qishui-api`
状态：设计已由用户确认，等待实现计划

## 目标

在现有 `qishui-api` Node.js/Express 服务内，一次性集成 `D:/Android/AndroidStudioProjects/ai/qishui` 目录中已发现、可命名、可封装的汽水音乐 API。实现方式采用“全量但分层实现”：新增所有明确路由和客户端方法，但按来源、稳定性和安全边界组织，避免把服务做成开放式任意代理。

## 已确认决策

- 用户选择范围：一次性全量集成汽水 API。
- 用户选择方案：方案 1，全量但分层实现。
- 联网验证：允许运行访问汽水/抖音上游的 live smoke。
- 敏感字段边界：允许受控 raw 输出上游业务响应；入站 `Cookie`、`sessionid`、请求 headers 不回显、不落盘、不写日志、不写入 OpenAPI 示例。
- 提交边界：本设计文件不自动提交 commit，除非用户明确要求。

## 证据来源

- `qishui-api/src/qishuiClient.js`：现有统一上游客户端。
- `qishui-api/module/*.js`：现有自动挂载路由模块。
- `qishui-api/README.md`：现有能力矩阵和安全说明。
- `qishui-api/openapi.json`：现有接口契约。
- `PopDownloader-main/server/config/qishui-auth.js`：Passport、账号、我的歌单、收藏、`track_v2`、`video_v2` 上游配置。
- `musicdl-master/musicdl/modules/sources/soda.py`：PC 搜索、歌单详情分页、`track_v2`、`url_player_info`、歌词补全、下载解析线索。
- `汽水音乐分析报告.md`：APK 静态逆向、推荐流、发现页、歌单 Feed、风险与边界总结。
- `qishui_net_log.txt` 与 `frida_safe_net_logger.js`：运行时抓包和 Hook 线索，用于后续 live smoke 对照。

## API 范围与路由分组

### 搜索

- 保留并完善：`/search`
- 新增：`/search/playlist`
- 可选新增：`/search/mixed`

`/search` 继续面向歌曲搜索。`/search/playlist` 优先使用可验证的 PC 歌单搜索路径；如果上游没有独立歌单搜索端点，则使用混合搜索结果抽取歌单，并在响应中标记 `source_strategy`。

### 歌单

- 保留并完善：`/playlist/detail`
- 保留并完善：`/playlist/related/media`
- 新增：`/playlist/feed/media`

`/playlist/detail` 支持分页参数并统一输出歌单与媒体资源。`/playlist/related/media` 对应 App 歌单相关媒体推荐。`/playlist/feed/media` 对应 APK 静态确认的 `/luna/feed/playlist-media`，可能需要真实 App 上下文。

### 推荐与发现

- 保留并完善：`/discover`
- 保留并完善：`/discover/mix`
- 保留并完善：`/daily/mix`
- 保留并完善：`/radio/list`
- 保留并完善：`/radio/tracks`
- 新增：`/feed/listen/video`

推荐类接口统一承载 App Luna 推荐流。对可能需要 App 上下文的接口，在上游返回 forbidden 或上下文错误时，响应中标记 `requires_app_context: true`，不伪造成功结果。

### 曲目与视频

- 保留并完善：`/track/detail`
- 新增：`/video/detail`
- 保留并完善：`/h5/seo/track`
- 保留并完善：`/media/player`

`/video/detail` 对应 PC `video_v2`。`/media/player` 继续用于 App 播放信息摘要，支持受控 raw 输出，但不回显入站凭据。

### 账号与个人资源

- 新增：`/auth/qrcode`
- 新增：`/auth/qrcode/status`
- 新增：`/auth/me`
- 新增：`/me/playlists`
- 新增：`/me/collection/mixed`

扫码登录流程只处理当前请求，不在服务端持久化账号状态。`/auth/qrcode/status` 可以返回本次扫码获得的 `sessionid`，供调用方自行保存；之后的账号资源接口接受 `Cookie` 请求头或 body 中的受控 `sessionid` 参数并转发给上游。

### 歌词与分享页

- 保留并完善：`/song/detail`
- 保留并完善：`/lyric`
- 新增：`/share/resolve`

`/share/resolve` 统一分享链接、短链、`track_id`、`playlist_id`、`video_id` 的识别与解析。分享页请求必须保留汽水/抖音域名白名单，防止服务端任意 URL 请求。

### 音频、下载与解密

- 保留并完善：`/audio/info`
- 保留并完善：`/download/url`
- 保留并完善：`/download/file`
- 保留并完善：`/decrypt/spade`
- 保留并完善：`/audio/decrypt`

下载和解密接口继续只允许 POST body 传参。下载 URL 必须是 HTTPS，且 hostname 必须命中 `QISHUI_DOWNLOAD_ALLOWED_HOSTS`。文件大小受 `QISHUI_DOWNLOAD_MAX_BYTES` 限制。解密能力可通过 `QISHUI_ENABLE_DECRYPT=false` 禁用。

### 调试与能力清单

- 保留：`/health`
- 保留：`/api/list`
- 新增：`/api/capabilities`

`/api/capabilities` 输出能力矩阵、路由、是否需要登录态、是否可能需要 App 上下文、是否支持 raw 输出，不输出任何密钥、Cookie、sessionid 或签名头。

## 组件设计

| 层级 | 文件/目录 | 设计 |
|---|---|---|
| 路由层 | `module/*.js` | 每个 API 一个小模块，继续由 `server.js` 自动挂载。新增模块使用下划线命名并映射为斜杠路由。 |
| 客户端层 | `src/qishuiClient.js` | 继续作为统一上游客户端，按 PC、Luna App、H5、Passport 方法分组。 |
| 规范化层 | `src/normalizers.js` | 扩展歌单、视频、搜索结果、账号资源、Feed item 的统一输出结构。 |
| ID/参数层 | `src/ids.js` | 继续解析 `track_id`、`playlist_id`；新增 `video_id`、分页参数、搜索关键词解析。 |
| 安全层 | `src/download.js`、`src/qishuiClient.js` | 保留下载白名单、大小限制、敏感字段过滤；新增 raw 输出过滤函数。 |
| 文档层 | `README.md`、`openapi.json` | 同步所有新增路由、参数、登录态说明、已知边界。 |
| 测试层 | `test/*.test.js`、`test/live-smoke.js` | 单元测试 mock 上游；live smoke 验证允许联网的核心路径。 |

## 数据流

### 通用请求流

```text
HTTP 请求
  -> server.js mergeInput + trace_id + Cookie 透传
  -> module/<route>.js
  -> QishuiClient.<method>()
  -> requestJson()
  -> 上游 PC / Luna / H5 / Passport API
  -> normalize / redact / raw filter
  -> WebResponse { code, message, data, trace_id }
```

### 账号流

```text
/auth/qrcode
  -> Passport get_qrcode
  -> 返回二维码 token / URL，不保存账号状态

/auth/qrcode/status
  -> Passport check_qrconnect
  -> 从 Set-Cookie 提取 sessionid
  -> 返回登录状态和本次扫码得到的 sessionid
  -> 服务端不落盘、不日志输出、不在后续响应回显入站 Cookie
```

### 播放与下载流

```text
track_id / video_id / url
  -> track_v2 / video_v2 / seo_track / share resolve
  -> audio_info / media_player / download_url
  -> download_file / audio_decrypt 仅走 POST
  -> HTTPS + allowlist + max_bytes
```

## 错误处理

| 场景 | 行为 |
|---|---|
| 缺少必要参数 | 抛 `ValidationError`，返回统一错误响应。 |
| 上游 HTTP 非 2xx | 抛 `UpstreamError`，保留上游状态、路径和脱敏摘要。 |
| 需要登录态但未传 Cookie/sessionid | 返回明确错误，不自动登录、不伪造状态。 |
| App 上下文不足导致 forbidden | 返回上游错误，并在响应中标记 `requires_app_context: true`。 |
| raw 输出 | `include_raw=true` 时返回上游业务 body；不包含请求 headers、Cookie、sessionid。 |
| 下载文件 | 仅 POST；仅 HTTPS；仅允许白名单域名；受 `QISHUI_DOWNLOAD_MAX_BYTES` 限制。 |
| 解密接口 | 仅 POST；参数只允许 body；可用 `QISHUI_ENABLE_DECRYPT=false` 禁用。 |
| 扫码登录 | 只返回本次扫码得到的 `sessionid`；服务端不落盘、不自动复用。 |

## 安全边界

- 不硬编码真实 Cookie、`sessionid`、`X-Helios`、`X-Medusa`、`PlayAuth`、`spade_a`。
- 不在日志、错误详情、OpenAPI 示例中输出账号凭据。
- 不新增开放式 URL 代理。
- 分享页解析只允许汽水/抖音域名。
- 下载只允许 HTTPS 与配置白名单域名。
- raw 输出只面向上游业务响应，不包含请求侧凭据。
- VIP、下载、解密能力只用于处理调用方已合法授权的内容。

## 测试策略

| 类型 | 命令/方式 | 覆盖 |
|---|---|---|
| 单元测试 | `npm test` | 参数校验、路由挂载、ID 解析、Cookie 不泄露、raw 过滤、下载限制。 |
| Mock 上游测试 | `global.fetch` mock | 搜索歌单、账号、视频详情、Feed 接口 payload 和响应 normalize。 |
| Live smoke | `npm run smoke:live` 或新增 smoke 子集 | 验证推荐歌单、搜索歌单、歌单详情、视频/曲目详情等可达性。 |
| 手工验证 | `/api/list`、`/api/capabilities` | 检查全量路由是否挂载和能力矩阵是否正确。 |

## 完成标准

1. 新增全量路由模块，并能在 `/api/list` 中列出。
2. `openapi.json` 覆盖新增接口、参数和响应说明。
3. `README.md` 包含完整功能矩阵、登录态说明、敏感字段规则、live smoke 说明。
4. `npm test` 全部通过。
5. 允许联网的 live smoke 至少覆盖核心可达接口；不可达接口按上游原因分类记录。
6. 不新增硬编码 Cookie、`sessionid`、真实 `X-Helios`、`X-Medusa`、`PlayAuth` 或 `spade_a` 示例值。
7. 不把服务做成任意 URL 代理；分享页和下载 URL 保留域名白名单。

## 实施注意事项

- 一次性全量实现仍需按代码提交粒度分块编辑，先扩展纯参数和 normalize，再接路由，再更新文档和测试。
- `src/qishuiClient.js` 会继续变大；本轮为最小架构变更，不拆分客户端文件。
- 对缺少动态样例的 App 接口，只实现明确端点、参数和错误分类，不伪造可用结果。
- 如果 live smoke 暴露某个上游接口路径不可达，需要在 README 已知边界中记录实际返回，而不是静默降级。
