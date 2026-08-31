async function meCollectionMixed(query, { client, cookie }) {
  return client.meCollectionMixed(query, { cookie })
}

meCollectionMixed.methods = ['post']
meCollectionMixed.bodyOnly = true

module.exports = meCollectionMixed
