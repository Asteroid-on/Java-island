const crypto = require('crypto')
const { ValidationError } = require('./errors')

const encryptedBoxTypes = new Set(['senc', 'saio', 'saiz', 'sinf', 'schi', 'tenc', 'schm', 'frma'])
const containerBoxTypes = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'stsd'])
const maxSampleCount = 200000
const maxChunkCount = 200000
const maxStscEntryCount = 20000

function decodeBase36(codePoint) {
  if (codePoint >= 48 && codePoint <= 57) return codePoint - 48
  if (codePoint >= 97 && codePoint <= 122) return codePoint - 97 + 10
  return 0xFF
}

function bitCount(number) {
  let value = number - ((number >> 1) & 0x55555555)
  value = (value & 0x33333333) + ((value >> 2) & 0x33333333)
  return (((value + (value >> 4)) & 0xF0F0F0F) * 0x1010101) >> 24
}

function decryptSpadeInner(spadeKey) {
  const result = new Uint8Array(spadeKey)
  const buffer = new Uint8Array(2 + spadeKey.length)
  buffer.set([0xFA, 0x55], 0)
  buffer.set(spadeKey, 2)

  for (let index = 0; index < result.length; index += 1) {
    let value = (spadeKey[index] ^ buffer[index]) - bitCount(index) - 21
    while (value < 0) value += 0xFF
    result[index] = value
  }
  return result
}

function decryptSpade(spadeKeyBytes) {
  const spadeKeyLength = spadeKeyBytes.length
  if (spadeKeyLength < 3) return ''
  const paddingLength = (spadeKeyBytes[0] ^ spadeKeyBytes[1] ^ spadeKeyBytes[2]) - 48
  if (spadeKeyLength < paddingLength + 2) return ''
  const innerInput = spadeKeyBytes.slice(1, spadeKeyLength - paddingLength)
  const decodedBuffer = decryptSpadeInner(innerInput)
  if (decodedBuffer.length === 0) return ''
  const skipBytes = decodeBase36(decodedBuffer[0])
  const decodedMessageLength = spadeKeyLength - paddingLength - 2
  const endIndex = 1 + decodedMessageLength - skipBytes
  if (endIndex > decodedBuffer.length) return ''
  return Buffer.from(decodedBuffer.slice(1, endIndex)).toString('utf8')
}

function decryptSpadeA(spadeA) {
  try {
    return decryptSpade(new Uint8Array(Buffer.from(String(spadeA || '').trim(), 'base64')))
  } catch (_) {
    return ''
  }
}

function isHexKey(value) {
  return typeof value === 'string' && /^[a-f0-9]{32}$/i.test(value.trim())
}

function resolveAudioKey(source = {}) {
  const rawHexKey = source.hex_key || source.key
  if (isHexKey(rawHexKey)) return rawHexKey.trim().toLowerCase()

  const rawSpadeA = source.spade_a || source.spadeA
  if (!rawSpadeA) throw new ValidationError('spade_a 或 hex_key 不能为空')
  const hexKey = decryptSpadeA(rawSpadeA).trim()
  if (!isHexKey(hexKey)) throw new ValidationError('spade_a 或 hex_key 无效')
  return hexKey.toLowerCase()
}

function hexToBytes(hex) {
  if (!isHexKey(hex)) throw new ValidationError('hex_key 必须是 32 位十六进制 AES-128 密钥')
  return Buffer.from(hex, 'hex')
}

function asUint8Array(input) {
  if (input instanceof Uint8Array) return input
  if (Buffer.isBuffer(input)) return new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
  if (input instanceof ArrayBuffer) return new Uint8Array(input)
  throw new ValidationError('音频数据必须是 Buffer、Uint8Array 或 ArrayBuffer')
}

function concatUint8Arrays(arrays) {
  const totalLength = arrays.reduce((sum, array) => sum + array.length, 0)
  const result = new Uint8Array(totalLength)
  let offset = 0
  for (const array of arrays) {
    result.set(array, offset)
    offset += array.length
  }
  return result
}

