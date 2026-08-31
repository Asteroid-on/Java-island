const test = require('node:test')
const assert = require('node:assert/strict')
const { extractTrackId, extractPlaylistId } = require('../src/ids')
const { sentencesToLrc } = require('../src/lyrics')
const { normalizeFeedItem } = require('../src/normalizers')
const { decryptSpadeA, MP4Box, resolveAudioKey, scanForFlacMetadata } = require('../src/audioDecryptor')
const { assertAllowedDownloadUrl, downloadBinary } = require('../src/download')
const { QishuiClient } = require('../src/qishuiClient')
const config = require('../src/config')
const { constructServer } = require('../server')

test('extractTrackId parses common qishui links', () => {
  assert.equal(extractTrackId('https://music.douyin.com/qishui/share/track?track_id=7079108541549643812'), '7079108541549643812')
  assert.equal(extractTrackId('https://example.com/track/123456'), '123456')
})

test('extractPlaylistId parses playlist query and path', () => {
  assert.equal(extractPlaylistId('https://music.douyin.com/qishui/share/playlist?playlist_id=7096700219496368135'), '7096700219496368135')
  assert.equal(extractPlaylistId('/playlist/7096700219496368135'), '7096700219496368135')
})

test('extractVideoId parses video links and ugc ids separately', () => {
  const { extractVideoId } = require('../src/ids')
  assert.equal(extractVideoId('https://music.douyin.com/qishui/share/video?ugc_video_id=7256000000000000000'), '7256000000000000000')
  assert.equal(extractVideoId('/video/7256000000000000000'), '7256000000000000000')
})

