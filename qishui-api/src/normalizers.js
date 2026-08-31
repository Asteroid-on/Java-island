function pickUrl(value, size) {
  if (!value) return ''
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value[0] || ''
  if (Array.isArray(value.urls) && value.urls.length > 0) {
    // 图片 CDN 结构 {urls, uri, template_prefix}：裸拼是目录地址而非图片，
    // 需追加 ~<template_prefix>-resize:<size> 模板后缀才能取到图片（实测可用）
    const base = `${value.urls[0] || ''}${value.uri || ''}`
    if (value.uri && value.template_prefix) {
      const dim = Number.isInteger(size) && size > 0 ? size : 600
      return `${base}~${value.template_prefix}-resize:${dim}:${dim}.jpeg`
    }
    return base
  }
  return value.url || value.uri || value.template || ''
}

function normalizeArtist(artist) {
  if (!artist || typeof artist !== 'object') return null
  return {
    id: artist.id ? String(artist.id) : '',
    name: artist.name || artist.simple_display_name || artist.user_info?.nickname || '',
    avatar_url: pickUrl(artist.url_avatar || artist.user_info?.medium_avatar_url || artist.user_info?.thumb_avatar_url),
    raw: artist,
  }
}

function normalizeTrack(track) {
  if (!track || typeof track !== 'object') return null
  const album = track.album || {}
  return {
    id: track.id ? String(track.id) : '',
    name: track.name || track.trackName || '',
    duration: track.duration ?? track.duration_ms ?? 0,
    vid: track.vid || '',
    media_type: track.media_type || 'track',
    album: album
      ? {
          id: album.id ? String(album.id) : '',
          name: album.name || '',
          cover_url: pickUrl(album.url_cover || album.cover_url || album.coverURL, 600),
          release_date: album.release_date || null,
        }
      : null,
    artists: Array.isArray(track.artists) ? track.artists.map(normalizeArtist).filter(Boolean) : [],
    stats: track.stats || {},
    raw: track,
  }
}

function unwrapTrackFromResource(resource) {
  return resource?.entity?.track_wrapper?.track || resource?.entity?.track || resource?.track || null
}

function normalizeFeedItem(item) {
  if (!item || typeof item !== 'object') return null
  const track = unwrapTrackFromResource(item)
  return {
    id: item.id ? String(item.id) : track?.id ? String(track.id) : '',
    type: item.type || track?.media_type || '',
    track: normalizeTrack(track),
    recommend_reason: item.recommend_reason_v2 || item.recommendReasonV2 || null,
    predict_info: item.predict_info || item.predictInfo || null,
    raw: item,
  }
}

function normalizePlaylist(playlist) {
  if (!playlist || typeof playlist !== 'object') return null
  return {
    id: playlist.id ? String(playlist.id) : '',
    title: playlist.title || playlist.name || '',
    type: playlist.type ?? playlist.playlist_type ?? null,
    description: playlist.description || playlist.intro || '',
    cover_url: pickUrl(playlist.url_cover || playlist.cover || playlist.cover_url || playlist.coverURL),
    count_tracks: playlist.count_tracks ?? playlist.track_count ?? playlist.song_count ?? null,
    raw: playlist,
  }
}

function extractPlaylistsFromDiscoverMix(data) {
  const blocks = Array.isArray(data?.inner_block) ? data.inner_block : []
  return blocks
    .flatMap((block) => (Array.isArray(block.resources) ? block.resources : []))
    .map((resource) => normalizePlaylist(resource?.entity?.playlist || resource?.playlist))
    .filter((playlist) => playlist && playlist.id)
}

function extractRadiosFromDiscover(data) {
  const blocks = Array.isArray(data?.blocks) ? data.blocks : []
  const radioBlock = blocks.find((block) => block?.type === 'discover_feed_radio')
  const innerBlocks = Array.isArray(radioBlock?.inner_block) ? radioBlock.inner_block : []
  return innerBlocks
    .flatMap((block) => (Array.isArray(block.resources) ? block.resources : []))
    .map((resource) => resource?.entity?.radio)
    .filter(Boolean)
    .map((radio) => ({
      id: radio.radio_id ? String(radio.radio_id) : '',
      name: radio.radio_name || '',
      type: radio.radio_type || '',
      raw: radio,
    }))
    .filter((radio) => radio.id)
}

function normalizePlaylistDetail(data) {
  const mediaResources = Array.isArray(data?.media_resources) ? data.media_resources : []
  return {
    playlist: normalizePlaylist(data?.playlist),
    media_resources: mediaResources.map(normalizeFeedItem).filter(Boolean),
    next_cursor: data?.next_cursor || '',
    session_id: data?.session_id || '',
    raw: data,
  }
}

function normalizeVideo(video) {
  if (!video || typeof video !== 'object') return null
  return {
    id: video.id || video.video_id || video.ugc_video_id ? String(video.id || video.video_id || video.ugc_video_id) : '',
    title: video.title || video.name || video.desc || '',
    cover_url: pickUrl(video.cover_url || video.cover || video.url_cover || video.poster),
    duration: video.duration ?? video.duration_ms ?? 0,
    media_type: video.media_type || video.type || 'ugc_video',
    raw: video,
  }
}

function unwrapEntity(resource) {
  return resource?.entity || resource || {}
}

function normalizeMixedResource(resource) {
  const entity = unwrapEntity(resource)
  const track = normalizeTrack(entity.track_wrapper?.track || entity.track || resource?.track)
  if (track && track.id) return { type: 'track', track, raw: resource }

  const playlist = normalizePlaylist(entity.playlist || resource?.playlist)
  if (playlist && playlist.id) return { type: 'playlist', playlist, raw: resource }

  const video = normalizeVideo(entity.video || entity.ugc_video || resource?.video || resource?.ugc_video)
  if (video && video.id) return { type: 'video', video, raw: resource }

  return null
}

function normalizeSearchGroups(data) {
  const groups = Array.isArray(data?.result_groups) ? data.result_groups : []
  const resources = groups.flatMap((group) => (Array.isArray(group.data) ? group.data : []))
  const mixed = resources.map(normalizeMixedResource).filter(Boolean)
  return {
    tracks: mixed.filter((item) => item.type === 'track').map((item) => item.track),
    playlists: mixed.filter((item) => item.type === 'playlist').map((item) => item.playlist),
    videos: mixed.filter((item) => item.type === 'video').map((item) => item.video),
    mixed,
  }
}

function normalizeProfile(data) {
  const info = data?.my_info || data?.user || data || {}
  return {
    id: info.id ? String(info.id) : '',
    nickname: info.nickname || info.name || '',
    douyin_id: info.douyin_id || '',
    is_vip: Boolean(info.is_vip),
    vip_stage: info.vip_stage || '',
    raw: data,
  }
}

function normalizeMixedCollections(data) {
  const collections = Array.isArray(data?.mixed_collections) ? data.mixed_collections : []
  return collections
    .map((item) => ({
      item_type: item.item_type || '',
      playlist: normalizePlaylist(item.playlist),
      track: normalizeTrack(item.track || item.media?.track),
      video: normalizeVideo(item.video || item.media?.video),
      raw: item,
    }))
    .filter((item) => item.playlist || item.track || item.video)
}

module.exports = {
  pickUrl,
  normalizeTrack,
  normalizeFeedItem,
  normalizePlaylist,
  normalizePlaylistDetail,
  normalizeVideo,
  normalizeMixedResource,
  normalizeSearchGroups,
  normalizeProfile,
  normalizeMixedCollections,
  extractPlaylistsFromDiscoverMix,
  extractRadiosFromDiscover,
}