function readUint32BE(fileData, offset) {
  if (!Number.isInteger(offset) || offset < 0 || offset + 4 > fileData.length) {
    throw new ValidationError('音频容器结构无效')
  }
  return new DataView(fileData.buffer, fileData.byteOffset, fileData.byteLength).getUint32(offset, false)
}

function writeUint32BE(value) {
  const output = new Uint8Array(4)
  new DataView(output.buffer).setUint32(0, value, false)
  return output
}

function readAscii(fileData, start, end) {
  return Buffer.from(fileData.subarray(start, end)).toString('ascii')
}

class MP4Box {
  constructor(fileData, offset) {
    this.offset = offset
    this.size = readUint32BE(fileData, offset)
    this.type = readAscii(fileData, offset + 4, offset + 8)
    this.data = fileData.subarray(offset + 8, offset + this.size)
  }

  static findBox(fileData, boxType, offset = 0, end = null) {
    const searchEnd = end === null ? fileData.length : end
    let position = offset
    while (position < searchEnd) {
      if (position + 8 > searchEnd) break
      const boxSize = readUint32BE(fileData, position)
      if (boxSize === 0 || boxSize > searchEnd - position || boxSize < 8) break
      const currentType = readAscii(fileData, position + 4, position + 8)
      if (currentType === boxType) return new MP4Box(fileData, position)
      position += boxSize
    }
    return null
  }
}

function requireBox(box, name) {
  if (!box) throw new ValidationError(`音频容器缺少 ${name} box`)
  return box
}

function parseStsz(stszData) {
  const sampleSize = readUint32BE(stszData, 4)
  const sampleCount = readUint32BE(stszData, 8)
  if (sampleCount > maxSampleCount) throw new ValidationError('音频样本数量超过限制')
  if (sampleSize !== 0) return new Array(sampleCount).fill(sampleSize)
  const requiredLength = 12 + sampleCount * 4
  if (requiredLength > stszData.length) throw new ValidationError('stsz box 样本大小数据不完整')
  const sampleSizes = []
  for (let index = 0; index < sampleCount; index += 1) {
    sampleSizes.push(readUint32BE(stszData, 12 + index * 4))
  }
  return sampleSizes
}

function parseStsc(stscData) {
  const entryCount = readUint32BE(stscData, 4)
  if (entryCount > maxStscEntryCount) throw new ValidationError('stsc entry 数量超过限制')
  const requiredLength = 8 + entryCount * 12
  if (requiredLength > stscData.length) throw new ValidationError('stsc box 数据不完整')
  const entries = []
  for (let index = 0; index < entryCount; index += 1) {
    const baseOffset = 8 + index * 12
    entries.push({
      firstChunk: readUint32BE(stscData, baseOffset),
      samplesPerChunk: readUint32BE(stscData, baseOffset + 4),
      id: readUint32BE(stscData, baseOffset + 8),
    })
  }
  return entries
}

function parseSenc(sencData) {
  const sampleCount = readUint32BE(sencData, 4)
  if (sampleCount > maxSampleCount) throw new ValidationError('senc 样本数量超过限制')
  const requiredLength = 8 + sampleCount * 8
  if (requiredLength > sencData.length) throw new ValidationError('senc box IV 数据不完整')
  const ivs = []
  let position = 8
  for (let index = 0; index < sampleCount; index += 1) {
    const iv = new Uint8Array(16)
    iv.set(sencData.subarray(position, position + 8))
    ivs.push(iv)
    position += 8
  }
  return ivs
}

function calculateChunkOffsets(sampleSizes, stscEntries, chunkCount, baseOffset) {
  const offsets = []
  let currentOffset = baseOffset
  let sampleIndex = 0
  for (let chunkIndex = 1; chunkIndex <= chunkCount; chunkIndex += 1) {
    offsets.push(currentOffset)
    let samplesPerChunk = 0
    for (let entryIndex = 0; entryIndex < stscEntries.length; entryIndex += 1) {
      const currentEntry = stscEntries[entryIndex]
      const nextEntry = stscEntries[entryIndex + 1]
      if (chunkIndex >= currentEntry.firstChunk && (!nextEntry || chunkIndex < nextEntry.firstChunk)) {
        samplesPerChunk = currentEntry.samplesPerChunk
        break
      }
    }
    for (let sampleOffset = 0; sampleOffset < samplesPerChunk; sampleOffset += 1) {
      if (sampleIndex < sampleSizes.length) currentOffset += sampleSizes[sampleIndex]
      sampleIndex += 1
    }
  }
  return offsets
}

