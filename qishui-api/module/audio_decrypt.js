async function audioDecrypt(query, { client, cookie }) {
  return client.audioDecrypt(query, { cookie })
}

audioDecrypt.methods = ['post']
audioDecrypt.bodyOnly = true

module.exports = audioDecrypt