test('extractResourceIds keeps explicit resource links separate', () => {
  const { extractResourceIds } = require('../src/ids')
  assert.deepEqual(extractResourceIds('https://music.douyin.com/qishui/share/playlist?playlist_id=7096700219496368135'), {
    track_id: '',
    video_id: '',
    playlist_id: '7096700219496368135',
  })
  assert.deepEqual(extractResourceIds('https://music.douyin.com/qishui/share/video?ugc_video_id=7256000000000000000'), {
    track_id: '',
    video_id: '7256000000000000000',
    playlist_id: '',
  })
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

test('normalizeSearchGroups preserves ugc video ids', () => {
  const { normalizeSearchGroups } = require('../src/normalizers')
  const normalized = normalizeSearchGroups({
    result_groups: [{
      data: [{ entity: { ugc_video: { ugc_video_id: '7256000000000000000', title: '视频' } } }],
    }],
  })
  assert.equal(normalized.videos[0].id, '7256000000000000000')
})

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

test('comment calls luna comments endpoint and normalizes comments', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    const requestUrl = new URL(String(url))
    assert.equal(options.method, 'GET')
    assert.equal(requestUrl.pathname, '/luna/comments')
    assert.equal(requestUrl.searchParams.get('group_id'), '7079108541549643812')
    assert.equal(requestUrl.searchParams.get('count'), '2')
    return new Response(JSON.stringify({
      comments: [{
        comment_id: 'comment-1',
        group_id: '7079108541549643812',
        text: '好听',
        like_count: 12,
        reply_count: 3,
        create_time: 1710000000,
        is_liked: true,
        user_info: {
          user_id: 'user-1',
          nickname: '测试用户',
          avatar_thumb: { url_list: ['https://example.invalid/avatar.jpg'] },
        },
      }],
      total: 9,
      cursor: 'next-cursor',
      has_more: true,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.comment({ group_id: '7079108541549643812', count: 2 })
    assert.equal(result.comments[0].id, 'comment-1')
    assert.equal(result.comments[0].content, '好听')
    assert.equal(result.comments[0].user_name, '测试用户')
    assert.equal(result.comments[0].like_count, 12)
    assert.equal(result.total, 9)
    assert.equal(result.has_more, true)
  } finally {
    global.fetch = originalFetch
  }
})

test('comment route rejects missing group id', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    throw new Error('fetch should not be called')
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/comment`).then((item) => item.json())
    assert.notEqual(response.code, 0)
    assert.match(response.message, /group_id|track_id/)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('comment route redacts sensitive comment response fields', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    assert.equal(String(url).includes('/luna/comments'), true)
    assert.equal(options.headers.Cookie, 'sessionid=abc123;')
    return new Response(JSON.stringify({
      comments: [{
        comment_id: 'comment-1',
        text: '好听',
        user_info: {
          user_id: 'user-1',
          nickname: '测试用户',
          avatar_thumb: { url_list: ['https://example.invalid/avatar.jpg?X-Amz-Signature=signed-secret&Expires=123'] },
        },
        extra_text: 'url=https://music.douyin.com/audio.m4a?X-Amz-Signature=signed-secret&Expires=123 PlayAuth=play-secret spade_a=spade-secret sessionid=abc123',
        PlayAuth: 'play-secret',
        spade_a: 'spade-secret',
        sessionid: 'abc123',
      }],
      echo_text: 'https://music.douyin.com/audio.m4a?X-Amz-Signature=signed-secret&Expires=123 PlayAuth=play-secret spade_a=spade-secret sessionid=abc123',
      PlayAuth: 'play-secret',
      spade_a: 'spade-secret',
      sessionid: 'abc123',
      has_more: false,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/comment?group_id=7079108541549643812`, {
      headers: { Cookie: 'sessionid=abc123;' },
    }).then((item) => item.json())
    assert.equal(response.code, 0)
    assert.equal(response.data.comments[0].user_avatar, 'https://example.invalid/avatar.jpg?<redacted>')
    const text = JSON.stringify(response)
    assert.equal(text.includes('signed-secret'), false)
    assert.equal(text.includes('play-secret'), false)
    assert.equal(text.includes('spade-secret'), false)
    assert.equal(text.includes('abc123'), false)
    assert.equal(text.includes('sessionid=abc123'), false)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('comment route rejects zero count before upstream request', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  let upstreamCalled = false
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    upstreamCalled = true
    return new Response(JSON.stringify({ comments: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/comment`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ group_id: '7079108541549643812', count: 0 }),
    }).then((item) => item.json())
    assert.notEqual(response.code, 0)
    assert.match(response.message, /count/)
    assert.equal(upstreamCalled, false)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('comment route caps count above maximum', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    const requestUrl = new URL(String(url))
    assert.equal(requestUrl.searchParams.get('count'), String(config.maxPageSize))
    return new Response(JSON.stringify({ comments: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/comment`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ group_id: '7079108541549643812', count: 999999 }),
    }).then((item) => item.json())
    assert.equal(response.code, 0)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

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
    assert.equal(result.items[0].video.id, 'v1')
  } finally {
    global.fetch = originalFetch
  }
})

