/** 侧栏会话列表的展示常量与纯函数。 */

/** 折叠状态下默认展示的会话条数，超出部分需点击「展开」查看。 */
export const CONVERSATION_LIST_VISIBLE_LIMIT = 20

const MINUTE_MS = 60 * 1000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS
const RELATIVE_DAY_LIMIT = 30

/** 会话的「最近活跃」时间：优先 updateTime，缺失时退回 createTime。 */
export function conversationActivityTime(conversation) {
  return conversation?.updateTime || conversation?.createTime || null
}

/**
 * 把时间格式化为紧凑的相对时间：刚刚 / N分钟 / N小时 / N天；
 * 超过 30 天显示「M月D日」，跨年显示「YYYY/M/D」。
 */
export function formatRelativeTime(value, now = new Date()) {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (isNaN(date.getTime())) return ''
  const diff = Math.max(0, now.getTime() - date.getTime())
  if (diff < MINUTE_MS) return '刚刚'
  if (diff < HOUR_MS) return `${Math.floor(diff / MINUTE_MS)}分钟`
  if (diff < DAY_MS) return `${Math.floor(diff / HOUR_MS)}小时`
  if (diff < RELATIVE_DAY_LIMIT * DAY_MS) return `${Math.floor(diff / DAY_MS)}天`
  if (date.getFullYear() === now.getFullYear()) return `${date.getMonth() + 1}月${date.getDate()}日`
  return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`
}

/** 完整时间，用作悬停提示。 */
export function formatFullTime(value) {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (isNaN(date.getTime())) return String(value)
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 计算折叠后要展示的会话：前 limit 条；当前会话若落在折叠区，也追加展示，
 * 保证用户始终能看到自己正在使用的会话。
 */
export function selectVisibleConversations(conversations, { limit, expanded, activeId }) {
  const list = Array.isArray(conversations) ? conversations : []
  if (expanded || list.length <= limit) {
    return { visible: list, hiddenCount: 0 }
  }
  const visible = list.slice(0, limit)
  const active = activeId == null ? null : list.slice(limit).find(item => item.conversationId === activeId)
  if (active) visible.push(active)
  return { visible, hiddenCount: list.length - visible.length }
}
