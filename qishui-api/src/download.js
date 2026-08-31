const path = require('path')
const config = require('./config')
const { ValidationError, UpstreamError } = require('./errors')
const { sanitizeForExternal } = require('./redaction')

function allowedHostSuffixes() {
  return config.downloadAllowedHosts.split(',').map((host) => host.trim().toLowerCase()).filter(Boolean)
}

function isAllowedDownloadHost(hostname) {
  const normalizedHostname = hostname.toLowerCase()
  return allowedHostSuffixes().some((suffix) => normalizedHostname === suffix || normalizedHostname.endsWith(`.${suffix}`))
}

function assertAllowedDownloadUrl(input) {
  let url
  try {
    url = new URL(String(input || '').trim())
  } catch (_) {
    throw new ValidationError('url 不是有效链接')
  }
  if (url.protocol !== 'https:') throw new ValidationError('url 只支持 HTTPS')
  if (!isAllowedDownloadHost(url.hostname)) throw new ValidationError('url 不在允许下载的域名范围内')
  return url
}

function filenameFromUrl(url) {
  const basename = path.posix.basename(url.pathname)
  return basename && basename !== '/' ? basename : 'audio.bin'
}

function filenameFromContentDisposition(header) {
  if (!header) return ''
  const utf8Match = header.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match) return decodeURIComponent(utf8Match[1].trim())
  const asciiMatch = header.match(/filename="?([^";]+)"?/i)
  return asciiMatch ? asciiMatch[1].trim() : ''
}

function normalizeMaxBytes(value) {
  const parsed = Number.parseInt(value ?? config.downloadMaxBytes, 10)
  if (!Number.isFinite(parsed) || parsed <= 0) throw new ValidationError('max_bytes 必须是正整数')
  return Math.min(parsed, config.downloadMaxBytes)
}

async function readResponseBody(response, maxBytes) {
  const contentLength = Number.parseInt(response.headers.get('content-length') || '0', 10)
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    throw new ValidationError('下载文件超过大小限制', { max_bytes: maxBytes, content_length: contentLength })
  }

  if (!response.body || typeof response.body.getReader !== 'function') {
    const arrayBuffer = await response.arrayBuffer()
    if (arrayBuffer.byteLength > maxBytes) throw new ValidationError('下载文件超过大小限制', { max_bytes: maxBytes })
    return Buffer.from(arrayBuffer)
  }

  const reader = response.body.getReader()
  const chunks = []
  let receivedBytes = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    receivedBytes += value.byteLength
    if (receivedBytes > maxBytes) {
      await reader.cancel()
      throw new ValidationError('下载文件超过大小限制', { max_bytes: maxBytes })
    }
    chunks.push(Buffer.from(value))
  }
  return Buffer.concat(chunks)
}

function isRedirectStatus(status) {
  return [301, 302, 303, 307, 308].includes(status)
}

function downloadErrorDetails(value) {
  return sanitizeForExternal(value)
}

async function fetchWithAllowedRedirects(initialUrl, requestOptions, maxRedirects = 5) {
  let currentUrl = assertAllowedDownloadUrl(initialUrl)
  for (let redirectCount = 0; redirectCount <= maxRedirects; redirectCount += 1) {
    const response = await fetch(currentUrl, {
      ...requestOptions,
      redirect: 'manual',
    })
    if (!isRedirectStatus(response.status)) return { response, url: currentUrl }
    const location = response.headers.get('location')
    if (!location) throw new UpstreamError('下载重定向缺少 Location', downloadErrorDetails({ url: currentUrl.toString(), status: response.status }))
    if (redirectCount === maxRedirects) throw new UpstreamError('下载重定向次数过多', downloadErrorDetails({ url: currentUrl.toString() }))
    currentUrl = assertAllowedDownloadUrl(new URL(location, currentUrl).toString())
  }
  throw new UpstreamError('下载重定向失败', downloadErrorDetails({ url: currentUrl.toString() }))
}

async function downloadBinary(input, options = {}) {
  const url = assertAllowedDownloadUrl(input)
  const controller = new AbortController()
  const timeoutMs = options.timeoutMs || config.requestTimeoutMs
  const maxBytes = normalizeMaxBytes(options.maxBytes)
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  try {
    const { response, url: finalUrl } = await fetchWithAllowedRedirects(url, {
      method: 'GET',
      headers: {
        Accept: '*/*',
        ...(options.headers || {}),
      },
      signal: controller.signal,
    })
    if (!response.ok) {
      throw new UpstreamError(`下载请求失败：HTTP ${response.status}`, downloadErrorDetails({ url: finalUrl.toString(), status: response.status }))
    }
    const buffer = await readResponseBody(response, maxBytes)
    return {
      url: finalUrl.toString(),
      buffer,
      filename: filenameFromContentDisposition(response.headers.get('content-disposition')) || filenameFromUrl(finalUrl),
      content_type: response.headers.get('content-type') || 'application/octet-stream',
      content_length: buffer.length,
    }
  } catch (error) {
    if (error.name === 'AbortError') throw new UpstreamError('下载请求超时', downloadErrorDetails({ url: url.toString(), timeout_ms: timeoutMs }))
    if (error instanceof ValidationError || error instanceof UpstreamError) throw error
    throw new UpstreamError(error.message, downloadErrorDetails({ url: url.toString() }))
  } finally {
    clearTimeout(timer)
  }
}

module.exports = {
  assertAllowedDownloadUrl,
  downloadBinary,
  isAllowedDownloadHost,
}
