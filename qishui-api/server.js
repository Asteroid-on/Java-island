require('dotenv').config()
const fs = require('fs')
const path = require('path')
const express = require('express')
const config = require('./src/config')
const { QishuiClient } = require('./src/qishuiClient')
const { HttpError, ValidationError } = require('./src/errors')
const { ok, fail } = require('./src/response')
const { sanitizeForExternal } = require('./src/redaction')

async function getModuleDefinitions(modulesPath) {
  const files = await fs.promises.readdir(modulesPath)
  return files
    .filter((file) => file.endsWith('.js'))
    .sort()
    .map((file) => {
      const modulePath = path.join(modulesPath, file)
      const moduleHandler = require(modulePath)
      const route = moduleHandler.route || `/${file.replace(/\.js$/i, '').replace(/_/g, '/')}`
      return { file, route, moduleHandler }
    })
}

function createTraceId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function mergeInput(req) {
  const body = req.body && typeof req.body === 'object' ? req.body : {}
  return {
    ...req.query,
    ...body,
    body,
  }
}

function resolveCors(req) {
  const origins = config.corsAllowOrigin.split(',').map((origin) => origin.trim()).filter(Boolean)
  if (origins.includes('*')) return { origin: '*', credentials: false }
  const requestOrigin = req.headers.origin
  if (requestOrigin && origins.includes(requestOrigin)) return { origin: requestOrigin, credentials: true }
  if (!requestOrigin && origins.length === 1) return { origin: origins[0], credentials: true }
  return { origin: '', credentials: false }
}

function setCorsHeaders(req, res) {
  const cors = resolveCors(req)
  const headers = {
    'Access-Control-Allow-Headers': 'X-Requested-With,Content-Type,Cookie',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Content-Type': 'application/json; charset=utf-8',
  }
  if (cors.origin) headers['Access-Control-Allow-Origin'] = cors.origin
  if (cors.credentials) headers['Access-Control-Allow-Credentials'] = 'true'
  res.set(headers)
}

async function constructServer() {
  const app = express()
  const modulesPath = path.join(__dirname, 'module')
  const moduleDefs = await getModuleDefinitions(modulesPath)
  const client = new QishuiClient()

  app.set('trust proxy', true)
  app.use(express.json({ limit: '1mb' }))
  app.use(express.urlencoded({ extended: false, limit: '1mb' }))
  app.use((req, res, next) => {
    req.traceId = req.headers['x-trace-id'] || createTraceId()
    setCorsHeaders(req, res)
    if (req.method === 'OPTIONS') return res.status(204).end()
    next()
  })

  app.get('/', (req, res) => {
    res.json(ok({
      name: 'qishui-api',
      version: require('./package.json').version,
      routes: moduleDefs.map((item) => item.route),
    }, req.traceId))
  })

  app.get('/health', (req, res) => {
    res.json(ok({ status: 'ok' }, req.traceId))
  })

  app.get('/api/list', (req, res) => {
    res.json(ok(moduleDefs.map((item) => ({ route: item.route, file: item.file })), req.traceId))
  })

  for (const item of moduleDefs) {
    const routeHandler = async (req, res, next) => {
      try {
        if (item.moduleHandler.bodyOnly && Object.keys(req.query || {}).length) {
          throw new ValidationError('敏感参数必须放在 POST JSON body 中')
        }
        const data = await item.moduleHandler(mergeInput(req), {
          req,
          client,
          cookie: req.headers.cookie || (config.sessionid ? `sessionid=${config.sessionid};` : ''),
          traceId: req.traceId,
        })
        if (data && typeof data === 'object' && data.status && data.body) {
          return res.status(data.status).json(data.body)
        }
        return res.json(ok(data, req.traceId))
      } catch (error) {
        return next(error)
      }
    }
    const methods = Array.isArray(item.moduleHandler.methods) && item.moduleHandler.methods.length ? item.moduleHandler.methods : ['get', 'post']
    for (const method of methods) app[method.toLowerCase()](item.route, routeHandler)
  }

  app.use((req, res) => {
    res.status(404).json(fail(40400, '接口不存在', req.traceId, { path: req.path }))
  })

  app.use((error, req, res, _next) => {
    const status = error instanceof HttpError ? error.status : 500
    const code = error instanceof HttpError ? error.code : 50000
    const message = error instanceof HttpError ? error.message : '服务器内部错误'
    res.status(status).json(fail(code, message, req.traceId, sanitizeForExternal(error.details)))
  })

  return app
}

async function serveQishuiApi(options = {}) {
  const app = await constructServer()
  const port = options.port || config.port
  const host = options.host || config.host
  return new Promise((resolve) => {
    const server = app.listen(port, host, () => {
      console.log(`qishui-api listening on http://${host}:${port}`)
      resolve(server)
    })
  })
}

module.exports = {
  constructServer,
  serveQishuiApi,
  getModuleDefinitions,
}
