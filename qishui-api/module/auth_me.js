async function authMe(query, { client, cookie }) {
  return client.authMe(query, { cookie })
}

authMe.methods = ['post']
authMe.bodyOnly = true

module.exports = authMe
