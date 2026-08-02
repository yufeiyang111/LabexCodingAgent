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
 * Internal reasoning delimiters can be raw, HTML-escaped, attributed, mixed-case, or split across events.
 * Backend normalization is authoritative; this scanner is the replay and rendering defense in depth.
 */
const INTERNAL_REASONING_TAG_PATTERN = /<\s*(\/?)\s*think(?:ing)?(?:\s+[^<>]*?)?\s*>|&lt;\s*(\/?)\s*think(?:ing)?(?:\s+.*?)?\s*&gt;|&#(?:0*60|x0*3c);\s*(\/?)\s*think(?:ing)?(?:\s+.*?)?\s*&#(?:0*62|x0*3e);/i

function findInternalReasoningTag(value) {
  const match = INTERNAL_REASONING_TAG_PATTERN.exec(value)
  if (!match) return null
  return {
    index: match.index,
    length: match[0].length,
    closing: match.slice(1, 4).some(group => group === '/')
  }
}

function couldBeRawReasoningPrefix(value) {
  if (!value.startsWith('<')) return false
  let index = 1
  while (index < value.length && /\s/.test(value[index])) index += 1
  if (index === value.length) return true
  if (value[index] === '/') {
    index += 1
    while (index < value.length && /\s/.test(value[index])) index += 1
    if (index === value.length) return true
  }
  const start = index
  while (index < value.length && !/[\s>&]/.test(value[index])) index += 1
  const word = value.slice(start, index)
  if (!word) return true
  return 'think'.startsWith(word) || 'thinking'.startsWith(word) || word === 'think' || word === 'thinking'
}

function couldBeInternalReasoningPrefix(value) {
  const normalized = value.toLowerCase()
  if ('&lt;'.startsWith(normalized) || '&#60;'.startsWith(normalized) || '&#x3c;'.startsWith(normalized)) return true
  if (normalized.startsWith('&lt;')) return couldBeRawReasoningPrefix(`<${normalized.slice(4)}`)
  if (normalized.startsWith('&#60;')) return couldBeRawReasoningPrefix(`<${normalized.slice(5)}`)
  if (normalized.startsWith('&#x3c;')) return couldBeRawReasoningPrefix(`<${normalized.slice(6)}`)
  return couldBeRawReasoningPrefix(normalized)
}

function safeInternalReasoningLength(value) {
  for (let index = 0; index < value.length; index += 1) {
    if ((value[index] === '<' || value[index] === '&') && couldBeInternalReasoningPrefix(value.slice(index))) {
      return index
    }
  }
  return value.length
}

/** 删除独立思考通道中的协议标签，但保留思考文本。 */
export function createInternalReasoningTagStreamFilter() {
  let buffer = ''

  function process() {
    let output = ''
    while (buffer) {
      const match = findInternalReasoningTag(buffer)
      if (match) {
        output += buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match.length)
        continue
      }
      const safeLength = safeInternalReasoningLength(buffer)
      output += buffer.slice(0, safeLength)
      buffer = buffer.slice(safeLength)
      break
    }
    return output
  }

  return {
    push(value) {
      buffer += String(value ?? '')
      return process()
    },
    flush() {
      const output = process()
      const safeLength = safeInternalReasoningLength(buffer)
      const tail = buffer.slice(0, safeLength)
      buffer = ''
      return output + tail
    },
    reset() {
      buffer = ''
    }
  }
}

/** 删除最终回答中的完整思考块，并跨事件保留不完整协议前缀。 */
export function createInternalReasoningBlockStreamFilter() {
  let buffer = ''
  let insideInternalBlock = false

  function process() {
    let visible = ''
    while (buffer) {
      const match = findInternalReasoningTag(buffer)
      if (match) {
        if (!insideInternalBlock) visible += buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match.length)
        insideInternalBlock = match.closing ? false : true
        continue
      }
      const safeLength = safeInternalReasoningLength(buffer)
      if (!insideInternalBlock) visible += buffer.slice(0, safeLength)
      buffer = buffer.slice(safeLength)
      break
    }
    return visible
  }

  return {
    push(value) {
      buffer += String(value ?? '')
      return process()
    },
    flush() {
      const visible = process()
      const safeLength = safeInternalReasoningLength(buffer)
      const tail = insideInternalBlock ? '' : buffer.slice(0, safeLength)
      buffer = ''
      insideInternalBlock = false
      return visible + tail
    },
    reset() {
      buffer = ''
      insideInternalBlock = false
    }
  }
}

export function stripInternalReasoningTags(value) {
  const filter = createInternalReasoningTagStreamFilter()
  return filter.push(value) + filter.flush()
}

export function stripInternalReasoningBlocks(value) {
  const filter = createInternalReasoningBlockStreamFilter()
  return filter.push(value) + filter.flush()
}
