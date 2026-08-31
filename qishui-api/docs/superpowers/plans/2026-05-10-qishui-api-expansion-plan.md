# qishui-api Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the known Qishui/Soda Music API surface into `qishui-api` with typed route modules, normalized responses, documented contracts, and verification.

**Architecture:** Keep the existing Express auto-module architecture. Add focused route modules in `module/`, extend `QishuiClient` as the single upstream client, extend `ids` and `normalizers` for reusable parsing, and keep download/decrypt protections intact. The plan avoids an open proxy and treats App-only endpoints as explicitly classified upstream-context APIs.

**Tech Stack:** Node.js >= 18, Express 5, built-in `fetch`, built-in `node:test`, CommonJS modules, OpenAPI JSON, Markdown docs.

---

## Scope Check

The approved spec covers several API families: search, playlist, discovery/feed, track/video, account, share parsing, audio/download/decrypt, and capability reporting. The user explicitly selected one full-plan scope, so this plan keeps one implementation plan but divides work into independently testable vertical slices.

Current path `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api` was observed as not being a Git repository. Each task ends with a checkpoint command instead of a commit. If a Git repository is initialized later and the user explicitly requests commits, commit at those checkpoints only after user authorization.

## File Structure

### Existing files to modify

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/ids.js`  
  Adds `extractVideoId`, common bounded count helper, and shared resource ID extraction.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/normalizers.js`  
  Adds search result, video, account resource, mixed collection, and capability-safe normalizers.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/qishuiClient.js`  
  Adds PC search playlist/mixed, Passport auth, account APIs, video detail, share resolve, playlist feed media, listen video feed, raw filtering, and App-context classification.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`  
  Adds unit and mock-upstream tests for new helpers, routes, auth, search playlist, feed payloads, and security boundaries.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/live-smoke.js`  
  Expands live smoke to exercise the safe core endpoints and classify upstream-context failures.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/README.md`  
  Updates capability matrix, examples, login-state rules, known boundaries, and live smoke notes.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/openapi.json`  
  Adds new paths and security notes without including real secrets.

### New files to create

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/capabilities.js`  
  Central capability matrix used by `/api/capabilities` and docs validation.

- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/search_playlist.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/search_mixed.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/playlist_feed_media.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/feed_listen_video.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/video_detail.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/auth_qrcode.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/auth_qrcode_status.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/auth_me.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/me_playlists.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/me_collection_mixed.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/share_resolve.js`
- `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/api_capabilities.js`

---

### Task 1: Shared ID, search, account, and capability helpers

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/ids.js`
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/normalizers.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/capabilities.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write the failing helper tests**

Append this block to `test/smoke.test.js`:

```js
test('extractVideoId parses video links and ugc ids separately', () => {
  const { extractVideoId } = require('../src/ids')
  assert.equal(extractVideoId('https://music.douyin.com/qishui/share/video?ugc_video_id=7256000000000000000'), '7256000000000000000')
  assert.equal(extractVideoId('/video/7256000000000000000'), '7256000000000000000')
})

test('normalizeSearchGroups extracts tracks playlists and videos', () => {
  const { normalizeSearchGroups } = require('../src/normalizers')
  const normalized = normalizeSearchGroups({
    result_groups: [{
      data: [
        { entity: { track: { id: '1', name: '歌', artists: [{ id: '2', name: '歌手' }] } } },
        { entity: { playlist: { id: '3', title: '歌单', count_tracks: 8 } } },
        { entity: { video: { id: '4', title: '视频', cover_url: 'https://example.invalid/cover.jpg' } } },
      ],
    }],
  })
  assert.equal(normalized.tracks[0].id, '1')
  assert.equal(normalized.playlists[0].id, '3')
  assert.equal(normalized.videos[0].id, '4')
})

test('capabilities do not expose secrets', () => {
  const { capabilities } = require('../src/capabilities')
  const text = JSON.stringify(capabilities)
  assert.equal(text.includes('sessionid='), false)
  assert.equal(text.includes('X-Helios'), false)
  assert.equal(text.includes('PlayAuth'), false)
})
```

- [ ] **Step 2: Run the helper tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL with `extractVideoId is not a function`, `normalizeSearchGroups is not a function`, or missing `../src/capabilities`.

- [ ] **Step 3: Replace `src/ids.js` with the complete helper implementation**

Replace `src/ids.js` with:

```js
const { ValidationError } = require('./errors')

function requireString(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new ValidationError(`${name} 不能为空`)
  }
  return value.trim()
}

function optionalInt(value, fallback, name) {
  if (value === undefined || value === null || value === '') return fallback
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 0) throw new ValidationError(`${name} 必须是非负整数`)
  return parsed
}

function optionalPositiveInt(value, fallback, name) {
  const parsed = optionalInt(value, fallback, name)
  if (parsed <= 0) throw new ValidationError(`${name} 必须是正整数`)
  return parsed
}

function boundedPositiveInt(value, fallback, maximum, name) {
  return Math.min(optionalPositiveInt(value, fallback, name), maximum)
}

function decodedCandidates(input) {
  if (!input) return []
  const raw = String(input).trim()
  try {
    return [raw, decodeURIComponent(raw)]
  } catch (_) {
    return [raw]
  }
}

function extractByPatterns(input, patterns) {
  for (const candidate of decodedCandidates(input)) {
    for (const pattern of patterns) {
      const match = candidate.match(pattern)
      if (match) return match[1]
    }
  }
  return ''
}

function extractTrackId(input) {
  return extractByPatterns(input, [
    /[?&]track_id=(\d+)/,
    /\/(?:track|song)\/(\d+)/,
    /\b(\d{6,})\b/,
  ])
}

function extractVideoId(input) {
  return extractByPatterns(input, [
    /[?&](?:ugc_video_id|video_id)=(\d+)/,
    /\/(?:video|ugc_video)\/(\d+)/,
    /\b(\d{6,})\b/,
  ])
}

function extractPlaylistId(input) {
  return extractByPatterns(input, [
    /[?&]playlist_id=(\d+)/,
    /\/(?:playlist|list)\/(\d+)/,
    /\b(\d{6,})\b/,
  ])
}

function extractResourceIds(input) {
  return {
    track_id: extractTrackId(input),
    video_id: extractVideoId(input),
    playlist_id: extractPlaylistId(input),
  }
}

module.exports = {
  requireString,
  optionalInt,
  optionalPositiveInt,
  boundedPositiveInt,
  extractTrackId,
  extractVideoId,
  extractPlaylistId,
  extractResourceIds,
}
```

