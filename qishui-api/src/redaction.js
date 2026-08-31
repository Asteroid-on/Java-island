const REDACTED = '<redacted>'

const SENSITIVE_KEY_PARTS = [
  'cookie',
  'setcookie',
  'requestheaders',
  'headers',
  'authorization',
  'session',
  'sessionid',
  'token',
  'passport',
  'sidguard',
  'sidtt',
  'uidtt',
  'odintt',
  'passportcsrftoken',
  'csrf',
  'xhelios',
  'xmedusa',
  'playauth',
  'spade',
  'urlplayerinfo',
  'videomodel',
  'playurl',
  'audiourl',
  'downloadurl',
  'mainurl',
  'backupurl',
  'sourceurl',
  'urllist',
  'secret',
]

const SENSITIVE_ASSIGNMENT_RE = /((?:sessionid|session_id|sid_guard|sid_tt|uid_tt|odin_tt|passport_csrf_token|csrf_token|cookie|authorization|token|playauth|play_auth|spade_a|x-helios|x-medusa)\s*[=:]\s*)("[^"]*"|'[^']*'|[^,;&}\]\s]+)/gi
const URL_RE = /https?:\/\/[^\s"'<>]+/gi

function normalizeSensitiveKey(key) {
  return String(key || '').replace(/[-_\s]/g, '').toLowerCase()
}

function isSensitiveKey(key) {
  const normalized = normalizeSensitiveKey(key)
  return SENSITIVE_KEY_PARTS.some((part) => normalized === part || normalized.includes(part))
}

function safeUrlForOutput(value) {
  try {
    const url = new URL(String(value))
    return `${url.origin}${url.pathname}${url.search ? '?<redacted>' : ''}`
  } catch (_) {
    return value
  }
}

function redactUrlQueries(value) {
  return value.replace(URL_RE, (match) => safeUrlForOutput(match))
}

function redactSensitiveString(value, secrets = []) {
  let output = String(value)
  const sortedSecrets = [...new Set(secrets.map((secret) => String(secret || '')).filter((secret) => secret.length >= 4))]
    .sort((left, right) => right.length - left.length)
  for (const secret of sortedSecrets) output = output.split(secret).join(REDACTED)
  return redactUrlQueries(output
    .replace(SENSITIVE_ASSIGNMENT_RE, `$1${REDACTED}`)
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, `$1${REDACTED}`))
}

function sanitizeNode(value, options, key, depth) {
  if (isSensitiveKey(key)) return REDACTED
  if (value === null || value === undefined) return value
  if (depth > 8) return '<truncated>'
  if (typeof value === 'string') {
    const redacted = redactSensitiveString(value, options.secrets)
    return normalizeSensitiveKey(key).endsWith('url') || normalizeSensitiveKey(key) === 'url' ? safeUrlForOutput(redacted) : redacted
  }
  if (typeof value === 'number' || typeof value === 'boolean') return value
  if (Array.isArray(value)) return value.map((item) => sanitizeNode(item, options, key, depth + 1))
  if (value instanceof Error) {
    return sanitizeNode({ name: value.name, message: value.message, details: value.details }, options, key, depth + 1)
  }
  if (typeof value !== 'object') return String(value)

  const output = {}
  for (const [propertyName, propertyValue] of Object.entries(value)) {
    output[propertyName] = sanitizeNode(propertyValue, options, propertyName, depth + 1)
  }
  return output
}

function sanitizeForExternal(value, options = {}) {
  return sanitizeNode(value, { secrets: options.secrets || [] }, '', 0)
}

function collectCookieSecrets(cookie) {
  const secrets = new Set()
  const text = String(cookie || '').trim()
  if (!text) return []
  secrets.add(text)
  for (const part of text.split(';')) {
    const [rawName, ...rawValueParts] = part.split('=')
    const name = rawName.trim()
    const value = rawValueParts.join('=').trim()
    if (!name || !value) continue
    secrets.add(`${name}=${value}`)
    secrets.add(value)
  }
  return [...secrets]
}

function collectHeaderSecrets(headers = {}) {
  const secrets = new Set()
  for (const [key, value] of Object.entries(headers || {})) {
    const values = Array.isArray(value) ? value : [value]
    for (const item of values) {
      const text = String(item || '').trim()
      if (!text) continue
      if (normalizeSensitiveKey(key).includes('cookie')) {
        for (const secret of collectCookieSecrets(text)) secrets.add(secret)
      }
      if (isSensitiveKey(key)) secrets.add(text)
      const bearerMatch = text.match(/Bearer\s+([^\s]+)/i)
      if (bearerMatch) secrets.add(bearerMatch[1])
    }
  }
  return [...secrets]
}

module.exports = {
  REDACTED,
  collectCookieSecrets,
  collectHeaderSecrets,
  isSensitiveKey,
  redactSensitiveString,
  safeUrlForOutput,
  sanitizeForExternal,
}