test('playlistFeedMedia route builds payload from query when body is empty', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  let upstreamPayload = null
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    upstreamPayload = JSON.parse(options.body)
    return new Response(JSON.stringify({ items: [], has_more: false }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/playlist/feed/media?playlist_id=7096700219496368135&count=3`).then((item) => item.json())
    assert.equal(response.code, 0)
    assert.equal(upstreamPayload.playlist_id, '7096700219496368135')
    assert.equal(upstreamPayload.count, 3)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('playlistFeedMedia caps POST body count', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  let upstreamPayload = null
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    upstreamPayload = JSON.parse(options.body)
    return new Response(JSON.stringify({ items: [], has_more: false }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/playlist/feed/media`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ playlist_id: '7096700219496368135', count: 999999 }),
    }).then((item) => item.json())
    assert.equal(response.code, 0)
    assert.equal(upstreamPayload.playlist_id, '7096700219496368135')
    assert.equal(upstreamPayload.count, config.maxPageSize)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('feedListenVideo caps POST body count', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  let upstreamPayload = null
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    upstreamPayload = JSON.parse(options.body)
    return new Response(JSON.stringify({ items: [], has_more: false }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/feed/listen/video`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ count: 999999 }),
    }).then((item) => item.json())
    assert.equal(response.code, 0)
    assert.equal(upstreamPayload.count, config.maxPageSize)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('playlistFeedMedia redacts inbound credentials from upstream and item raw', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => new Response(JSON.stringify({
    items: [{
      id: 'item1',
      entity: {
        track: {
          id: 't1',
          name: '歌',
          sessionid: 'abc123',
          Cookie: 'sessionid=abc123;',
        },
      },
      request_headers: { cookie: 'sessionid=abc123;' },
      plain_echo: 'abc123',
    }],
    sessionid: 'abc123',
    Cookie: 'sessionid=abc123;',
    request_headers: { cookie: 'sessionid=abc123;' },
    plain_echo: 'abc123',
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.playlistFeedMedia({ playlist_id: '7096700219496368135', sessionid: 'abc123' }, { cookie: 'sessionid=abc123;' })
    const text = JSON.stringify(result)
    assert.equal(text.includes('abc123'), false)
    assert.equal(text.includes('sessionid=abc123'), false)
  } finally {
    global.fetch = originalFetch
  }
})

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

test('videoDetail redacts inbound credentials from normalized video and upstream', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => new Response(JSON.stringify({
    video: {
      video_id: '7256000000000000000',
      title: '视频标题',
      sessionid: 'abc123',
      Cookie: 'sessionid=abc123;',
      request_headers: { cookie: 'sessionid=abc123;' },
      plain_echo: 'abc123',
    },
    sessionid: 'abc123',
    Cookie: 'sessionid=abc123;',
    request_headers: { cookie: 'sessionid=abc123;' },
    plain_echo: 'abc123',
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.videoDetail({ video_id: '7256000000000000000', sessionid: 'abc123' })
    const text = JSON.stringify(result)
    assert.equal(text.includes('abc123'), false)
    assert.equal(text.includes('sessionid=abc123'), false)
  } finally {
    global.fetch = originalFetch
  }
})

test('videoDetail route rejects GET and query credentials', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    throw new Error('fetch should not be called')
  }
  try {
    const { port } = server.address()
    const base = `http://127.0.0.1:${port}`
    const postQueryResponse = await fetch(`${base}/video/detail?sessionid=abc123&video_id=7256000000000000000`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    }).then((response) => response.json())
    assert.equal(postQueryResponse.code, 40000)
    assert.match(postQueryResponse.message, /敏感参数必须放在 POST JSON body 中/)

    const getResponse = await fetch(`${base}/video/detail`).then((response) => response.json())
    assert.equal(getResponse.code, 40400)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('shareResolve identifies track playlist and video ids without fetching arbitrary hosts', async () => {
  const client = new QishuiClient({ timeoutMs: 1000 })
  assert.deepEqual(client.shareResolve({ url: 'https://music.douyin.com/qishui/share/track?track_id=111111' }).ids.track_id, '111111')
  assert.deepEqual(client.shareResolve({ url: 'https://music.douyin.com/qishui/share/playlist?playlist_id=222222' }).ids.playlist_id, '222222')
  assert.deepEqual(client.shareResolve({ url: 'https://music.douyin.com/qishui/share/video?ugc_video_id=333333' }).ids.video_id, '333333')
})

test('shareResolve marks bare numeric id as ambiguous', async () => {
  const client = new QishuiClient({ timeoutMs: 1000 })
  const result = client.shareResolve({ id: '123456' })
  assert.notEqual(result.primary_type, 'track')
  assert.equal(result.primary_type, 'ambiguous')
})

test('shareResolve keeps explicit playlist_id as playlist only', async () => {
  const client = new QishuiClient({ timeoutMs: 1000 })
  const result = client.shareResolve({ playlist_id: '222222' })
  assert.deepEqual(result.ids, { track_id: '', video_id: '', playlist_id: '222222' })
  assert.deepEqual(result.resource_types, ['playlist'])
  assert.equal(result.primary_type, 'playlist')
})

test('authQrcode exposes flattened token and qrcode fields', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    const requestUrl = new URL(String(url))
    assert.equal(requestUrl.pathname, '/passport/web/get_qrcode/')
    assert.equal(options.method, 'GET')
    assert.equal(requestUrl.searchParams.get('need_logo'), 'false')
    assert.equal(requestUrl.searchParams.get('need_short_url'), 'false')
    assert.equal(requestUrl.searchParams.get('is_new_login'), '1')
    return new Response(JSON.stringify({
      message: 'success',
      data: {
        token: 'qr-token',
        qrcode: 'data:image/png;base64,abc',
        qrcode_index_url: 'https://bff-pc.qishui.com/ucenter_web/app/sdk-next?token=qr-token',
        expire_time: 1780488525,
        web_name: '汽水音乐-PC端',
        copywriting: '请使用「抖音 APP」扫码验证',
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.authQrcode()
    assert.equal(result.token, 'qr-token')
    assert.equal(result.qrcode, 'data:image/png;base64,abc')
    assert.equal(result.qrcode_index_url.includes('token=qr-token'), true)
    assert.equal(result.expire_time, 1780488525)
    assert.equal(result.scan_app, 'douyin')
    assert.equal(result.data.token, 'qr-token')
  } finally {
    global.fetch = originalFetch
  }
})

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
    assert.equal(result.status, 'confirmed')
    assert.equal(result.data.status, 'confirmed')
  } finally {
    global.fetch = originalFetch
  }
})

test('authQrcodeStatus extracts sessionid from multiple Set-Cookie headers', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url).includes('/passport/web/check_qrconnect/'), true)
    assert.equal(options.method, 'POST')
    return {
      ok: true,
      status: 200,
      headers: {
        getSetCookie() {
          return [
            'csrf_token=csrf123; Path=/; HttpOnly',
            'sessionid=middle123; Path=/; HttpOnly',
            'sid_guard=guard123; Path=/; HttpOnly',
          ]
        },
        entries() {
          return [
            ['content-type', 'application/json'],
            ['set-cookie', 'sid_guard=guard123; Path=/; HttpOnly'],
          ][Symbol.iterator]()
        },
      },
      text: async () => JSON.stringify({ message: 'success', data: { status: 'confirmed' } }),
    }
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.authQrcodeStatus({ token: 'qr-token' })
    assert.equal(result.auth.sessionid, 'middle123')
  } finally {
    global.fetch = originalFetch
  }
})

