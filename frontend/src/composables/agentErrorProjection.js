export function projectVisibleAgentError(content, message) {
  const current = typeof content === 'string' ? content : ''
  const detail = typeof message === 'string' && message.trim() ? message.trim() : '\u6a21\u578b\u670d\u52a1\u8c03\u7528\u5931\u8d25'
  const visible = `\u9519\u8bef\uff1a${detail}`
  if (!current.trim()) return visible
  if (current.includes(visible)) return current
  return `${current.trimEnd()}\n\n${visible}`
}
