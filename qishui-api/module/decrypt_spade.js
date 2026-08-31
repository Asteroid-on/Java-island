async function decryptSpade(query, { client }) {
  return client.decryptSpade(query)
}

decryptSpade.methods = ['post']
decryptSpade.bodyOnly = true

module.exports = decryptSpade
