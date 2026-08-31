const { capabilities } = require('../src/capabilities')

async function apiCapabilities() {
  return {
    count: capabilities.length,
    capabilities,
  }
}

apiCapabilities.methods = ['get']
apiCapabilities.route = '/api/capabilities'

module.exports = apiCapabilities