function updateStco(stcoData, offsets) {
  const chunkCount = readUint32BE(stcoData, 4)
  if (offsets.length < chunkCount) throw new ValidationError('stco 偏移数量不足')
  const header = stcoData.subarray(0, 8)
  const body = new Uint8Array(chunkCount * 4)
  const view = new DataView(body.buffer)
  for (let index = 0; index < chunkCount; index += 1) view.setUint32(index * 4, offsets[index], false)
  return concatUint8Arrays([header, body])
}

function processBoxTree(fileData, offset, size, newMdatOffset, context) {
  const parts = []
  let position = offset + 8
  const end = offset + size

  while (position < end) {
    if (position + 8 > end) {
      parts.push(fileData.subarray(position, end))
      break
    }
    const boxSize = readUint32BE(fileData, position)
    if (boxSize < 8 || boxSize > end - position) {
      parts.push(fileData.subarray(position, end))
      break
    }
    const type = readAscii(fileData, position + 4, position + 8)

    if (encryptedBoxTypes.has(type)) {
      position += boxSize
      continue
    }

    if (type === 'enca') {
      const inner = processBoxTree(fileData, position, boxSize, newMdatOffset, context)
      parts.push(writeUint32BE(inner.length + 8), Buffer.from('mp4a'), inner)
      position += boxSize
      continue
    }

    if (type === 'stco') {
      const offsets = calculateChunkOffsets(context.sampleSizes, context.stscEntries, context.chunkCount, newMdatOffset)
      const updatedBody = updateStco(fileData.subarray(position + 8, position + boxSize), offsets)
      parts.push(writeUint32BE(updatedBody.length + 8), Buffer.from('stco'), updatedBody)
      position += boxSize
      continue
    }

    if (containerBoxTypes.has(type)) {
      const inner = processBoxTree(fileData, position, boxSize, newMdatOffset, context)
      parts.push(writeUint32BE(inner.length + 8), Buffer.from(type), inner)
      position += boxSize
      continue
    }

    parts.push(fileData.subarray(position, position + boxSize))
    position += boxSize
  }

  return concatUint8Arrays(parts)
}

function scanForFlacMetadata(stsdData) {
  const marker = Buffer.from('dfLa')
  for (let index = 4; index < stsdData.length - 4; index += 1) {
    if (
      stsdData[index] === marker[0]
      && stsdData[index + 1] === marker[1]
      && stsdData[index + 2] === marker[2]
      && stsdData[index + 3] === marker[3]
    ) {
      const boxSize = readUint32BE(stsdData, index - 4)
      if (boxSize >= 8 && index - 4 + boxSize <= stsdData.length) {
        return stsdData.subarray(index + 4, index - 4 + boxSize)
      }
    }
  }
  return null
}

function decryptCtr(encryptedSample, keyBytes, iv) {
  const decipher = crypto.createDecipheriv(`aes-${keyBytes.length * 8}-ctr`, keyBytes, Buffer.from(iv))
  decipher.setAutoPadding(false)
  return Buffer.concat([decipher.update(Buffer.from(encryptedSample)), decipher.final()])
}

