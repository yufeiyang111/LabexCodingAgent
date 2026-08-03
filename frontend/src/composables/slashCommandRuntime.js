/**
 * 解析 slash command 输入；命令所有权以服务端返回的 dispatch 为准。
 */
export function parseSlashCommand(raw) {
  const text = String(raw || '').trim()
  if (!text.startsWith('/')) return null
  const body = text.slice(1).trim()
  if (!body) return null
  const separator = body.search(/\s/)
  if (separator < 0) {
    return { command: body.toLowerCase(), arguments: '' }
  }
  return {
    command: body.slice(0, separator).toLowerCase(),
    arguments: body.slice(separator).trim()
  }
}

/**
 * 将后端目录转换成前端只读投影；不可用命令不能进入“可用指令”。
 */
export function normalizeCommandCatalog(response) {
  const commands = response?.data?.commands ?? response?.commands ?? []
  if (!Array.isArray(commands)) return []
  return commands
    .filter(command => command?.name && command.dispatch !== 'UNAVAILABLE')
    .map(command => ({
      name: String(command.name),
      description: String(command.description || ''),
      category: command.dispatch === 'CLIENT_ACTION' ? '界面' : 'Agent',
      aliases: Array.isArray(command.aliases) ? command.aliases.map(String) : [],
      dispatch: command.dispatch,
      action: command.action || '',
      subtask: Boolean(command.subtask)
    }))
}

/**
 * 执行一次 typed slash command。只有 AGENT_PROMPT 会返回可发送给 Agent 的 prompt。
 */
export async function resolveSlashCommand({
  raw,
  runCommand,
  clientActions = {},
  requestContext = {}
}) {
  const parsed = parseSlashCommand(raw)
  if (!parsed) throw new Error('请输入有效的 slash command')
  if (typeof runCommand !== 'function') throw new Error('命令解析服务不可用')

  const response = await runCommand({
    ...requestContext,
    command: parsed.command,
    message: String(raw).trim()
  })
  const command = response?.data ?? response ?? {}
  if (!command.success) {
    throw new Error(command.message || `指令 /${parsed.command} 执行失败`)
  }

  if (command.dispatch === 'CLIENT_ACTION') {
    const handler = clientActions[command.action]
    if (typeof handler !== 'function') {
      throw new Error(`客户端动作 ${command.action || '(missing)'} 尚未注册`)
    }
    await handler({
      command: command.command || parsed.command,
      arguments: command.arguments ?? parsed.arguments,
      description: command.description || ''
    })
    return { kind: 'handled', command: command.command || parsed.command }
  }

  if (command.dispatch === 'AGENT_PROMPT') {
    const prompt = String(command.template || '').trim()
    if (!prompt) throw new Error(`指令 /${command.command || parsed.command} 未生成有效 Agent prompt`)
    return {
      kind: 'agent_prompt',
      command: command.command || parsed.command,
      prompt,
      subtask: Boolean(command.subtask),
      description: command.description || ''
    }
  }

  throw new Error(command.message || `指令 /${command.command || parsed.command} 没有可执行分发路径`)
}