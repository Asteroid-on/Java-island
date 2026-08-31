# qishui-api

汽水音乐 Node.js API 服务

## 使用声明

- 本项目仅供个人学习交流使用。
- 下载、解密和解析能力只用于处理你已获得合法授权的内容。
- 商用、传播、批量抓取、版权规避等风险由使用者自己承担。

## 功能状态

| 能力 | 路由 | 状态 |
|---|---|---|
| 发现页 | `/discover` | 已验证可返回发现页块 |
| 发现页混合流 | `/discover/mix` | 已验证可返回推荐歌单块 |
| 推荐歌单 | `/recommend/playlist` | 已验证可提取推荐歌单 |
| 歌单详情 | `/playlist/detail` | 已验证可返回歌单与歌曲资源 |
| 歌单 Feed 媒体 | `/playlist/feed/media` | 已实现 GET/POST，支持歌单媒体流分页 |
| 电台列表 | `/radio/list` | 已验证可从发现页提取电台 |
| 电台歌曲 | `/radio/tracks` | 已验证可返回推荐歌曲 Feed |
| Feed 模式 | `/feed/mode` | 已验证可返回模式数据 |
| Feed 引导 | `/feed/mode/guidance` | 已验证可达 |
| 听歌视频 Feed | `/feed/listen/video` | 已实现 GET/POST，支持听歌视频流分页 |
| DailyMix | `/daily/mix` | 端点存在，裸调通常返回 `ERR_REQUEST_FORBIDDEN` |
| 歌单相关推荐 | `/playlist/related/media` | 端点存在，通常需要登录态 |
| 搜索 | `/search` | 使用 PC 接口，可能需要 Cookie 或签名 header |
| 歌单搜索 | `/search/playlist` | 已实现 GET/POST，返回歌单搜索结果 |
| 混合搜索 | `/search/mixed` | 已实现 GET/POST，返回歌曲/歌单/视频混合结果 |
| 曲目详情 | `/track/detail` | 使用 PC 接口，可能需要 Cookie 或签名 header |
| 视频详情 | `/video/detail` | 已实现 POST（bodyOnly），支持登录态视频详情查询 |
| 播放信息 | `/media/player` | App 播放信息接口，默认只返回脱敏后的播放字段摘要 |
| 分享页歌曲解析 | `/song/detail` | 支持 `track_id` 或分享链接 |
| 分享链接解析 | `/share/resolve` | 已实现 GET/POST，解析 track/playlist/video 资源 ID |
| 歌词 | `/lyric` | 基于分享页或 H5 SEO 结果提取 |
| H5 SEO 曲目 | `/h5/seo/track` | 基于 `track_id` 调用 H5 SEO 接口 |
| 音频信息 | `/audio/info` | 从曲目或分享页提取音频地址、`spade_a`、`PlayAuth` 等可见字段 |
| 下载地址 | `/download/url` | 解析曲目对应音频地址，不直接批量下载 |
| 下载文件 | `/download/file` | 受控下载允许域名内的音频资源，返回 base64 |
| spade 解密 | `/decrypt/spade` | 将 `spade_a` 解出 32 位 AES hex key |
| 本地音频解密 | `/audio/decrypt` | 对传入 base64 或受控 URL 下载的加密音频做 AES-CTR 本地解密 |
| 登录二维码 | `/auth/qrcode` | 已实现 GET，返回本次扫码登录二维码流程信息 |
| 登录二维码状态 | `/auth/qrcode/status` | 已实现 POST，轮询当前扫码状态 |
| 当前账号信息 | `/auth/me` | 已实现 POST，返回当前登录账号信息 |
| 我的歌单 | `/me/playlists` | 已实现 POST，返回当前账号歌单列表 |
| 我的收藏混合流 | `/me/collection/mixed` | 已实现 POST，返回当前账号收藏混合数据 |
| 能力清单 | `/api/capabilities` | 已实现 GET，返回接口能力列表 |

## 安装与运行

```bash
cd D:/Android/AndroidStudioProjects/ai/qishui/qishui-api
npm install
npm start
```

默认监听：`http://0.0.0.0:3300`。

## 常用示例

### 获取推荐歌单

```bash
curl "http://127.0.0.1:3300/recommend/playlist?count=10"
```

### 获取歌单详情

```bash
curl "http://127.0.0.1:3300/playlist/detail?playlist_id=7096700219496368135"
```

### 获取电台列表

```bash
curl "http://127.0.0.1:3300/radio/list"
```