function decryptAudioBuffer(input, hexKey) {
  const fileData = asUint8Array(input)
  const keyBytes = hexToBytes(hexKey)

  const ftyp = MP4Box.findBox(fileData, 'ftyp')
  const moov = requireBox(MP4Box.findBox(fileData, 'moov'), 'moov')
  const trak = requireBox(MP4Box.findBox(fileData, 'trak', moov.offset + 8, moov.offset + moov.size), 'trak')
  const mdia = requireBox(MP4Box.findBox(fileData, 'mdia', trak.offset + 8, trak.offset + trak.size), 'mdia')
  const minf = requireBox(MP4Box.findBox(fileData, 'minf', mdia.offset + 8, mdia.offset + mdia.size), 'minf')
  const stbl = requireBox(MP4Box.findBox(fileData, 'stbl', minf.offset + 8, minf.offset + minf.size), 'stbl')
  const stsd = requireBox(MP4Box.findBox(fileData, 'stsd', stbl.offset + 8, stbl.offset + stbl.size), 'stsd')
  const stsz = requireBox(MP4Box.findBox(fileData, 'stsz', stbl.offset + 8, stbl.offset + stbl.size), 'stsz')
  const stsc = requireBox(MP4Box.findBox(fileData, 'stsc', stbl.offset + 8, stbl.offset + stbl.size), 'stsc')
  const stco = requireBox(MP4Box.findBox(fileData, 'stco', stbl.offset + 8, stbl.offset + stbl.size), 'stco')
  const mdat = requireBox(MP4Box.findBox(fileData, 'mdat'), 'mdat')

  let senc = MP4Box.findBox(fileData, 'senc', stbl.offset + 8, stbl.offset + stbl.size)
  if (!senc) senc = MP4Box.findBox(fileData, 'senc', moov.offset + 8, moov.offset + moov.size)
  requireBox(senc, 'senc')

  const sampleSizes = parseStsz(stsz.data)
  const stscEntries = parseStsc(stsc.data)
  const chunkCount = readUint32BE(stco.data, 4)
  if (chunkCount > maxChunkCount) throw new ValidationError('音频 chunk 数量超过限制')
  if (8 + chunkCount * 4 > stco.data.length) throw new ValidationError('stco box 数据不完整')
  const ivs = parseSenc(senc.data)
  if (ivs.length < sampleSizes.length) throw new ValidationError('senc IV 数量少于样本数量')
  const encryptedPayloadSize = sampleSizes.reduce((sum, sampleSize) => sum + sampleSize, 0)
  if (encryptedPayloadSize > mdat.size - 8) throw new ValidationError('样本总大小超过 mdat 数据范围')

  const decryptedSamples = []
  let sampleOffset = mdat.offset + 8
  for (let index = 0; index < sampleSizes.length; index += 1) {
    const sampleSize = sampleSizes[index]
    if (sampleOffset + sampleSize > fileData.length) throw new ValidationError('音频样本数据不完整')
    decryptedSamples.push(decryptCtr(fileData.subarray(sampleOffset, sampleOffset + sampleSize), keyBytes, ivs[index]))
    sampleOffset += sampleSize
  }

  const flacMetadata = scanForFlacMetadata(stsd.data)
  if (flacMetadata) {
    const metadataStart = flacMetadata.length > 4 ? 4 : 0
    const outputBuffer = Buffer.from(concatUint8Arrays([Buffer.from('fLaC'), flacMetadata.subarray(metadataStart), ...decryptedSamples]))
    return {
      buffer: outputBuffer,
      extension: '.flac',
      content_type: 'audio/flac',
      codec: 'flac',
      sample_count: sampleSizes.length,
    }
  }

  const context = { sampleSizes, stscEntries, chunkCount }
  const ftypSize = ftyp ? ftyp.size : 0
  const dummyMoov = processBoxTree(fileData, moov.offset, moov.size, 0, context)
  const newMdatOffset = ftypSize + dummyMoov.length + 16
  const cleanMoovData = processBoxTree(fileData, moov.offset, moov.size, newMdatOffset, context)
  const cleanMoov = concatUint8Arrays([writeUint32BE(cleanMoovData.length + 8), Buffer.from('moov'), cleanMoovData])
  const mdatData = concatUint8Arrays(decryptedSamples)
  const newMdat = concatUint8Arrays([writeUint32BE(mdatData.length + 8), Buffer.from('mdat'), mdatData])
  const finalParts = []
  if (ftyp) finalParts.push(fileData.subarray(ftyp.offset, ftyp.offset + ftyp.size))
  finalParts.push(cleanMoov, newMdat)

  return {
    buffer: Buffer.from(concatUint8Arrays(finalParts)),
    extension: '.m4a',
    content_type: 'audio/mp4',
    codec: 'mp4',
    sample_count: sampleSizes.length,
  }
}

module.exports = {
  decryptSpadeA,
  isHexKey,
  resolveAudioKey,
  decryptAudioBuffer,
  scanForFlacMetadata,
  MP4Box,
}
