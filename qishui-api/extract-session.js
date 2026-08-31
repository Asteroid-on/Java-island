/**
 * 从 SodaMusic (Chromium 内核) Cookies 数据库提取登录态。
 * 用法: node extract-session.js <Cookies.db> <keyHexFile> <sessionOutFile>
 * 仅本地一次性工具：读取 sql.js 解库 → AES-256-GCM(v10) 解密 → 输出 sessionid。
 * 控制台只回显掩码值，不打印明文凭据。
 */
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const initSqlJs = require('sql.js')

async function main() {
  const dbPath = process.argv[2]
  const keyHexFile = process.argv[3]
  const outFile = process.argv[4] || path.join(__dirname, '.session.tmp')
  const key = Buffer.from(fs.readFileSync(keyHexFile, 'utf8').trim(), 'hex')

  const SQL = await initSqlJs()
  const db = new SQL.Database(fs.readFileSync(dbPath))
  const res = db.exec('SELECT host_key, name, value, encrypted_value FROM cookies')
  const decrypted = {}
  if (res.length > 0) {
    for (const [host, name, plain, enc] of res[0].values) {
      // 优先明文 value 列（部分 Electron 客户端不启用加密存储）
      const plainText = typeof plain === 'string' ? plain : (plain && plain.length ? Buffer.from(plain).toString('utf8') : '')
      if (plainText) {
        decrypted[host + '|' + name] = plainText
        continue
      }
      if (!enc || !enc.length) continue
      const ver = Buffer.from(enc.slice(0, 3)).toString('latin1')
      if (ver !== 'v10' && ver !== 'v20') continue
      try {
        const nonce = enc.slice(3, 15)
        const body = enc.slice(15)
        const tag = body.slice(body.length - 16)
        const data = body.slice(0, body.length - 16)
        const d = crypto.createDecipheriv('aes-256-gcm', key, nonce)
        d.setAuthTag(tag)
        decrypted[host + '|' + name] = Buffer.concat([d.update(data), d.final()]).toString('utf8')
      } catch (e) {
        decrypted[host + '|' + name] = '<decrypt-fail:' + ver + '>'
      }
    }
  }

  const keys = Object.keys(decrypted)
  const pick =
    keys.find((k) => k.includes('qishui') && k.endsWith('|sessionid')) ||
    keys.find((k) => k.includes('douyin') && k.endsWith('|sessionid')) ||
    keys.find((k) => k.endsWith('|sessionid')) ||
    ''
  const sessionid = pick ? decrypted[pick] : ''

  const masked = sessionid
    ? sessionid.slice(0, 4) + '****' + sessionid.slice(-4) + ' (len=' + sessionid.length + ')'
    : '(none)'
  console.log(JSON.stringify({
    source: pick,
    sessionid: masked,
    cookie_names: [...new Set(keys.map((k) => k.split('|')[1]))],
  }))
  fs.writeFileSync(outFile, sessionid)
}

main().catch((e) => { console.error('ERR ' + e.message); process.exit(1) })
