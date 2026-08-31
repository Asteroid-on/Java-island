const { ValidationError, UpstreamError } = require('./errors')
const { pickUrl, normalizeTrack } = require('./normalizers')
const { sentencesToLrc, sentencesToTimedLines } = require('./lyrics')

const ALLOWED_HOST_SUFFIXES = ['douyin.com', 'qishui.com']

function isAllowedHost(hostname) {
  return ALLOWED_HOST_SUFFIXES.some((suffix) => hostname === suffix || hostname.endsWith(`.${suffix}`))
}

function assertAllowedUrl(input) {
  let url
  try {
    url = new URL(input)
  } catch (_) {
    throw new ValidationError('url 不是有效链接')
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new ValidationError('url 只支持 HTTP/HTTPS')
  if (!isAllowedHost(url.hostname)) throw new ValidationError('url 只允许汽水/抖音域名')
  return url
}

async function fetchAllowedText(input, options = {}) {
  let url = assertAllowedUrl(input)
  const timeoutMs = options.timeoutMs || 15000
  for (let index = 0; index < 5; index += 1) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: options.headers || {},
        redirect: 'manual',
        signal: controller.signal,
      })
      if (response.status >= 300 && response.status < 400 && response.headers.get('location')) {
        url = assertAllowedUrl(new URL(response.headers.get('location'), url).toString())
        continue
      }
      if (!response.ok) throw new UpstreamError(`分享页请求失败：HTTP ${response.status}`, { url: url.toString() })
      return await response.text()
    } catch (error) {
      if (error.name === 'AbortError') throw new UpstreamError('分享页请求超时', { url: url.toString(), timeout_ms: timeoutMs })
      if (error instanceof ValidationError || error instanceof UpstreamError) throw error
      throw new UpstreamError(error.message, { url: url.toString() })
    } finally {
      clearTimeout(timer)
    }
  }
  throw new UpstreamError('分享页重定向次数过多', { url: url.toString() })
}

function extractBalancedJson(text, startIndex) {
  let depth = 0
  let inString = false
  let escape = false
  for (let index = startIndex; index < text.length; index += 1) {
    const char = text[index]
    if (inString) {
      if (escape) {
        escape = false
      } else if (char === '\\') {
        escape = true
      } else if (char === '"') {
        inString = false
      }
      continue
    }
    if (char === '"') {
      inString = true
    } else if (char === '{') {
      depth += 1
    } else if (char === '}') {
      depth -= 1
      if (depth === 0) return text.slice(startIndex, index + 1)
    }
  }
  return ''
}

function extractRouterData(html) {
  const markerIndex = html.indexOf('_ROUTER_DATA')
  if (markerIndex < 0) throw new UpstreamError('分享页未找到 _ROUTER_DATA')
  const startIndex = html.indexOf('{', markerIndex)
  if (startIndex < 0) throw new UpstreamError('分享页 _ROUTER_DATA 格式异常')
  const jsonText = extractBalancedJson(html, startIndex)
  if (!jsonText) throw new UpstreamError('分享页 _ROUTER_DATA JSON 不完整')
  try {
    return JSON.parse(jsonText)
  } catch (error) {
    throw new UpstreamError('分享页 _ROUTER_DATA JSON 解析失败', { error: error.message })
  }
}

function findAudioOption(routerData) {
  const loaderData = routerData?.loaderData || {}
  const candidates = [
    loaderData.track_page,
    loaderData['song_(id)/page'],
    loaderData['track_(id)/page'],
    ...Object.values(loaderData).filter((value) => value && typeof value === 'object'),
  ]
  for (const candidate of candidates) {
    if (candidate?.audioWithLyricsOption) return candidate.audioWithLyricsOption
  }
  return null
}

function formatSongFromAudioOption(audioOption, routerData) {
  if (!audioOption || typeof audioOption !== 'object') throw new UpstreamError('分享页未找到音频数据')
  const trackInfo = audioOption.trackInfo || {}
  const sentences = audioOption.lyrics?.sentences || []
  return {
    track_id: audioOption.track_id || trackInfo.id || '',
    name: audioOption.trackName || trackInfo.name || '',
    artist_name: audioOption.artistName || '',
    artists: Array.isArray(trackInfo.artists) ? trackInfo.artists.map((artist) => ({ id: String(artist.id || ''), name: artist.name || artist.simple_display_name || '' })) : [],
    album: trackInfo.album
      ? {
          id: String(trackInfo.album.id || ''),
          name: trackInfo.album.name || '',
          cover_url: pickUrl(trackInfo.album.url_cover),
          release_date: trackInfo.album.release_date || null,
        }
      : null,
    cover_url: audioOption.coverURL || pickUrl(trackInfo.album?.url_cover),
    audio_url: audioOption.url || '',
    duration: audioOption.duration || trackInfo.duration || 0,
    vid: audioOption.vid || trackInfo.vid || '',
    lyrics: {
      lrc: sentencesToLrc(sentences),
      lines: sentencesToTimedLines(sentences),
    },
    related_tracks: Array.isArray(audioOption.relatedTracks)
      ? audioOption.relatedTracks.map((item) => normalizeTrack(item.track)).filter(Boolean)
      : [],
    artist_tracks: Array.isArray(audioOption.artistTracks)
      ? audioOption.artistTracks.map((item) => normalizeTrack(item.track)).filter(Boolean)
      : [],
    chart_tracks: Array.isArray(audioOption.chartTracks)
      ? audioOption.chartTracks.map((item) => normalizeTrack(item.track)).filter(Boolean)
      : [],
    raw: {
      audioWithLyricsOption: audioOption,
      routerData,
    },
  }
}

module.exports = {
  assertAllowedUrl,
  fetchAllowedText,
  extractRouterData,
  findAudioOption,
  formatSongFromAudioOption,
}
