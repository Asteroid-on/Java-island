async function mePlaylists(query, { client, cookie }) {
  return client.mePlaylists(query, { cookie })
}

mePlaylists.methods = ['post']
mePlaylists.bodyOnly = true

module.exports = mePlaylists
