async function downloadFile(query, { client, cookie }) {
  return client.downloadFile(query, { cookie })
}

downloadFile.methods = ['post']
downloadFile.bodyOnly = true

module.exports = downloadFile
