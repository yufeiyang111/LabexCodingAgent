const CALLOUT_TYPES = new Set(['note', 'tip', 'success', 'warning', 'important', 'error'])

function isFence(line) {
  return /^\s*(```+|~~~+)/.test(line)
}

/**
 * 将受控的 ::: 提示块转换为安全的 Markdown 引用。
 * 代码围栏内的指令保持原样。
 */
export function normalizeSpecialMarkdownBlocks(text) {
  if (!text) return ''

  const lines = String(text).split('\n')
  const output = []
  let inFence = false

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (isFence(line)) {
      inFence = !inFence
      output.push(line)
      continue
    }

    const start = !inFence && line.match(/^\s*:::(note|tip|success|warning|important|error)\s*$/i)
    if (!start || !CALLOUT_TYPES.has(start[1].toLowerCase())) {
      output.push(line)
      continue
    }

    const type = start[1].toUpperCase()
    const body = []
    let cursor = index + 1
    let bodyInFence = false
    let closed = false
    for (; cursor < lines.length; cursor += 1) {
      const candidate = lines[cursor]
      if (isFence(candidate)) bodyInFence = !bodyInFence
      if (!bodyInFence && /^\s*:::\s*$/.test(candidate)) {
        closed = true
        break
      }
      body.push(candidate)
    }

    if (!closed) {
      output.push(line, ...body)
      index = cursor - 1
      continue
    }

    output.push(`> [!${type}]`, ...body.map(item => item ? `> ${item}` : '>'))
    index = cursor
  }

  return output.join('\n')
}


/**
 * Removes internal reasoning protocol delimiters leaked by a provider or old persisted events.
 * Server-side stream parsing is authoritative; this is the UI and replay defense in depth.
 */
export function stripInternalReasoningTags(value) {
  return String(value ?? '')
    .replace(/<\/?think(?:ing)?\s*>/gi, '')
    .replace(/<\/?(?:t|th|thi|thin|think|thinki|thinkin|thinking)?$/i, '')
}

/**
 * Final answers must not display raw reasoning enclosed by internal delimiters.
 * Complete blocks are removed; partial stream prefixes are handled by stripInternalReasoningTags.
 */
export function stripInternalReasoningBlocks(value) {
  return stripInternalReasoningTags(String(value ?? '')
    .replace(/<think(?:ing)?\s*>[\s\S]*?<\/think(?:ing)?\s*>/gi, ''))
}

const INTERNAL_REASONING_OPEN_TAGS = ['<thinking>', '<think>']
const INTERNAL_REASONING_CLOSE_TAGS = ['</thinking>', '</think>']

function findInternalReasoningTag(value, tags) {
  const normalized = value.toLowerCase()
  let selected = null
  for (const tag of tags) {
    const index = normalized.indexOf(tag)
    if (index >= 0 && (!selected || index < selected.index || (index === selected.index && tag.length > selected.tag.length))) {
      selected = { index, tag }
    }
  }
  return selected
}

function safeInternalReasoningPrefixLength(value, tags) {
  const normalized = value.toLowerCase()
  const maximum = Math.max(...tags.map(tag => tag.length - 1))
  for (let length = Math.min(maximum, normalized.length); length > 0; length -= 1) {
    const suffix = normalized.slice(-length)
    if (tags.some(tag => tag.startsWith(suffix))) return normalized.length - length
  }
  return normalized.length
}

/**
 * Stateful final-output filter. It prevents an unfinished internal reasoning block from being
 * rendered between streaming deltas, while still preserving normal visible text around it.
 */
export function createInternalReasoningBlockStreamFilter() {
  let buffer = ''
  let insideInternalBlock = false

  return {
    push(value) {
      buffer += String(value ?? '')
      let visible = ''
      while (buffer) {
        const tags = insideInternalBlock ? INTERNAL_REASONING_CLOSE_TAGS : INTERNAL_REASONING_OPEN_TAGS
        const match = findInternalReasoningTag(buffer, tags)
        if (match) {
          if (!insideInternalBlock) visible += buffer.slice(0, match.index)
          buffer = buffer.slice(match.index + match.tag.length)
          insideInternalBlock = !insideInternalBlock
          continue
        }
        const safeLength = safeInternalReasoningPrefixLength(buffer, tags)
        if (!insideInternalBlock && safeLength > 0) visible += buffer.slice(0, safeLength)
        buffer = buffer.slice(safeLength)
        break
      }
      return visible
    },
    reset() {
      buffer = ''
      insideInternalBlock = false
    }
  }
}
