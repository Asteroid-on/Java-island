const DEFAULT_LUNA_API_HOST = 'https://beta-luna.douyin.com'
const DEFAULT_PC_API_HOST = 'https://api.qishui.com'
const DEFAULT_MUSIC_SHARE_HOST = 'https://music.douyin.com'
const DEFAULT_DOWNLOAD_ALLOWED_HOSTS = [
  'douyin.com',
  'qishui.com',
  'douyinvod.com',
  'bytedance.com',
  'bytedance.net',
  'byted.org',
  'bytecdn.cn',
  'bytecdntp.com',
  'snssdk.com',
].join(',')

function randomNumericId(length) {
  let value = ''
  while (value.length < length) value += Math.floor(Math.random() * 10)
  return value
}

function readInt(name, fallback) {
  const raw = process.env[name]
  if (!raw) return fallback
  const value = Number.parseInt(raw, 10)
  return Number.isFinite(value) ? value : fallback
}

function readBool(name, fallback) {
  const raw = process.env[name]
  if (raw === undefined || raw === '') return fallback
  return !['0', 'false', 'no', 'off'].includes(raw.toLowerCase())
}

module.exports = {
  port: readInt('PORT', 3300),
  host: process.env.HOST || '0.0.0.0',
  requestTimeoutMs: readInt('QISHUI_REQUEST_TIMEOUT_MS', 15000),
  lunaApiHost: process.env.QISHUI_LUNA_API_HOST || DEFAULT_LUNA_API_HOST,
  pcApiHost: process.env.QISHUI_PC_API_HOST || DEFAULT_PC_API_HOST,
  musicShareHost: process.env.QISHUI_MUSIC_SHARE_HOST || DEFAULT_MUSIC_SHARE_HOST,
  corsAllowOrigin: process.env.CORS_ALLOW_ORIGIN || '*',
  xHelios: process.env.QISHUI_X_HELIOS || '',
  xMedusa: process.env.QISHUI_X_MEDUSA || '',
  // Java-island 适配：本地提取的汽水登录态（仅用于上游请求透传，不参与日志/响应回显）
  sessionid: process.env.QISHUI_SESSIONID || '',
  deviceId: process.env.QISHUI_DEVICE_ID || randomNumericId(16),
  installId: process.env.QISHUI_INSTALL_ID || randomNumericId(16),
  fp: process.env.QISHUI_FP || '',
  maxPageSize: readInt('QISHUI_MAX_PAGE_SIZE', 50),
  enableDecrypt: readBool('QISHUI_ENABLE_DECRYPT', true),
  downloadMaxBytes: readInt('QISHUI_DOWNLOAD_MAX_BYTES', 52428800),
  downloadAllowedHosts: process.env.QISHUI_DOWNLOAD_ALLOWED_HOSTS || DEFAULT_DOWNLOAD_ALLOWED_HOSTS,
}
