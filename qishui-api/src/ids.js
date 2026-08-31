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
  const parsed = optionalPositiveInt(value, fallback, name)
  const parsedMaximum = Number.parseInt(maximum, 10)
  if (!Number.isFinite(parsedMaximum) || parsedMaximum <= 0) throw new ValidationError(`${name} 最大值必须是正整数`)
  return Math.min(parsed, parsedMaximum)
}

function decodedCandidates(input) {
  if (!input) return []
  const raw = String(input).trim()
  try {
    const decoded = decodeURIComponent(raw)
    return decoded === raw ? [raw] : [raw, decoded]
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
    /^\s*(\d+)\s*$/,
    /\b(\d{6,})\b/,
  ])
}

function extractVideoId(input) {
  return extractByPatterns(input, [
    /[?&](?:ugc_video_id|video_id)=(\d+)/,
    /\/(?:video|ugc_video)\/(\d+)/,
    /^\s*(\d+)\s*$/,
    /\b(\d{6,})\b/,
  ])
}

function extractPlaylistId(input) {
  return extractByPatterns(input, [
    /[?&]playlist_id=(\d+)/,
    /\/(?:playlist|list)\/(\d+)/,
    /^\s*(\d+)\s*$/,
    /\b(\d{6,})\b/,
  ])
}

function extractResourceIds(input) {
  const empty = { track_id: '', video_id: '', playlist_id: '' }

  const explicitTrackId = extractByPatterns(input, [/[?&]track_id=(\d+)/, /\/(?:track|song)\/(\d+)/])
  if (explicitTrackId) return { ...empty, track_id: explicitTrackId }

  const explicitPlaylistId = extractByPatterns(input, [/[?&]playlist_id=(\d+)/, /\/(?:playlist|list)\/(\d+)/])
  if (explicitPlaylistId) return { ...empty, playlist_id: explicitPlaylistId }

  const explicitVideoId = extractByPatterns(input, [/[?&](?:ugc_video_id|video_id)=(\d+)/, /\/(?:video|ugc_video)\/(\d+)/])
  if (explicitVideoId) return { ...empty, video_id: explicitVideoId }

  const numericId = extractByPatterns(input, [/^\s*(\d+)\s*$/])
  if (numericId) {
    return {
      track_id: extractTrackId(numericId),
      video_id: extractVideoId(numericId),
      playlist_id: extractPlaylistId(numericId),
    }
  }

  return empty
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