- [ ] **Step 4: Append new normalizers and update exports**

In `src/normalizers.js`, add this block before `module.exports`:

```js
function normalizeVideo(video) {
  if (!video || typeof video !== 'object') return null
  return {
    id: video.id || video.video_id ? String(video.id || video.video_id) : '',
    title: video.title || video.name || video.desc || '',
    cover_url: pickUrl(video.cover_url || video.cover || video.url_cover || video.poster),
    duration: video.duration ?? video.duration_ms ?? 0,
    media_type: video.media_type || video.type || 'ugc_video',
    raw: video,
  }
}

function unwrapEntity(resource) {
  return resource?.entity || resource || {}
}

function normalizeMixedResource(resource) {
  const entity = unwrapEntity(resource)
  const track = normalizeTrack(entity.track_wrapper?.track || entity.track || resource?.track)
  if (track && track.id) return { type: 'track', track, raw: resource }
  const playlist = normalizePlaylist(entity.playlist || resource?.playlist)
  if (playlist && playlist.id) return { type: 'playlist', playlist, raw: resource }
  const video = normalizeVideo(entity.video || entity.ugc_video || resource?.video)
  if (video && video.id) return { type: 'video', video, raw: resource }
  return null
}

function normalizeSearchGroups(data) {
  const groups = Array.isArray(data?.result_groups) ? data.result_groups : []
  const resources = groups.flatMap((group) => Array.isArray(group.data) ? group.data : [])
  const mixed = resources.map(normalizeMixedResource).filter(Boolean)
  return {
    tracks: mixed.filter((item) => item.type === 'track').map((item) => item.track),
    playlists: mixed.filter((item) => item.type === 'playlist').map((item) => item.playlist),
    videos: mixed.filter((item) => item.type === 'video').map((item) => item.video),
    mixed,
  }
}

function normalizeProfile(data) {
  const info = data?.my_info || data?.user || data || {}
  return {
    id: info.id ? String(info.id) : '',
    nickname: info.nickname || info.name || '',
    douyin_id: info.douyin_id || '',
    is_vip: Boolean(info.is_vip),
    vip_stage: info.vip_stage || '',
    raw: data,
  }
}

function normalizeMixedCollections(data) {
  const collections = Array.isArray(data?.mixed_collections) ? data.mixed_collections : []
  return collections.map((item) => ({
    item_type: item.item_type || '',
    playlist: normalizePlaylist(item.playlist),
    track: normalizeTrack(item.track || item.media?.track),
    video: normalizeVideo(item.video || item.media?.video),
    raw: item,
  })).filter((item) => item.playlist || item.track || item.video)
}
```

Then replace the export object at the bottom of `src/normalizers.js` with:

```js
module.exports = {
  pickUrl,
  normalizeTrack,
  normalizeFeedItem,
  normalizePlaylist,
  normalizePlaylistDetail,
  normalizeVideo,
  normalizeMixedResource,
  normalizeSearchGroups,
  normalizeProfile,
  normalizeMixedCollections,
  extractPlaylistsFromDiscoverMix,
  extractRadiosFromDiscover,
}
```

- [ ] **Step 5: Create `src/capabilities.js`**

Create `src/capabilities.js` with:

```js
const capabilities = [
  { group: 'search', route: '/search', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: 'PC 搜索歌曲' },
  { group: 'search', route: '/search/playlist', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: 'PC 搜索歌单或从混合搜索提取歌单' },
  { group: 'search', route: '/search/mixed', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: 'PC 混合搜索' },
  { group: 'playlist', route: '/playlist/detail', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '歌单详情与媒体资源' },
  { group: 'playlist', route: '/playlist/related/media', methods: ['GET', 'POST'], auth: true, app_context: true, raw: true, description: '歌单相关推荐媒体' },
  { group: 'playlist', route: '/playlist/feed/media', methods: ['GET', 'POST'], auth: true, app_context: true, raw: true, description: '歌单 Feed 媒体流' },
  { group: 'feed', route: '/discover', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '发现页块' },
  { group: 'feed', route: '/discover/mix', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '发现页混合推荐流' },
  { group: 'feed', route: '/daily/mix', methods: ['GET', 'POST'], auth: true, app_context: true, raw: true, description: 'DailyMix 推荐歌曲流' },
  { group: 'feed', route: '/radio/list', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '电台列表' },
  { group: 'feed', route: '/radio/tracks', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '电台歌曲 Feed' },
  { group: 'feed', route: '/feed/listen/video', methods: ['GET', 'POST'], auth: true, app_context: true, raw: true, description: '听视频 Feed' },
  { group: 'media', route: '/track/detail', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: 'PC 曲目详情' },
  { group: 'media', route: '/video/detail', methods: ['GET', 'POST'], auth: true, app_context: false, raw: true, description: 'PC 视频详情' },
  { group: 'media', route: '/h5/seo/track', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: 'H5 SEO 曲目详情' },
  { group: 'media', route: '/media/player', methods: ['GET', 'POST'], auth: true, app_context: true, raw: false, description: 'App 播放信息摘要' },
  { group: 'auth', route: '/auth/qrcode', methods: ['GET'], auth: false, app_context: false, raw: true, description: '获取扫码登录二维码' },
  { group: 'auth', route: '/auth/qrcode/status', methods: ['POST'], auth: false, app_context: false, raw: true, description: '轮询扫码登录状态' },
  { group: 'auth', route: '/auth/me', methods: ['POST'], auth: true, app_context: false, raw: true, description: '账号信息' },
  { group: 'auth', route: '/me/playlists', methods: ['POST'], auth: true, app_context: false, raw: true, description: '我的歌单' },
  { group: 'auth', route: '/me/collection/mixed', methods: ['POST'], auth: true, app_context: false, raw: true, description: '我的收藏混合资源' },
  { group: 'share', route: '/song/detail', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '歌曲详情或分享页解析' },
  { group: 'share', route: '/lyric', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '歌词解析' },
  { group: 'share', route: '/share/resolve', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '分享链接资源识别' },
  { group: 'audio', route: '/audio/info', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '音频信息与可见解密字段' },
  { group: 'audio', route: '/download/url', methods: ['GET', 'POST'], auth: false, app_context: false, raw: true, description: '解析曲目音频下载地址' },
  { group: 'audio', route: '/download/file', methods: ['POST'], auth: false, app_context: false, raw: false, description: '受控下载音频文件' },
  { group: 'audio', route: '/decrypt/spade', methods: ['POST'], auth: false, app_context: false, raw: false, description: '解出 spade_a 对应 AES key' },
  { group: 'audio', route: '/audio/decrypt', methods: ['POST'], auth: false, app_context: false, raw: false, description: '本地解密加密音频' },
  { group: 'system', route: '/api/capabilities', methods: ['GET'], auth: false, app_context: false, raw: false, description: '能力矩阵' },
]

module.exports = { capabilities }
```

