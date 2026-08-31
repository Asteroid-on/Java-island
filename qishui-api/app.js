#!/usr/bin/env node
const { serveQishuiApi } = require('./server')

serveQishuiApi().catch((error) => {
  console.error(error)
  process.exit(1)
})
