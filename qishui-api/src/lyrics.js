function isMetadataLine(text) {
  const value = String(text || '').trim()
  if (!value) return true
  if (/^(作词|作曲|编曲|制作人|监制|混音|母带|录音|演唱|歌手|艺人)[:：]/.test(value)) return true
  if (/^[一-龥]{2,4}$/.test(value)) return true
  if (/^[\s\-—.*+=|\[\]（）(){}]*$/.test(value)) return true
  if (value === '翻译' || value === '歌词') return true
  return /(歌词|滚动歌词|翻译)?贡献者/.test(value)
}

function formatLrcTime(ms) {
  const value = Math.max(0, Number(ms) || 0)
  const minutes = Math.floor(value / 60000)
  const seconds = Math.floor((value % 60000) / 1000)
  const milliseconds = Math.floor(value % 1000)
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(milliseconds).padStart(3, '0')}`
}

function sentencesToLrc(sentences) {
  if (!Array.isArray(sentences)) return ''
  return sentences
    .map((sentence) => {
      if (!sentence || typeof sentence !== 'object') return ''
      const text = sentence.text || (Array.isArray(sentence.words) ? sentence.words.map((word) => word.text || '').join('') : '')
      if (isMetadataLine(text)) return ''
      const startMs = sentence.startMs ?? sentence.startTime ?? 0
      return `[${formatLrcTime(startMs)}]${String(text).trim()}`
    })
    .filter(Boolean)
    .join('\n')
}

function sentencesToTimedLines(sentences) {
  if (!Array.isArray(sentences)) return []
  return sentences
    .map((sentence) => {
      if (!sentence || typeof sentence !== 'object') return null
      const text = sentence.text || (Array.isArray(sentence.words) ? sentence.words.map((word) => word.text || '').join('') : '')
      if (isMetadataLine(text)) return null
      return {
        text: String(text).trim(),
        start_ms: sentence.startMs ?? sentence.startTime ?? 0,
        end_ms: sentence.endMs ?? sentence.endTime ?? 0,
        words: Array.isArray(sentence.words)
          ? sentence.words.map((word) => ({
              text: word.text || '',
              start_ms: word.startMs ?? word.startTime ?? 0,
              end_ms: word.endMs ?? word.endTime ?? 0,
            }))
          : [],
      }
    })
    .filter(Boolean)
}

module.exports = {
  isMetadataLine,
  sentencesToLrc,
  sentencesToTimedLines,
}
