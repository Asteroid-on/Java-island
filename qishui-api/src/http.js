const { UpstreamError } = require('./errors')
const { collectHeaderSecrets, sanitizeForExternal } = require('./redaction')

function appendQuery(url, query) {
  if (!query || typeof query !== 'object') return url
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    url.searchParams.set(key, String(value))
  }
  return url
}

function headersToObject(headers) {
  const output = {}
  if (!headers) return output

  for (const [key, value] of headers.entries()) {
    const normalized = key.toLowerCase()
    if (normalized === 'set-cookie' && output[normalized]) {
      output[normalized] = Array.isArray(output[normalized]) ? [...output[normalized], value] : [output[normalized], value]
    } else {
      output[normalized] = value
    }
  }

  const setCookies = typeof headers.getSetCookie === 'function' ? headers.getSetCookie() : []
  if (Array.isArray(setCookies) && setCookies.length) output['set-cookie'] = setCookies

  return output
}

async function requestJson(urlString, options = {}) {
  const url = appendQuery(new URL(urlString), options.query)
  const controller = new AbortController()
  const timeoutMs = options.timeoutMs || 15000
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  const headers = {
    Accept: 'application/json, text/plain, */*',
    ...(options.headers || {}),
  }

  let body
  if (options.body !== undefined) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json; charset=utf-8'
    body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
  }

  const secrets = collectHeaderSecrets(headers)
  const details = (value) => sanitizeForExternal(value, { secrets })

  try {
    const response = await fetch(url, {
      method: options.method || (body === undefined ? 'GET' : 'POST'),
      headers,
      body,
      signal: controller.signal,
      redirect: options.redirect || 'follow',
    })
    const text = await response.text()
    let parsed = null
    if (text) {
      try {
        parsed = JSON.parse(text)
      } catch (_) {
        parsed = null
      }
    }

    if (!response.ok) {
      throw new UpstreamError(`上游请求失败：HTTP ${response.status}`, details({
        url: url.toString(),
        status: response.status,
        body: parsed ?? text.slice(0, 500),
      }))
    }

    return {
      status: response.status,
      headers: headersToObject(response.headers),
      body: parsed,
      text,
      url: url.toString(),
    }
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new UpstreamError('上游请求超时', details({ url: url.toString(), timeout_ms: timeoutMs }))
    }
    if (error instanceof UpstreamError) throw error
    throw new UpstreamError(error.message, details({ url: url.toString() }))
  } finally {
    clearTimeout(timer)
  }
}

module.exports = {
  requestJson,
}