test('authQrcodeStatus sends qrcode creation cookies for the same token', async () => {
  const originalFetch = global.fetch
  const calls = []

  global.fetch = async (url, options) => {
    const requestUrl = new URL(String(url))
    calls.push({ requestUrl, options })

    if (requestUrl.pathname === '/passport/web/get_qrcode/') {
      return {
        ok: true,
        status: 200,
        headers: {
          getSetCookie() {
            return [
              'passport_csrf_token=csrf-value; Path=/; Domain=.qishui.com; HttpOnly',
              'passport_csrf_token_default=default-value; Path=/; Domain=.qishui.com; HttpOnly',
            ]
          },
          entries() {
            return [['content-type', 'application/json']][Symbol.iterator]()
          },
        },
        text: async () => JSON.stringify({
          message: 'success',
          data: {
            token: 'qr-token-cookie',
            qrcode: 'data:image/png;base64,abc',
            qrcode_index_url: 'https://bff-pc.qishui.com/ucenter_web/app/sdk-next?token=qr-token-cookie',
            expire_time: 1780488525,
          },
        }),
      }
    }

    assert.equal(requestUrl.pathname, '/passport/web/check_qrconnect/')
    assert.equal(options.method, 'POST')
    assert.match(options.headers.Cookie || '', /passport_csrf_token=csrf-value/)
    assert.match(options.headers.Cookie || '', /passport_csrf_token_default=default-value/)

    return {
      ok: true,
      status: 200,
      headers: {
        getSetCookie() {
          return []
        },
        entries() {
          return [['content-type', 'application/json']][Symbol.iterator]()
        },
      },
      text: async () => JSON.stringify({ message: 'success', data: { status: 'new' } }),
    }
  }

  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    await client.authQrcode()
    const result = await client.authQrcodeStatus({ token: 'qr-token-cookie' })

    assert.equal(result.status, 'new')
    assert.equal(calls.length, 2)
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

test('account APIs redact upstream credential echoes', async () => {
  const originalFetch = global.fetch
  global.fetch = async (_url, options) => {
    assert.equal(options.headers.Cookie, 'sessionid=abc123;')
    return new Response(JSON.stringify({
      my_info: { id: 'u1', nickname: '用户', is_vip: true },
      playlists: [{ id: 'p1', title: '歌单' }],
      mixed_collections: [{ item_type: 'playlist', playlist: { id: 'p2', title: '收藏歌单' } }],
      total_num: 1,
      sessionid: 'abc123',
      Cookie: 'sessionid=abc123;',
      request_headers: { cookie: 'sessionid=abc123;' },
      nested: {
        session_id: 'abc123',
        exact_value: 'abc123',
        mixed_value: 'prefix sessionid=abc123; suffix',
      },
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const results = [
      await client.authMe({ sessionid: 'abc123' }),
      await client.mePlaylists({ sessionid: 'abc123' }),
      await client.meCollectionMixed({ sessionid: 'abc123' }),
    ]
    for (const result of results) {
      const text = JSON.stringify(result)
      assert.equal(text.includes('abc123'), false)
      assert.equal(text.includes('sessionid=abc123'), false)
    }
  } finally {
    global.fetch = originalFetch
  }
})

test('account APIs redact every inbound Cookie value from successful responses', async () => {
  const originalFetch = global.fetch
  const inboundCookie = 'sessionid=abc123; sid_guard=guard123; uid_tt=uidsecret; odin_tt=odinsecret'
  global.fetch = async (_url, options) => {
    assert.equal(options.headers.Cookie, inboundCookie)
    return new Response(JSON.stringify({
      my_info: { id: 'u1', nickname: '用户', is_vip: true },
      sid_guard: 'guard123',
      uid_tt: 'uidsecret',
      odin_tt: 'odinsecret',
      plain_guard_echo: 'guard123',
      plain_uid_echo: 'uidsecret',
      plain_odin_echo: 'odinsecret',
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.authMe({}, { cookie: inboundCookie })
    const text = JSON.stringify(result)
    assert.equal(text.includes('abc123'), false)
    assert.equal(text.includes('guard123'), false)
    assert.equal(text.includes('uidsecret'), false)
    assert.equal(text.includes('odinsecret'), false)
  } finally {
    global.fetch = originalFetch
  }
})

test('account APIs keep normal fields containing short Cookie values', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => new Response(JSON.stringify({
    my_info: { id: 'u1', nickname: '用户1', is_vip: true },
    harmless_echo: '1',
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.authMe({}, { cookie: 'sessionid=abc123; short=1' })
    assert.equal(result.profile.id, 'u1')
    assert.equal(result.profile.nickname, '用户1')
    assert.equal(result.upstream.harmless_echo, '1')
  } finally {
    global.fetch = originalFetch
  }
})

test('account APIs reject invalid or missing sessionid before request', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => {
    throw new Error('fetch should not be called')
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    await assert.rejects(() => client.authMe({ sessionid: '   ' }), /Cookie 或 sessionid 不能为空/)
    await assert.rejects(() => client.authMe({ sessionid: 'abc; foo=bar' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({ sessionid: 'abc\n123' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({ sessionid: 'abc 123' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({ sessionid: ' abc123' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({ sessionid: 'abc123 ' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({ sessionid: '\nabc123' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({ sessionid: 'abc123\t' }), /sessionid 无效/)
    await assert.rejects(() => client.authMe({}), /Cookie 或 sessionid 不能为空/)
    await assert.rejects(() => client.authMe({}, { cookie: '   ' }), /Cookie 或 sessionid 不能为空/)
  } finally {
    global.fetch = originalFetch
  }
})

test('upstream error details redact inbound credentials and signed URLs', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    return new Response(JSON.stringify({
      request_headers: { cookie: 'sessionid=abc123; sid_guard=guard123' },
      plain_session_echo: 'abc123',
      plain_guard_echo: 'guard123',
      audio_url: 'https://music.douyin.com/audio.m4a?X-Amz-Signature=signed-secret&Expires=123',
    }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/auth/me`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Cookie: 'sessionid=abc123; sid_guard=guard123',
      },
      body: '{}',
    }).then((item) => item.json())
    const text = JSON.stringify(response)
    assert.equal(response.code, 50200)
    assert.equal(text.includes('abc123'), false)
    assert.equal(text.includes('guard123'), false)
    assert.equal(text.includes('signed-secret'), false)
    assert.equal(text.includes('X-Amz-Signature=signed-secret'), false)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

test('download errors redact signed URL query values', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => new Response('blocked', { status: 403 })
  try {
    await assert.rejects(
      async () => {
        await downloadBinary('https://music.douyin.com/audio.m4a?X-Amz-Signature=signed-secret&Expires=123', { maxBytes: 1024 })
      },
      (error) => {
        const text = JSON.stringify(error.details)
        assert.equal(text.includes('signed-secret'), false)
        assert.equal(text.includes('X-Amz-Signature=signed-secret'), false)
        return /下载请求失败/.test(error.message)
      },
    )
  } finally {
    global.fetch = originalFetch
  }
})

test('capabilities do not expose secrets', () => {
  const { capabilities } = require('../src/capabilities')
  const text = JSON.stringify(capabilities)
  assert.equal(text.includes('sessionid='), false)
  assert.equal(text.includes('X-Helios'), false)
  assert.equal(text.includes('PlayAuth'), false)
})

test('api capabilities route returns all documented module capability rows without secrets', async () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const { capabilities } = require('../src/capabilities')
  const openapi = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'openapi.json'), 'utf8'))
  const documentedModuleRoutes = Object.keys(openapi.paths).filter((route) => !['/health', '/api/list'].includes(route))
  const capabilityRoutes = new Map(capabilities.map((item) => [item.route, item]))
  const operationMethods = ['get', 'post', 'put', 'patch', 'delete']
  for (const route of documentedModuleRoutes) {
    const capability = capabilityRoutes.get(route)
    assert.ok(capability, `${route} missing from capabilities`)
    const openapiMethods = operationMethods.filter((method) => openapi.paths[route][method]).map((method) => method.toUpperCase())
    assert.deepEqual([...capability.methods].sort(), openapiMethods.sort(), `${route} methods drift from OpenAPI`)
  }

  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  try {
    const { port } = server.address()
    const base = `http://127.0.0.1:${port}`
    const response = await fetch(`${base}/api/capabilities`).then((item) => item.json())
    assert.equal(response.code, 0)
    assert.equal(response.data.count, documentedModuleRoutes.length)
    assert.equal(response.data.capabilities.some((item) => item.route === '/recommend/playlist'), true)
    assert.equal(response.data.capabilities.some((item) => item.route === '/feed/mode'), true)
    assert.equal(response.data.capabilities.some((item) => item.route === '/feed/mode/guidance'), true)
    assert.equal(response.data.capabilities.some((item) => item.route === '/search/playlist'), true)
    assert.equal(response.data.capabilities.some((item) => item.route === '/auth/qrcode'), true)
    assert.equal(JSON.stringify(response).includes('sessionid='), false)
  } finally {
    server.close()
  }
})

test('sentencesToLrc formats timed lyrics', () => {
  const lrc = sentencesToLrc([{ startMs: 1234, text: '第一句歌词' }])
  assert.equal(lrc, '[00:01.234]第一句歌词')
})

test('normalizeFeedItem unwraps track wrapper', () => {
  const feedItem = {
    id: '1',
    type: 'track',
    entity: {
      track_wrapper: {
        track: {
          id: '7079108541549643812',
          name: '测试歌曲',
          artists: [{ id: '1', name: '测试歌手' }],
          album: { id: '2', name: '测试专辑' },
        },
      },
    },
  }
  const normalized = normalizeFeedItem(feedItem)
  assert.equal(normalized.track.id, '7079108541549643812')
  assert.equal(normalized.track.artists[0].name, '测试歌手')
})

test('resolveAudioKey accepts hex key and rejects invalid spade_a', () => {
  assert.equal(resolveAudioKey({ hex_key: '00112233445566778899aabbccddeeff' }), '00112233445566778899aabbccddeeff')
  assert.notEqual(decryptSpadeA('invalid-spade-a'), '00112233445566778899aabbccddeeff')
  assert.throws(() => resolveAudioKey({ spade_a: 'invalid-spade-a' }), /spade_a 或 hex_key 无效/)
})

test('MP4Box finds top-level boxes', () => {
  const box = Buffer.alloc(12)
  box.writeUInt32BE(12, 0)
  box.write('ftyp', 4, 'ascii')
  box.write('isom', 8, 'ascii')
  const parsed = MP4Box.findBox(new Uint8Array(box), 'ftyp')
  assert.equal(parsed.type, 'ftyp')
  assert.equal(parsed.size, 12)
})

test('scanForFlacMetadata extracts dfLa box payload', () => {
  const stsdData = Buffer.concat([
    Buffer.from([0x00, 0x00, 0x00, 0x0c]),
    Buffer.from('dfLa'),
    Buffer.from([0x00, 0x00, 0x00, 0x00]),
  ])
  assert.deepEqual(Array.from(scanForFlacMetadata(new Uint8Array(stsdData))), [0, 0, 0, 0])
})

test('assertAllowedDownloadUrl rejects non-allowlisted hosts', () => {
  assert.equal(assertAllowedDownloadUrl('https://music.douyin.com/qishui/share/track?track_id=1').hostname, 'music.douyin.com')
  assert.throws(() => assertAllowedDownloadUrl('https://example.com/audio.m4a'), /url 不在允许下载的域名范围内/)
  assert.throws(() => assertAllowedDownloadUrl('http://music.douyin.com/audio.m4a'), /url 只支持 HTTPS/)
})

test('downloadBinary rejects redirects outside allowlist', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => new Response('', { status: 302, headers: { Location: 'https://example.com/audio.m4a' } })
  try {
    await assert.rejects(
      () => downloadBinary('https://music.douyin.com/audio.m4a', { maxBytes: 1024 }),
      /url 不在允许下载的域名范围内/,
    )
  } finally {
    global.fetch = originalFetch
  }
})

test('downloadBinary enforces content length limit', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => new Response('blocked', { status: 200, headers: { 'Content-Length': '2048' } })
  try {
    await assert.rejects(
      () => downloadBinary('https://music.douyin.com/audio.m4a', { maxBytes: 1024 }),
      /下载文件超过大小限制/,
    )
  } finally {
    global.fetch = originalFetch
  }
})

test('mediaPlayer posts app payload and redacts playback secrets', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url), 'https://beta-luna.douyin.com/luna/media-player')
    assert.equal(options.method, 'POST')
    assert.equal(options.headers.Cookie, 'sessionid=secret')
    const body = JSON.parse(options.body)
    assert.equal(body.media_id, '7079108541549643812')
    assert.equal(body.media_type, 'track')
    assert.equal(body.enable_dash, true)
    return new Response(JSON.stringify({
      player_infos: [{
        media_id: '7079108541549643812',
        expire_at: 123,
        url_player_info: 'secret-url-player-info',
        video_model: '{"play_auth":"secret"}',
        video_model_type: 1,
        audio_effects: { eq: 'on' },
      }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.mediaPlayer({ track_id: '7079108541549643812' }, { cookie: 'sessionid=secret' })
    assert.equal(result.player_infos[0].has_url_player_info, true)
    assert.equal(result.player_infos[0].has_video_model, true)
    assert.deepEqual(result.player_infos[0].audio_effect_keys, ['eq'])
    assert.equal(result.upstream.player_infos[0].url_player_info, '<redacted>')
    assert.equal(result.upstream.player_infos[0].video_model, '<redacted>')
  } finally {
    global.fetch = originalFetch
  }
})

test('downloadUrl sends query sessionid as upstream Cookie', async () => {
  const originalFetch = global.fetch
  global.fetch = async (url, options) => {
    assert.equal(String(url).includes('/luna/h5/seo_track'), true)
    assert.equal(options.headers.Cookie, 'sessionid=pool-secret;')
    return new Response(JSON.stringify({
      seo_track: {
        track: {
          id: '7079108541549643812',
          name: '测试歌曲',
          artists: [{ id: '1', name: '测试歌手' }],
          album: { id: '2', name: '测试专辑' },
        },
      },
      track_player: {
        video_model: JSON.stringify({
          video_list: [{ main_url: 'https://music.douyin.com/audio.m4a' }],
        }),
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.downloadUrl({ track_id: '7079108541549643812', sessionid: 'pool-secret' })
    assert.equal(result.audio_url, 'https://music.douyin.com/audio.m4a')
    assert.equal(JSON.stringify(result).includes('pool-secret'), false)
  } finally {
    global.fetch = originalFetch
  }
})

test('downloadFile does not forward request Cookie to audio URL', async () => {
  const originalFetch = global.fetch
  global.fetch = async (_url, options) => {
    assert.equal(options.headers.Cookie, undefined)
    return new Response('audio', { status: 200, headers: { 'Content-Type': 'audio/mp4' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    const result = await client.downloadFile({ audio_url: 'https://music.douyin.com/audio.m4a' }, { cookie: 'sessionid=secret' })
    assert.equal(Buffer.from(result.audio_base64, 'base64').toString('utf8'), 'audio')
  } finally {
    global.fetch = originalFetch
  }
})

test('audioDecrypt rejects base64 payloads over max_bytes before parsing', async () => {
  const client = new QishuiClient({ timeoutMs: 1000 })
  await assert.rejects(
    () => client.audioDecrypt({
      hex_key: '00112233445566778899aabbccddeeff',
      audio_base64: Buffer.from('abc').toString('base64'),
      max_bytes: 2,
    }),
    /audio_base64 超过大小限制/,
  )
})

test('audioDecrypt does not forward request Cookie to audio URL', async () => {
  const originalFetch = global.fetch
  global.fetch = async (_url, options) => {
    assert.equal(options.headers.Cookie, undefined)
    return new Response('not-mp4', { status: 200, headers: { 'Content-Type': 'audio/mp4' } })
  }
  try {
    const client = new QishuiClient({ timeoutMs: 1000 })
    await assert.rejects(
      () => client.audioDecrypt({
        hex_key: '00112233445566778899aabbccddeeff',
        audio_url: 'https://music.douyin.com/audio.m4a',
      }, { cookie: 'sessionid=secret' }),
      /音频容器缺少 moov box/,
    )
  } finally {
    global.fetch = originalFetch
  }
})

test('sensitive POST routes reject GET and query parameters', async () => {
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  try {
    const { port } = server.address()
    const base = `http://127.0.0.1:${port}`
    const getResponse = await fetch(`${base}/decrypt/spade?hex_key=00112233445566778899aabbccddeeff`).then((response) => response.json())
    assert.equal(getResponse.code, 40400)

    const postQueryResponse = await fetch(`${base}/decrypt/spade?hex_key=00112233445566778899aabbccddeeff`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    }).then((response) => response.json())
    assert.equal(postQueryResponse.code, 40000)
    assert.equal(postQueryResponse.message, '敏感参数必须放在 POST JSON body 中')
  } finally {
    server.close()
  }
})

test('default routes reject unsupported HTTP methods before upstream requests', async () => {
  const originalFetch = global.fetch
  const app = await constructServer()
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  global.fetch = async (url, options) => {
    if (String(url).startsWith('http://127.0.0.1:')) return originalFetch(url, options)
    throw new Error('fetch should not be called')
  }
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/search?keywords=test`, { method: 'DELETE' }).then((item) => item.json())
    assert.equal(response.code, 40400)
  } finally {
    global.fetch = originalFetch
    server.close()
  }
})

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
