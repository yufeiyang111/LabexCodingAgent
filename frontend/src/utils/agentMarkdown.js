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