### 获取电台歌曲

```bash
curl "http://127.0.0.1:3300/radio/tracks?radio_id=676100069&count=10"
```

### 解析歌曲或歌词

```bash
curl "http://127.0.0.1:3300/song/detail?track_id=7079108541549643812"
curl "http://127.0.0.1:3300/lyric?track_id=7079108541549643812"
```

### App 播放信息摘要

```bash
curl "http://127.0.0.1:3300/media/player?track_id=7079108541549643812"
```

`/media/player` 来自 APK 动态验证到的 `/luna/media-player`，响应中的 `url_player_info`、`video_model`、`play_auth`、`spade_a`、签名 URL 等播放敏感值会默认脱敏，只保留是否存在、过期时间和字段摘要。

### 音频信息、下载与解密

```bash
curl "http://127.0.0.1:3300/audio/info?track_id=7079108541549643812"
curl "http://127.0.0.1:3300/download/url?track_id=7079108541549643812"
curl -X POST "http://127.0.0.1:3300/decrypt/spade" \
  -H "Content-Type: application/json" \
  -d '{"spade_a":"你的spade_a"}'
```

下载文件会返回 base64，不做批量下载；`audio_url` 建议通过 POST 传入，避免签名参数进入访问日志：

```bash
curl -X POST "http://127.0.0.1:3300/download/file" \
  -H "Content-Type: application/json" \
  -d '{"audio_url":"允许域名内的HTTPS音频URL"}'
```

本地解密支持 POST，传入 `spade_a` 或 `hex_key`，以及 `audio_base64` 或 `audio_url`：

```bash
curl -X POST "http://127.0.0.1:3300/audio/decrypt" \
  -H "Content-Type: application/json" \
  -d '{"hex_key":"00112233445566778899aabbccddeeff","audio_base64":"加密音频base64","return_base64":true}'
```

## 统一响应

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "trace_id": "trace-id"
}
```

## 登录态与敏感信息

- `/auth/qrcode` 与 `/auth/qrcode/status` 只处理当前扫码流程，不持久化账号状态。
- `/auth/qrcode` 会把上游 `data.token`、`data.qrcode`、`data.qrcode_index_url` 扁平输出为 `token`、`qrcode`、`qrcode_index_url`，调用方应展示 `qrcode` 并用 `token` 轮询。
- 当前上游二维码文案要求使用已登录的「抖音 APP」扫码验证；使用汽水音乐 App 扫码可能不会完成确认。
- `/auth/qrcode/status` 会把上游 `data.status` 扁平输出为 `status`，确认后可返回本次扫码获得的 `auth.sessionid`，调用方需自行安全保存。
- 账号接口（`/auth/me`、`/me/playlists`、`/me/collection/mixed`）支持请求头 `Cookie` 或 POST JSON body 的 `sessionid`；服务端仅透传给上游，不会在其他响应中回显入站凭据。
- `include_raw=true` 只返回上游业务响应体，不返回请求 headers、Cookie 或入站 session。
- `/media/player` 会对播放敏感字段做响应脱敏，不返回 `url_player_info`、`video_model`、`play_auth`、`spade_a` 等原始值。
- 如需 PC 接口签名 header，可通过 `.env` 配置 `QISHUI_X_HELIOS`、`QISHUI_X_MEDUSA`。
- PC 搜索使用运行时生成的临时设备标识；如需固定测试环境，可通过 `.env` 配置 `QISHUI_DEVICE_ID`、`QISHUI_INSTALL_ID`、`QISHUI_FP`。
- 下载和解密仅用于合法授权内容；下载 URL 必须为 HTTPS 且命中白名单域名（`QISHUI_DOWNLOAD_ALLOWED_HOSTS`）。
- 下载/解密默认限制单文件大小为 `QISHUI_DOWNLOAD_MAX_BYTES=52428800`。
- POST JSON 请求还受 Express `1mb` body 限制；如需处理大文件，优先传入受控 `audio_url`，不要直接提交大体积 base64。
- 如需关闭本地解密接口，可设置 `QISHUI_ENABLE_DECRYPT=false`。

## 验证命令

```bash
npm test
npm run smoke:live
```

`npm test` 是本地单元测试，不访问上游。`npm run smoke:live` 会访问汽水上游接口，用于确认当前网络环境下的真实可用性。

## 已知边界

- 下载/解密能力涉及版权和合规风险，本项目仅供个人学习交流使用，商用风险自己承担。
