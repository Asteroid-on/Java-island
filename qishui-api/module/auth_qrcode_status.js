async function authQrcodeStatus(query, { client }) {
  return client.authQrcodeStatus(query)
}

authQrcodeStatus.methods = ['post']
authQrcodeStatus.bodyOnly = true

module.exports = authQrcodeStatus