- [ ] **Step 6: Run helper tests and verify they pass**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: PASS for the new helper tests and existing tests.

- [ ] **Step 7: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 2: PC search playlist and mixed search routes

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/qishuiClient.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/search_playlist.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/search_mixed.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write failing search API tests**

Append this block to `test/smoke.test.js`:

```js
test('searchPlaylists calls PC playlist search and normalizes playlists', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(options.method, 'GET')
    assert.equal(String(url).includes('/luna/pc/search/playlist'), true)
    return new Response(JSON.stringify({
      result_groups: [{ data: [{ entity: { playlist: { id: '9001', title: '测试歌单', count_tracks: 12 } } }] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.searchPlaylists({ keywords: '测试' })
    assert.equal(result.source_strategy, 'pc_search_playlist')
    assert.equal(result.playlists[0].id, '9001')
    assert.equal(result.playlists[0].title, '测试歌单')
  } finally {
    global.fetch = originalFetch
  }
})

test('searchMixed calls PC mixed search and returns typed resources', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url) => {
    assert.equal(String(url).includes('/luna/pc/search/mixed'), true)
    return new Response(JSON.stringify({
      result_groups: [{ data: [
        { entity: { track: { id: '1', name: '歌' } } },
        { entity: { playlist: { id: '2', title: '歌单' } } },
      ] }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.searchMixed({ keywords: '测试' })
    assert.equal(result.tracks[0].id, '1')
    assert.equal(result.playlists[0].id, '2')
    assert.equal(result.mixed.length, 2)
  } finally {
    global.fetch = originalFetch
  }
})
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL with `client.searchPlaylists is not a function` or `client.searchMixed is not a function`.

- [ ] **Step 3: Update `qishuiClient.js` imports**

In `src/qishuiClient.js`, replace the existing `errors`, `ids`, and `normalizers` import lines with:

```js
const { ValidationError, UpstreamError } = require('./errors')
const { requireString, optionalInt, optionalPositiveInt, boundedPositiveInt, extractTrackId, extractVideoId, extractPlaylistId, extractResourceIds } = require('./ids')
const { extractPlaylistsFromDiscoverMix, extractRadiosFromDiscover, normalizePlaylistDetail, normalizeFeedItem, normalizeSearchGroups, normalizeProfile, normalizeMixedCollections, normalizeVideo } = require('./normalizers')
```

- [ ] **Step 4: Add PC search helper and methods to `QishuiClient`**

Inside `class QishuiClient`, add this method before `async search(query = {}, context = {})`:

```js
  pcSearchParams(query = {}, keyword) {
    const cursor = optionalInt(query.cursor || query.offset, 0, 'cursor')
    return {
      aid: '386088',
      app_name: 'luna_pc',
      region: 'cn',
      geo_region: 'cn',
      os_region: 'cn',
      sim_region: '',
      device_id: query.device_id || config.deviceId,
      cdid: '',
      iid: query.iid || config.installId,
      version_name: '3.0.0',
      version_code: '30000000',
      channel: 'official',
      build_mode: 'master',
      network_carrier: '',
      ac: 'wifi',
      tz_name: 'Asia/Shanghai',
      resolution: '',
      device_platform: 'windows',
      device_type: 'Windows',
      os_version: 'Windows 11',
      fp: query.fp || config.fp || query.device_id || config.deviceId,
      q: keyword,
      cursor,
      search_id: query.search_id || '',
      search_method: query.search_method || 'input',
      debug_params: '',
      from_search_id: query.from_search_id || '',
      search_scene: query.search_scene || '',
    }
  }
```

Then add these methods immediately after the existing `search` method:

```js
  async searchMixed(query = {}, context = {}) {
    const keyword = requireString(query.keywords || query.keyword || query.q, 'keywords')
    const response = await this.getPc('/luna/pc/search/mixed', this.pcSearchParams(query, keyword), context)
    const body = response.body || {}
    return {
      ...normalizeSearchGroups(body),
      source_strategy: 'pc_search_mixed',
      upstream: body,
    }
  }

  async searchPlaylists(query = {}, context = {}) {
    const keyword = requireString(query.keywords || query.keyword || query.q, 'keywords')
    try {
      const response = await this.getPc('/luna/pc/search/playlist', this.pcSearchParams(query, keyword), context)
      const body = response.body || {}
      const normalized = normalizeSearchGroups(body)
      return {
        playlists: normalized.playlists,
        source_strategy: 'pc_search_playlist',
        upstream: body,
      }
    } catch (error) {
      if (!(error instanceof UpstreamError)) throw error
      const mixed = await this.searchMixed(query, context)
      return {
        playlists: mixed.playlists,
        source_strategy: 'pc_search_mixed_playlist_extract',
        upstream: mixed.upstream,
      }
    }
  }
```

- [ ] **Step 5: Refactor existing `search` to reuse `pcSearchParams`**

Replace the body of `async search(query = {}, context = {})` with:

```js
    const keyword = requireString(query.keywords || query.keyword || query.q, 'keywords')
    const response = await this.getPc('/luna/pc/search/track', this.pcSearchParams(query, keyword), context)
    return response.body
