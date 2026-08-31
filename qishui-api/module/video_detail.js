async function videoDetail(query, { client, cookie }) {
  return client.videoDetail(query, { cookie })
}

videoDetail.methods = ['post']
videoDetail.bodyOnly = true

module.exports = videoDetail
