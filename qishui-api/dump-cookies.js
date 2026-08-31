const fs = require('fs')
const initSqlJs = require('sql.js')
async function main() {
  const SQL = await initSqlJs()
  const db = new SQL.Database(fs.readFileSync(process.argv[2]))
  const tables = db.exec("SELECT name FROM sqlite_master WHERE type='table'")
  console.log('tables:', JSON.stringify(tables.length ? tables[0].values : []))
  for (const [t] of (tables.length ? tables[0].values : [])) {
    const c = db.exec('SELECT COUNT(*) FROM "' + t + '"')
    console.log('table', t, 'rows=', c[0].values[0][0])
  }
  const all = db.exec('SELECT host_key, name FROM cookies LIMIT 50')
  if (all.length) console.log(JSON.stringify(all[0].values))
}
main().catch(e => { console.error('ERR', e.message); process.exit(1) })
