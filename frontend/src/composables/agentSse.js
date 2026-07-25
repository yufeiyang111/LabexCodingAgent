export async function consumeAgentSse(body, onEvent) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() || ''
    frames.forEach(frame => dispatchFrame(frame, onEvent))
  }

  buffer += decoder.decode()
  if (buffer.trim()) {
    dispatchFrame(buffer, onEvent)
  }
}

function dispatchFrame(frame, onEvent) {
  let eventId = null
  const dataLines = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('id:')) {
      eventId = line.substring(3).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.substring(5).trimStart())
    }
  }
  const data = dataLines.join('\n').trim()
  if (!data) return

  try {
    const event = JSON.parse(data)
    if (event && typeof event === 'object' && !Array.isArray(event) && eventId) {
      event.eventId = eventId
    }
    onEvent(event)
  } catch (error) {
    console.warn('Agent SSE parse error:', error)
  }
}
