const { QishuiClient } = require('../src/qishuiClient')

const REDACTED = '<redacted>'
const SENSITIVE_KEYS = new Set([
  'cookie',
  'cookies',
  'sessionid',
  'session',
  'authorization',
  'token',
  'playauth',
  'spadea',
  'xhelios',
  'xmedusa',
  'urlplayerinfo',
  'videomodel',
  'playurl',
  'audiourl',
  'downloadurl',
  'headers',
  'setcookie',
])
const SENSITIVE_VALUE_NAMES = [
  'cookie',
  'sessionid',
  'session_id',
  'authorization',
  'token',
  'PlayAuth',
  'play_auth',
  'spade_a',
  'X-Helios',
  'X-Medusa',
  'url_player_info',
  'video_model',
  'play_url',
  'audio_url',
  'download_url',
  'headers',
  'set-cookie',
]
const SENSITIVE_VALUE_RE = new RegExp(`(${SENSITIVE_VALUE_NAMES.map(escapeRegExp).join('|')})(["']?\\s*[:=]\\s*)("[^"]*"|'[^']*'|[^,;&}\\]\\s]+)`, 'gi')

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function normalizeSensitiveKey(key) {
  return String(key || '').replace(/[-_]/g, '').toLowerCase()
}

function isSensitiveKey(key) {
  return SENSITIVE_KEYS.has(normalizeSensitiveKey(key))
}

function redactSensitiveString(value) {
  const truncated = value.length > 1000 ? `${value.slice(0, 1000)}<truncated>` : value
  return truncated
    .replace(SENSITIVE_VALUE_RE, `$1$2${REDACTED}`)
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, `$1${REDACTED}`)
    .replace(/(sessionid=)[^;\s,]+/gi, `$1${REDACTED}`)
}

function sanitizeForOutput(value, key = '', depth = 0) {
  if (isSensitiveKey(key)) return REDACTED
  if (value === null || value === undefined) return value
  if (depth > 8) return '<truncated>'
  if (typeof value === 'string') return redactSensitiveString(value)
  if (typeof value === 'number' || typeof value === 'boolean') return value
  if (Array.isArray(value)) return value.slice(0, 20).map((item) => sanitizeForOutput(item, key, depth + 1))
  if (value instanceof Error) return errorResult('error', value)
  if (typeof value !== 'object') return String(value)

  const output = {}
  for (const [propertyName, propertyValue] of Object.entries(value).slice(0, 50)) {
    output[propertyName] = sanitizeForOutput(propertyValue, propertyName, depth + 1)
  }
  return output
}

function errorResult(name, error) {
  const result = {
    name,
    ok: false,
    message: sanitizeForOutput(error?.message || String(error)),
  }
  if (error?.status !== undefined) result.status = error.status
  if (error?.code !== undefined) result.code = error.code
  result.details = sanitizeForOutput(error?.details ?? null)
  return result
}

async function record(name, action, options = {}) {
  try {
    const data = await action()
    const result = {
      name,
      ok: true,
      summary: options.summarize ? sanitizeForOutput(options.summarize(data)) : 'ok',
    }
    if (options.requiresAppContext) result.requires_app_context = true
    if (options.classify) Object.assign(result, options.classify(data))
    return { result, data }
  } catch (error) {
    const result = errorResult(name, error)
    if (options.requiresAppContext) result.requires_app_context = true
    return { result, data: null }
  }
}

function skippedResult(name, reason) {
  return { name, ok: false, skipped: true, reason }
}

async function main() {
  const client = new QishuiClient()
  const results = []

  const recommendPlaylists = await record('recommendPlaylists', () => client.recommendPlaylists({ count: 5 }), {
    summarize: (data) => ({ playlists: data.playlists.length, first: data.playlists[0]?.title || '' }),
  })
  results.push(recommendPlaylists.result)

  const firstPlaylist = recommendPlaylists.result.ok ? recommendPlaylists.data?.playlists?.[0] : null
  const playlistId = firstPlaylist?.id || firstPlaylist?.playlist_id || ''

  results.push((await record('searchPlaylists', () => client.searchPlaylists({ keywords: '周杰伦' }), {
    summarize: (data) => ({ playlists: data.playlists.length, source_strategy: data.source_strategy }),
  })).result)

  results.push((await record('searchMixed', () => client.searchMixed({ keywords: '周杰伦' }), {
    summarize: (data) => ({ tracks: data.tracks.length, playlists: data.playlists.length, videos: data.videos.length }),
  })).result)

  if (playlistId) {
    results.push((await record('playlistDetail', () => client.playlistDetail({ playlist_id: playlistId, count: 5 }), {
      summarize: (data) => ({ playlist: data.playlist?.title || '', media_resources: data.media_resources.length }),
    })).result)
    results.push((await record('playlistFeedMedia', () => client.playlistFeedMedia({ playlist_id: playlistId, count: 5 }), {
      requiresAppContext: true,
      summarize: (data) => ({ items: data.items.length, has_more: data.has_more }),
    })).result)
  } else {
    results.push(skippedResult('playlistDetail', 'recommendPlaylists returned no playlist id'))
    results.push(skippedResult('playlistFeedMedia', 'recommendPlaylists returned no playlist id'))
  }

  results.push((await record('dailyMix', () => client.dailyMix({ count: 5 }), {
    requiresAppContext: true,
    summarize: (data) => ({ supported_without_app_context: data.supported_without_app_context }),
    classify: (data) => (data.supported_without_app_context === false ? { ok: false, requires_app_context: true } : {}),
  })).result)

  results.push((await record('authQrcode', () => client.authQrcode(), {
    summarize: (data) => ({ has_data: Boolean(data?.data || data?.token || data?.qrcode) }),
  })).result)

  console.log(JSON.stringify({ results }, null, 2))

  const hardFailures = results.filter((item) => !item.ok && !item.requires_app_context && !item.skipped)
  if (hardFailures.length) process.exitCode = 1
}

main().catch((error) => {
  console.log(JSON.stringify({ results: [errorResult('liveSmoke', error)] }, null, 2))
  process.exitCode = 1
})
