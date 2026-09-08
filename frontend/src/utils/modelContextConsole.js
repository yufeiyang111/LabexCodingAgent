/**
 * 前端大模型上下文观测与控制台调试工具
 * 专门用于在浏览器开发者工具（F12 Console）以格式化、结构化形式输出发送给大模型的组装后上下文，
 * 并支持通过 window.__LABEX_LAST_CONTEXT__ 或 window.__showLastContext() 快捷观测。
 */

export function formatModelContextSummary(snapshot) {
  if (!snapshot) return ''
  const taskId = snapshot.taskId ?? '?'
  const iteration = snapshot.iteration ?? '1'
  const model = snapshot.model || 'Unknown Model'
  const count = snapshot.messages?.length ?? (Array.isArray(snapshot.messages) ? snapshot.messages.length : 0)
  const toolCount = snapshot.tools?.length ?? 0
  return `[LabexAgent Context] 🚀 发送给大模型的组装上下文 · Task #${taskId} · 第 ${iteration} 轮 · ${model} (${count} 条消息 / ${toolCount} 个工具)`
}

export function printModelContextToConsole(snapshot) {
  if (typeof console === 'undefined' || !snapshot) return

  // 挂载全局便捷调试变量与函数
  if (typeof window !== 'undefined') {
    try {
      window.__LABEX_LAST_CONTEXT__ = snapshot
      window.__LABEX_CONTEXT_HISTORY__ = window.__LABEX_CONTEXT_HISTORY__ || []
      window.__LABEX_CONTEXT_HISTORY__.push(snapshot)
      if (!window.__showLastContext) {
        window.__showLastContext = () => printModelContextToConsole(window.__LABEX_LAST_CONTEXT__)
      }
      if (!window.__copyLastContext) {
        window.__copyLastContext = () => {
          if (navigator?.clipboard && window.__LABEX_LAST_CONTEXT__) {
            navigator.clipboard.writeText(JSON.stringify(window.__LABEX_LAST_CONTEXT__, null, 2))
            console.log('%c[LabexAgent] 剪贴板已复制最近一次大模型组装上下文 JSON！', 'color: #16a34a; font-weight: bold;')
          }
        }
      }
    } catch {
      // 忽略非浏览器或沙箱环境异常
    }
  }

  const summary = formatModelContextSummary(snapshot)
  const badgeStyle = 'background: #1d4ed8; color: #ffffff; font-weight: bold; font-size: 11px; padding: 2px 7px; border-radius: 4px;'
  const labelStyle = 'font-weight: bold; color: #2563eb;'
  const fileStyle = 'font-weight: bold; color: #059669; font-family: monospace;'
  const subtleStyle = 'color: #64748b; font-size: 11px;'

  if (console.groupCollapsed) {
    console.groupCollapsed(`%c${summary}`, badgeStyle)
  } else {
    console.log(summary)
  }

  if (snapshot.filePath) {
    console.log('%c📁 工作区保存路径:', labelStyle, `%c${snapshot.filePath}`, fileStyle)
  }

  console.log('%c⚙️ 运行时调用参数:', labelStyle, {
    taskId: snapshot.taskId,
    iteration: snapshot.iteration,
    conversationId: snapshot.conversationId,
    model: snapshot.model,
    provider: snapshot.provider,
    estimatedTokens: snapshot.estimatedTokens,
    timestamp: snapshot.timestamp
  })

  console.log('%c📝 System Prompt (组装后系统提示词):', labelStyle)
  console.log(snapshot.systemPrompt || '(无系统提示词)')

  console.log(
    `%c💬 Assembled Messages (发送给大模型的上下文消息列表 · 共 ${snapshot.messages?.length || 0} 条):`,
    labelStyle,
    snapshot.messages || []
  )

  if (snapshot.tools && snapshot.tools.length > 0) {
    console.log(
      `%c🛠️ Tools (注入给大模型的工具函数与 JSON Schema · 共 ${snapshot.tools.length} 项):`,
      labelStyle,
      snapshot.tools
    )
  }

  console.log('%c🔍 完整上下文快照 (Raw Context Snapshot):', labelStyle, snapshot)
  console.log('%c💡 调试小贴士: 可直接在控制台输入 __LABEX_LAST_CONTEXT__ 查看原始对象，或执行 __copyLastContext() 复制完整 JSON。文件亦同步归档至工作区 .labex/context-history/ 目录。', subtleStyle)

  if (console.groupEnd) {
    console.groupEnd()
  }
}
