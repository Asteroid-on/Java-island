async function authQrcode(_query, { client }) {
  return client.authQrcode()
}

authQrcode.methods = ['get']

module.exports = authQrcode