```

- [ ] **Step 6: Create route modules**

Create `module/search_playlist.js`:

```js
module.exports = async (query, { client, cookie }) => client.searchPlaylists(query, { cookie })
```

Create `module/search_mixed.js`:

```js
module.exports = async (query, { client, cookie }) => client.searchMixed(query, { cookie })
```

- [ ] **Step 7: Run tests and verify search APIs pass**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: PASS for search helper tests and existing tests.

- [ ] **Step 8: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 3: Passport auth, account profile, playlists, and collections

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/qishuiClient.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/auth_qrcode.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/auth_qrcode_status.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/auth_me.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/me_playlists.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/me_collection_mixed.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write failing auth tests**

Append this block to `test/smoke.test.js`:

```js
test('authQrcodeStatus extracts sessionid from Set-Cookie', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url).includes('/passport/web/check_qrconnect/'), true)
    assert.equal(options.method, 'POST')
    assert.equal(String(options.body).includes('token=qr-token'), true)
    return new Response(JSON.stringify({ message: 'success', data: { status: 'confirmed' } }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
        'Set-Cookie': 'sessionid=abc123; Path=/; HttpOnly',
      },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.authQrcodeStatus({ token: 'qr-token' })
    assert.equal(result.auth.sessionid, 'abc123')
    assert.equal(result.data.status, 'confirmed')
  } finally {
    global.fetch = originalFetch
  }
})

