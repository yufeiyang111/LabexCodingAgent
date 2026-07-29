function normalizeLineEndings(text) {
  return String(text ?? '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

function unwrapEnvelope(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value
  if (value.code === 0 && value.data && typeof value.data === 'object' && !Array.isArray(value.data)) {
    return value.data
  }
  return value
}

export function parseSse(text, options = {}) {
  const onMalformed = typeof options.onMalformed === 'function' ? options.onMalformed : () => {}
  const normalized = normalizeLineEndings(text)
  const frames = normalized.split(/\n\n+/)
  const events = []

  for (const frame of frames) {
    if (!frame.trim()) continue
    let eventId = null
    let sseEvent = null
    const dataLines = []

    for (const line of frame.split('\n')) {
      if (!line || line.startsWith(':')) continue
      const separator = line.indexOf(':')
      const field = separator < 0 ? line : line.slice(0, separator)
      let value = separator < 0 ? '' : line.slice(separator + 1)
      if (value.startsWith(' ')) value = value.slice(1)

      if (field === 'id') eventId = value
      else if (field === 'event') sseEvent = value
      else if (field === 'data') dataLines.push(value)
    }

    if (dataLines.length === 0) continue
    const raw = dataLines.join('\n')
    try {
      const parsed = unwrapEnvelope(JSON.parse(raw))
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new TypeError('SSE JSON payload must be an object')
      }
      const event = { ...parsed }
      if (eventId !== null && eventId !== '') event.eventId = eventId
      if (sseEvent) event.sseEvent = sseEvent
      events.push(event)
    } catch (error) {
      onMalformed({
        eventId,
        sseEvent,
        raw,
        message: `Invalid SSE JSON frame: ${error instanceof Error ? error.message : String(error)}`
      })
    }
  }

  return events
}