test('account APIs send sessionid as Cookie without echoing request cookie', async () => {
  const originalFetch = global.fetch
  global.fetch = async (_url, options) => {
    assert.equal(options.headers.Cookie, 'sessionid=abc123;')
    return new Response(JSON.stringify({ my_info: { id: 'u1', nickname: '用户', is_vip: true } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.authMe({ sessionid: 'abc123' })
    assert.equal(result.profile.id, 'u1')
    assert.equal(JSON.stringify(result).includes('sessionid=abc123'), false)
  } finally {
    global.fetch = originalFetch
  }
})
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL with `client.authQrcodeStatus is not a function` or `client.authMe is not a function`.

- [ ] **Step 3: Add Passport constants and auth helpers**

Near the top of `src/qishuiClient.js`, after `PLAYBACK_SENSITIVE_NAME_RE`, add:

```js
const PASSPORT_FIXED = {
  aid: '386088',
  passport_jssdk_version: '2.4.13',
  passport_jssdk_type: 'normal',
  is_from_ttaccountsdk: '1',
  next: 'https://api.qishui.com',
  need_logo: 'false',
  need_short_url: 'false',
  is_frontier: 'true',
  is_new_login: '1',
  iid: '27960026095955',
  version_code: '30020100',
}

function getHeaderValue(headers, name) {
  const normalized = name.toLowerCase()
  for (const [key, value] of Object.entries(headers || {})) {
    if (key.toLowerCase() === normalized) return value
  }
  return ''
}

function extractSessionIdFromSetCookie(headers) {
  const raw = getHeaderValue(headers, 'set-cookie')
  const values = Array.isArray(raw) ? raw : raw ? [raw] : []
  for (const value of values) {
    const match = String(value).match(/sessionid=([^;]+)/)
    if (match) return match[1]
  }
  return ''
}

function sessionCookie(query, context) {
  const sessionid = query.sessionid || query.session_id
  if (sessionid) return `sessionid=${String(sessionid).trim()};`
  return context.cookie || ''
}
```

- [ ] **Step 4: Add auth methods to `QishuiClient`**

Inside `class QishuiClient`, add these methods before `async discover(query = {}, context = {})`:

```js
  getPassport(path, query = {}) {
    return requestJson(`${this.pcApiHost}${path}`, {
      method: 'GET',
      query,
      headers: this.webHeaders(),
      timeoutMs: this.timeoutMs,
    })
  }

  postPassportForm(path, query = {}, body = {}) {
    return requestJson(`${this.pcApiHost}${path}`, {
      method: 'POST',
      query,
      body: new URLSearchParams(body).toString(),
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        ...this.webHeaders(),
      },
      timeoutMs: this.timeoutMs,
    })
  }

  async authQrcode() {
    const response = await this.getPassport('/passport/web/get_qrcode/', {
      passport_jssdk_version: PASSPORT_FIXED.passport_jssdk_version,
      passport_jssdk_type: PASSPORT_FIXED.passport_jssdk_type,
      is_from_ttaccountsdk: PASSPORT_FIXED.is_from_ttaccountsdk,
      aid: PASSPORT_FIXED.aid,
      next: PASSPORT_FIXED.next,
    })
    return response.body
  }

  async authQrcodeStatus(query = {}) {
    const token = requireString(query.token, 'token')
    const response = await this.postPassportForm('/passport/web/check_qrconnect/', {
      passport_jssdk_version: PASSPORT_FIXED.passport_jssdk_version,
      passport_jssdk_type: PASSPORT_FIXED.passport_jssdk_type,
      is_from_ttaccountsdk: PASSPORT_FIXED.is_from_ttaccountsdk,
      aid: PASSPORT_FIXED.aid,
      iid: PASSPORT_FIXED.iid,
    }, {
      need_logo: PASSPORT_FIXED.need_logo,
      need_short_url: PASSPORT_FIXED.need_short_url,
      is_frontier: PASSPORT_FIXED.is_frontier,
      token,
      is_new_login: PASSPORT_FIXED.is_new_login,
      next: PASSPORT_FIXED.next,
    })
    return {
      ...response.body,
      auth: {
        aid: PASSPORT_FIXED.aid,
        sessionid: extractSessionIdFromSetCookie(response.headers),
      },
    }
  }

  async authMe(query = {}, context = {}) {
    const cookie = sessionCookie(query, context)
    if (!cookie) throw new ValidationError('Cookie 或 sessionid 不能为空')
    const response = await this.getPc('/luna/pc/me', { aid: query.aid || PASSPORT_FIXED.aid }, { cookie })
    const body = response.body || {}
    return {
      profile: normalizeProfile(body),
      upstream: body,
    }
  }

  async mePlaylists(query = {}, context = {}) {
    const cookie = sessionCookie(query, context)
    if (!cookie) throw new ValidationError('Cookie 或 sessionid 不能为空')
    const response = await this.getPc('/luna/pc/me/playlist', {
      aid: query.aid || PASSPORT_FIXED.aid,
      iid: query.iid || PASSPORT_FIXED.iid,
      version_code: query.version_code || PASSPORT_FIXED.version_code,
    }, { cookie })
    const body = response.body || {}
    return {
      playlists: Array.isArray(body.playlists) ? body.playlists.map((playlist) => require('./normalizers').normalizePlaylist(playlist)).filter(Boolean) : [],
      total_num: body.total_num || 0,
      upstream: body,
    }
  }

  async meCollectionMixed(query = {}, context = {}) {
    const cookie = sessionCookie(query, context)
    if (!cookie) throw new ValidationError('Cookie 或 sessionid 不能为空')
    const response = await this.getPc('/luna/pc/me/collection/mixed', { aid: query.aid || PASSPORT_FIXED.aid }, { cookie })
    const body = response.body || {}
    return {
      mixed_collections: normalizeMixedCollections(body),
      total_num: body.total_num || 0,
      upstream: body,
    }
  }
```

- [ ] **Step 5: Create auth route modules**

Create `module/auth_qrcode.js`:

```js
async function authQrcode(_query, { client }) {
  return client.authQrcode()
}

authQrcode.methods = ['get']

module.exports = authQrcode
```

Create `module/auth_qrcode_status.js`:

```js
async function authQrcodeStatus(query, { client }) {
  return client.authQrcodeStatus(query)
}

authQrcodeStatus.methods = ['post']
authQrcodeStatus.bodyOnly = true

module.exports = authQrcodeStatus
```

Create `module/auth_me.js`:

```js
async function authMe(query, { client, cookie }) {
  return client.authMe(query, { cookie })
}

authMe.methods = ['post']
authMe.bodyOnly = true

module.exports = authMe
```

Create `module/me_playlists.js`:

```js
async function mePlaylists(query, { client, cookie }) {
  return client.mePlaylists(query, { cookie })
}

mePlaylists.methods = ['post']
mePlaylists.bodyOnly = true

module.exports = mePlaylists
```

Create `module/me_collection_mixed.js`:

```js
async function meCollectionMixed(query, { client, cookie }) {
  return client.meCollectionMixed(query, { cookie })
}

meCollectionMixed.methods = ['post']
meCollectionMixed.bodyOnly = true

module.exports = meCollectionMixed
```

- [ ] **Step 6: Run tests and verify auth APIs pass**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: PASS for auth tests and existing tests.

- [ ] **Step 7: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 4: Video detail and share resolve routes

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/qishuiClient.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/video_detail.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/share_resolve.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write failing media tests**

Append this block to `test/smoke.test.js`:

```js
test('videoDetail posts video_v2 payload with session cookie', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url).includes('/luna/pc/video_v2'), true)
    assert.equal(options.method, 'POST')
    assert.equal(options.headers.Cookie, 'sessionid=abc123;')
    const body = JSON.parse(options.body)
    assert.equal(body.video_id, '7256000000000000000')
    return new Response(JSON.stringify({ video: { video_id: '7256000000000000000', title: '视频标题' } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.videoDetail({ video_id: '7256000000000000000', sessionid: 'abc123' })
    assert.equal(result.video.id, '7256000000000000000')
  } finally {
    global.fetch = originalFetch
  }
})

test('shareResolve identifies track playlist and video ids without fetching arbitrary hosts', async () => {
  const client = new QishuiClient({ timeoutMs: 1000 })
  assert.deepEqual(client.shareResolve({ url: 'https://music.douyin.com/qishui/share/track?track_id=111111' }).ids.track_id, '111111')
  assert.deepEqual(client.shareResolve({ url: 'https://music.douyin.com/qishui/share/playlist?playlist_id=222222' }).ids.playlist_id, '222222')
  assert.deepEqual(client.shareResolve({ url: 'https://music.douyin.com/qishui/share/video?ugc_video_id=333333' }).ids.video_id, '333333')
})
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL with `client.videoDetail is not a function` or `client.shareResolve is not a function`.

- [ ] **Step 3: Add PC POST helper and media methods**

Inside `class QishuiClient`, add this method after `getPc`:

```js
  postPc(path, payload = {}, context = {}, query = {}) {
    return requestJson(`${this.pcApiHost}${path}`, {
      method: 'POST',
      query,
      body: payload,
      headers: this.pcHeaders(context.cookie),
      timeoutMs: this.timeoutMs,
    })
  }
```

Then add these methods after `trackDetail`:

```js
  async videoDetail(query = {}, context = {}) {
    const videoId = extractVideoId(query.video_id || query.id || query.url)
    if (!videoId) throw new ValidationError('video_id 不能为空')
    const cookie = sessionCookie(query, context)
    if (!cookie) throw new ValidationError('Cookie 或 sessionid 不能为空')
    const response = await this.postPc('/luna/pc/video_v2', {
      video_id: videoId,
      type: query.type || 'ugc_video',
      scene_name: query.scene_name || 'library',
      queue_type: query.queue_type || 'favorite_track_playlist',
    }, { cookie }, { aid: query.aid || PASSPORT_FIXED.aid })
    const body = response.body || {}
    return {
      video: normalizeVideo(body.video || body.ugc_video || body),
      upstream: body,
    }
  }

  shareResolve(query = {}) {
    const input = requireString(query.url || query.id || query.track_id || query.playlist_id || query.video_id, 'url 或 id')
    const ids = extractResourceIds(input)
    const resourceTypes = []
    if (ids.track_id) resourceTypes.push('track')
    if (ids.playlist_id) resourceTypes.push('playlist')
    if (ids.video_id) resourceTypes.push('video')
    return {
      input,
      ids,
      resource_types: resourceTypes,
      primary_type: resourceTypes[0] || '',
    }
  }
```

- [ ] **Step 4: Create media route modules**

Create `module/video_detail.js`:

```js
module.exports = async (query, { client, cookie }) => client.videoDetail(query, { cookie })
```

Create `module/share_resolve.js`:

```js
module.exports = async (query, { client }) => client.shareResolve(query)
```

- [ ] **Step 5: Run tests and verify media routes pass**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: PASS for video/share tests and existing tests.

- [ ] **Step 6: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 5: Playlist feed media and listen-video feed routes

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/src/qishuiClient.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/playlist_feed_media.js`
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/feed_listen_video.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write failing feed tests**

Append this block to `test/smoke.test.js`:

```js
test('playlistFeedMedia posts playlist feed payload', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url).includes('/luna/feed/playlist-media'), true)
    const body = JSON.parse(options.body)
    assert.equal(body.playlist_id, '7096700219496368135')
    assert.equal(body.count, 3)
    return new Response(JSON.stringify({ items: [{ id: 'item1', entity: { track: { id: 't1', name: '歌' } } }], has_more: false }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.playlistFeedMedia({ playlist_id: '7096700219496368135', count: 3 })
    assert.equal(result.items[0].track.id, 't1')
  } finally {
    global.fetch = originalFetch
  }
})

test('feedListenVideo posts listen video payload', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url).includes('/luna/feed/listen-video'), true)
    const body = JSON.parse(options.body)
    assert.equal(body.count, 2)
    return new Response(JSON.stringify({ items: [{ id: 'v1', entity: { video: { id: 'v1', title: '视频' } } }] }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.feedListenVideo({ count: 2 })
    assert.equal(result.items[0].id, 'v1')
  } finally {
    global.fetch = originalFetch
  }
})
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL with `client.playlistFeedMedia is not a function` or `client.feedListenVideo is not a function`.

- [ ] **Step 3: Add feed methods to `QishuiClient`**

Add these methods after `playlistRelatedMedia`:

```js
  async playlistFeedMedia(query = {}, context = {}) {
    const playlistId = extractPlaylistId(query.id || query.playlist_id || query.url)
    if (!playlistId) throw new ValidationError('playlist_id 不能为空')
    const payload = query.body && typeof query.body === 'object'
      ? query.body
      : {
          playlist_id: playlistId,
          playlist_type: Number(query.playlist_type ?? 0) || 0,
          count: boundedPositiveInt(query.count, 10, config.maxPageSize, 'count'),
          cursor: query.cursor ? String(query.cursor) : '',
          feed_session_id: query.feed_session_id || '',
          server_session_id: query.server_session_id || '',
          played_media: Array.isArray(query.played_media) ? query.played_media : [],
          feed_extra: query.feed_extra && typeof query.feed_extra === 'object' ? query.feed_extra : {},
        }
    const response = await this.postLuna('/luna/feed/playlist-media', payload, context)
    const body = response.body || {}
    return {
      items: Array.isArray(body.items) ? body.items.map(normalizeFeedItem).filter(Boolean) : [],
      has_more: Boolean(body.has_more),
      feed_session_id: body.feed_session_id || '',
      server_session_id: body.server_session_id || '',
      upstream: body,
    }
  }

  async feedListenVideo(query = {}, context = {}) {
    const payload = query.body && typeof query.body === 'object'
      ? query.body
      : {
          count: boundedPositiveInt(query.count, 10, config.maxPageSize, 'count'),
          cursor: query.cursor ? String(query.cursor) : '',
          played_media: Array.isArray(query.played_media) ? query.played_media : [],
          feed_extra: query.feed_extra && typeof query.feed_extra === 'object' ? query.feed_extra : {},
        }
    const response = await this.postLuna('/luna/feed/listen-video', payload, context)
    const body = response.body || {}
    return {
      items: Array.isArray(body.items) ? body.items.map(normalizeFeedItem).filter(Boolean) : [],
      has_more: Boolean(body.has_more),
      upstream: body,
    }
  }
```

- [ ] **Step 4: Create feed route modules**

Create `module/playlist_feed_media.js`:

```js
module.exports = async (query, { client, cookie }) => client.playlistFeedMedia(query, { cookie })
```

Create `module/feed_listen_video.js`:

```js
module.exports = async (query, { client, cookie }) => client.feedListenVideo(query, { cookie })
```

- [ ] **Step 5: Run tests and verify feed APIs pass**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: PASS for feed tests and existing tests.

- [ ] **Step 6: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 6: Capabilities route and route mounting verification

**Files:**
- Create: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/module/api_capabilities.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write failing capabilities route test**

Append this block to `test/smoke.test.js`:

```js
test('api capabilities route returns all capability rows without secrets', async () => {
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  try {
    const { port } = server.address()
    const base = `http://127.0.0.1:${port}`
    const response = await fetch(`${base}/api/capabilities`).then((item) => item.json())
    assert.equal(response.code, 0)
    assert.equal(response.data.capabilities.some((item) => item.route === '/search/playlist'), true)
    assert.equal(response.data.capabilities.some((item) => item.route === '/auth/qrcode'), true)
    assert.equal(JSON.stringify(response).includes('sessionid='), false)
  } finally {
    server.close()
  }
})
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL because `/api/capabilities` does not exist.

- [ ] **Step 3: Create capabilities route module**

Create `module/api_capabilities.js`:

```js
const { capabilities } = require('../src/capabilities')

async function apiCapabilities() {
  return {
    count: capabilities.length,
    capabilities,
  }
}

apiCapabilities.methods = ['get']
apiCapabilities.route = '/api/capabilities'

module.exports = apiCapabilities
```

- [ ] **Step 4: Run tests and verify capabilities pass**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: PASS for capabilities route and existing tests.

- [ ] **Step 5: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 7: README and OpenAPI contract updates

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/README.md`
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/openapi.json`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/smoke.test.js`

- [ ] **Step 1: Write failing docs consistency test**

Append this block to `test/smoke.test.js`:

```js
test('README and OpenAPI document new full API routes', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const root = path.join(__dirname, '..')
  const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8')
  const openapi = JSON.parse(fs.readFileSync(path.join(root, 'openapi.json'), 'utf8'))
  const requiredRoutes = ['/search/playlist', '/search/mixed', '/auth/qrcode', '/auth/qrcode/status', '/auth/me', '/me/playlists', '/me/collection/mixed', '/video/detail', '/playlist/feed/media', '/feed/listen/video', '/share/resolve', '/api/capabilities']
  for (const route of requiredRoutes) {
    assert.equal(readme.includes(route), true, `${route} missing from README`)
    assert.equal(Boolean(openapi.paths[route]), true, `${route} missing from OpenAPI`)
  }
})
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: FAIL listing routes missing from README or OpenAPI.

- [ ] **Step 3: Update README capability matrix**

In `README.md`, replace the existing `## 功能状态` table with this table:

```md
## 功能状态

| 能力 | 路由 | 状态 |
|---|---|---|
| 发现页 | `/discover` | 已验证可返回发现页块 |
| 发现页混合流 | `/discover/mix` | 已验证可返回推荐歌单块 |
| 推荐歌单 | `/recommend/playlist` | 已验证可提取推荐歌单 |
| 歌单详情 | `/playlist/detail` | 支持歌单与歌曲资源 |
| 歌单相关推荐 | `/playlist/related/media` | 通常需要登录态或 App 上下文 |
| 歌单 Feed 媒体流 | `/playlist/feed/media` | App 逆向确认端点，可能需要 App 上下文 |
| 电台列表 | `/radio/list` | 可从发现页提取电台 |
| 电台歌曲 | `/radio/tracks` | 可返回推荐歌曲 Feed |
| Feed 模式 | `/feed/mode` | 端点存在 |
| Feed 引导 | `/feed/mode/guidance` | 端点存在 |
| DailyMix | `/daily/mix` | 端点存在，裸调可能被上游拒绝 |
| 听视频 Feed | `/feed/listen/video` | App 逆向确认端点，可能需要 App 上下文 |
| 搜索歌曲 | `/search` | 使用 PC 搜索歌曲接口 |
| 搜索歌单 | `/search/playlist` | 优先 PC 歌单搜索，失败时从混合搜索提取 |
| 混合搜索 | `/search/mixed` | 使用 PC 混合搜索接口 |
| 曲目详情 | `/track/detail` | 使用 PC 曲目接口 |
| 视频详情 | `/video/detail` | 使用 PC 视频接口，需要登录态 |
| App 播放信息 | `/media/player` | 返回脱敏播放摘要 |
| 分享页歌曲解析 | `/song/detail` | 支持 `track_id` 或分享链接 |
| 分享资源识别 | `/share/resolve` | 识别 track、playlist、video ID |
| 歌词 | `/lyric` | 基于分享页或 H5 SEO 结果提取 |
| H5 SEO 曲目 | `/h5/seo/track` | 基于 `track_id` 调用 H5 SEO 接口 |
| 音频信息 | `/audio/info` | 从曲目或分享页提取音频信息 |
| 下载地址 | `/download/url` | 解析曲目对应音频地址 |
| 下载文件 | `/download/file` | 受控下载允许域名内音频资源 |
| spade 解密 | `/decrypt/spade` | 解出 `spade_a` 对应 AES key |
| 本地音频解密 | `/audio/decrypt` | 对授权音频做 AES-CTR 本地解密 |
| 登录二维码 | `/auth/qrcode` | 获取 PC Passport 扫码二维码 |
| 登录状态 | `/auth/qrcode/status` | 轮询扫码状态并返回本次获得的 `sessionid` |
| 账号信息 | `/auth/me` | 需要 Cookie 或 body `sessionid` |
| 我的歌单 | `/me/playlists` | 需要 Cookie 或 body `sessionid` |
| 我的收藏 | `/me/collection/mixed` | 需要 Cookie 或 body `sessionid` |
| 能力矩阵 | `/api/capabilities` | 输出本服务路由能力清单 |
```

- [ ] **Step 4: Add README login and raw-output notes**

In `README.md`, replace `## 登录态与敏感信息` with:

```md
## 登录态与敏感信息

- 项目不会内置 Cookie、`sessionid`、`X-Helios`、`X-Medusa`、`PlayAuth`、`spade_a` 或其他真实敏感值。
- `/auth/qrcode` 与 `/auth/qrcode/status` 只完成当前扫码流程；服务端不持久化账号状态。
- `/auth/qrcode/status` 可返回本次扫码得到的 `sessionid`，调用方需自行安全保存。
- 账号接口支持请求头 `Cookie` 或 POST JSON body 的 `sessionid`，服务端只转发给上游，不在其他响应中回显入站凭据。
- `include_raw=true` 只返回上游业务响应体，不返回请求 headers、Cookie 或入站 session。
- `/media/player` 默认对播放敏感字段做响应脱敏。
- 下载与解密接口仅用于处理调用方已合法授权的内容。
- 下载 URL 必须是 HTTPS，域名必须命中 `QISHUI_DOWNLOAD_ALLOWED_HOSTS`，大小受 `QISHUI_DOWNLOAD_MAX_BYTES` 限制。
- 如需 PC 接口签名 header，可通过 `.env` 配置 `QISHUI_X_HELIOS`、`QISHUI_X_MEDUSA`，不要写入仓库。
- 导出日志或抓包结果前必须脱敏 Cookie、`sessionid`、设备标识、`spade_a`、`PlayAuth`、手机号和用户 ID。
```

- [ ] **Step 5: Update OpenAPI paths**

In `openapi.json`, add path objects for these routes under `paths`:

```json
{
  "/search/playlist": { "get": { "summary": "搜索歌单", "parameters": [{ "name": "keywords", "in": "query", "required": true, "schema": { "type": "string" } }], "responses": { "200": { "description": "success" } } }, "post": { "summary": "搜索歌单", "responses": { "200": { "description": "success" } } } },
  "/search/mixed": { "get": { "summary": "混合搜索", "parameters": [{ "name": "keywords", "in": "query", "required": true, "schema": { "type": "string" } }], "responses": { "200": { "description": "success" } } }, "post": { "summary": "混合搜索", "responses": { "200": { "description": "success" } } } },
  "/playlist/feed/media": { "get": { "summary": "歌单 Feed 媒体流", "parameters": [{ "name": "playlist_id", "in": "query", "required": true, "schema": { "type": "string" } }, { "name": "count", "in": "query", "schema": { "type": "integer", "default": 10 } }], "responses": { "200": { "description": "success" } } }, "post": { "summary": "歌单 Feed 媒体流", "responses": { "200": { "description": "success" } } } },
  "/feed/listen/video": { "get": { "summary": "听视频 Feed", "parameters": [{ "name": "count", "in": "query", "schema": { "type": "integer", "default": 10 } }], "responses": { "200": { "description": "success" } } }, "post": { "summary": "听视频 Feed", "responses": { "200": { "description": "success" } } } },
  "/video/detail": { "get": { "summary": "视频详情", "parameters": [{ "name": "video_id", "in": "query", "required": true, "schema": { "type": "string" } }], "responses": { "200": { "description": "success" } } }, "post": { "summary": "视频详情", "responses": { "200": { "description": "success" } } } },
  "/auth/qrcode": { "get": { "summary": "获取登录二维码", "responses": { "200": { "description": "success" } } } },
  "/auth/qrcode/status": { "post": { "summary": "轮询扫码登录状态", "responses": { "200": { "description": "success" } } } },
  "/auth/me": { "post": { "summary": "账号信息", "responses": { "200": { "description": "success" } } } },
  "/me/playlists": { "post": { "summary": "我的歌单", "responses": { "200": { "description": "success" } } } },
  "/me/collection/mixed": { "post": { "summary": "我的收藏混合资源", "responses": { "200": { "description": "success" } } } },
  "/share/resolve": { "get": { "summary": "识别分享资源 ID", "parameters": [{ "name": "url", "in": "query", "required": true, "schema": { "type": "string" } }], "responses": { "200": { "description": "success" } } }, "post": { "summary": "识别分享资源 ID", "responses": { "200": { "description": "success" } } } },
  "/api/capabilities": { "get": { "summary": "能力矩阵", "responses": { "200": { "description": "success" } } } }
}
```

Apply these as actual path entries, preserving existing paths and valid JSON syntax.

- [ ] **Step 6: Run JSON parse and test suite**

Run:

```bash
node -e "JSON.parse(require('node:fs').readFileSync('D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/openapi.json','utf8')); console.log('openapi ok')" && npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: `openapi ok`, then PASS for docs consistency and all existing tests.

- [ ] **Step 7: Record checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

---

### Task 8: Live smoke coverage and final validation

**Files:**
- Modify: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/live-smoke.js`
- Test: `D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/test/live-smoke.js`

- [ ] **Step 1: Replace `test/live-smoke.js` with classified live smoke**

Replace `test/live-smoke.js` with:

```js
const { QishuiClient } = require('../src/qishuiClient')

async function record(name, action, options = {}) {
  try {
    const data = await action()
    return { name, ok: true, summary: options.summarize ? options.summarize(data) : 'ok' }
  } catch (error) {
    return {
      name,
      ok: false,
      requires_app_context: Boolean(options.requiresAppContext),
      message: error.message,
      details: error.details || null,
    }
  }
}

async function main() {
  const client = new QishuiClient()
  const results = []

  results.push(await record('recommendPlaylists', () => client.recommendPlaylists({ count: 5 }), {
    summarize: (data) => ({ playlists: data.playlists.length, first: data.playlists[0]?.title || '' }),
  }))

  const playlistsResult = results.find((item) => item.name === 'recommendPlaylists')
  let playlistId = ''
  if (playlistsResult?.ok) {
    const playlists = await client.recommendPlaylists({ count: 1 })
    playlistId = playlists.playlists[0]?.id || ''
  }

  results.push(await record('searchPlaylists', () => client.searchPlaylists({ keywords: '周杰伦' }), {
    summarize: (data) => ({ playlists: data.playlists.length, source_strategy: data.source_strategy }),
  }))

  results.push(await record('searchMixed', () => client.searchMixed({ keywords: '周杰伦' }), {
    summarize: (data) => ({ tracks: data.tracks.length, playlists: data.playlists.length, videos: data.videos.length }),
  }))

  if (playlistId) {
    results.push(await record('playlistDetail', () => client.playlistDetail({ playlist_id: playlistId, count: 5 }), {
      summarize: (data) => ({ playlist: data.playlist?.title || '', media_resources: data.media_resources.length }),
    }))
    results.push(await record('playlistFeedMedia', () => client.playlistFeedMedia({ playlist_id: playlistId, count: 5 }), {
      requiresAppContext: true,
      summarize: (data) => ({ items: data.items.length, has_more: data.has_more }),
    }))
  }

  results.push(await record('dailyMix', () => client.dailyMix({ count: 5 }), {
    requiresAppContext: true,
    summarize: (data) => ({ supported_without_app_context: data.supported_without_app_context }),
  }))

  results.push(await record('authQrcode', () => client.authQrcode(), {
    summarize: (data) => ({ has_data: Boolean(data?.data || data?.token || data?.qrcode) }),
  }))

  console.log(JSON.stringify({ results }, null, 2))

  const hardFailures = results.filter((item) => !item.ok && !item.requires_app_context)
  if (hardFailures.length) process.exit(1)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
```

- [ ] **Step 2: Run local unit tests**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" test
```

Expected: all unit tests pass.

- [ ] **Step 3: Run live smoke because user allowed upstream access**

Run:

```bash
npm --prefix "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" run smoke:live
```

Expected: JSON output with `results`. App-context routes may have `ok: false` with `requires_app_context: true`. Non-App-context core routes should pass or show actionable upstream details.

- [ ] **Step 4: Verify route list manually through the server**

Run:

```bash
node -e "const { constructServer } = require('D:/Android/AndroidStudioProjects/ai/qishui/qishui-api/server'); (async()=>{const app=await constructServer(); const server=app.listen(0,'127.0.0.1', async()=>{const port=server.address().port; const list=await fetch('http://127.0.0.1:'+port+'/api/list').then(r=>r.json()); const caps=await fetch('http://127.0.0.1:'+port+'/api/capabilities').then(r=>r.json()); console.log(JSON.stringify({routes:list.data.map(x=>x.route).sort(), capability_count:caps.data.count}, null, 2)); server.close();});})().catch((error)=>{console.error(error); process.exit(1)})"
```

Expected: output includes `/search/playlist`, `/auth/qrcode`, `/video/detail`, `/playlist/feed/media`, `/feed/listen/video`, `/share/resolve`, and `/api/capabilities`.

- [ ] **Step 5: Record final checkpoint**

Run:

```bash
git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" rev-parse --is-inside-work-tree >/dev/null 2>&1 && git -C "D:/Android/AndroidStudioProjects/ai/qishui/qishui-api" status --short || printf 'not a git repository; checkpoint only\n'
```

Expected: `not a git repository; checkpoint only` in the current environment.

## Self-Review

### Spec coverage

- Search routes are covered by Task 2.
- Playlist detail, related media, and feed media are covered by existing code plus Task 5.
- Discovery, DailyMix, radio, and listen-video are covered by existing code plus Task 5 and Task 8.
- Track detail, video detail, H5 SEO track, and media player are covered by existing code plus Task 4.
- Account and personal resource APIs are covered by Task 3.
- Share resolve, lyric, and song detail are covered by existing code plus Task 4.
- Audio, download, and decrypt protections are preserved by existing tests and Task 7 docs.
- Capabilities are covered by Task 1 and Task 6.
- README, OpenAPI, unit tests, and live smoke are covered by Task 7 and Task 8.

### Placeholder scan

This plan avoids deferred markers and includes concrete paths, code snippets, commands, and expected results.

### Type and naming consistency

- Route modules use existing underscore-to-slash convention.
- New `QishuiClient` method names match route modules.
- New normalizer exports match test imports and client imports.
- `sessionid` is accepted in body and converted to a Cookie header without echoing the inbound Cookie value.
