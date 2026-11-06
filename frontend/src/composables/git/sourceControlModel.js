/**
 * 源码管理面板的纯展示逻辑：状态字母、diff 行分类、clone 命令拼装。
 *
 * 单独成模块的原因：这些是唯一值得单测的部分（组件本身是模板 + 事件）。
 * 放在组件里就只能靠浏览器测，而它们的输入输出完全确定。
 */

const STATUS_LABELS = {
  A: 'A',
  M: 'M',
  D: 'D',
  R: 'R',
  C: 'C',
  U: 'U',
  '??': 'U'
}

const STATUS_CLASSES = {
  A: 'added',
  M: 'modified',
  D: 'deleted',
  R: 'renamed',
  C: 'renamed',
  U: 'untracked',
  '??': 'untracked'
}

/** git 状态字母 → 面板上显示的单个字符。未跟踪用 U，和 VSCode 一致。 */
export function statusLabel(status) {
  return STATUS_LABELS[status] || (status ? status.charAt(0) : 'M')
}

/** git 状态字母 → CSS 类名后缀，决定颜色。 */
export function statusClass(status) {
  return STATUS_CLASSES[status] || 'modified'
}

/**
 * 把统一 diff 文本切成带类型的行。
 *
 * 类型：add / del / hunk / meta / context。`+++` / `---` 是文件头，归 meta 而不是 add/del，
 * 否则文件头会被染成增删色，看起来像内容变了。
 */
export function diffToLines(diff) {
  if (!diff) return []
  return diff.split('\n').map(text => {
    if (text.startsWith('+++') || text.startsWith('---')) return { type: 'meta', text }
    if (text.startsWith('@@')) return { type: 'hunk', text }
    if (text.startsWith('diff ') || text.startsWith('index ') || text.startsWith('new file')
      || text.startsWith('deleted file') || text.startsWith('similarity index')
      || text.startsWith('rename ') || text.startsWith('Binary files')) {
      return { type: 'meta', text }
    }
    if (text.startsWith('+')) return { type: 'add', text }
    if (text.startsWith('-')) return { type: 'del', text }
    return { type: 'context', text }
  })
}

/**
 * 拼出可以直接粘进终端的 clone 命令。
 *
 * 令牌放在 URL 的用户名位置而不是密码位置：git 对 `user:pass@host` 两者都支持，
 * 但只有密码位置会出现在部分客户端的凭据缓存里，放用户名位置泄露面更小。
 */
export function buildCloneCommand(cloneUrl) {
  if (!cloneUrl || !cloneUrl.url) return ''
  const token = cloneUrl.username || ''
  const withToken = token
    ? cloneUrl.url.replace(/^(https?:\/\/)/, `$1${encodeURIComponent(token)}@`)
    : cloneUrl.url
  return `git clone ${withToken}`
}

/**
 * 把提交列表按日期分组，供历史面板渲染。
 * 返回 [{ label, commits }]，label 形如「今天」「昨天」「2026-08-20」。
 */
export function groupCommitsByDay(commits, now = new Date()) {
  const today = startOfDay(now)
  const groups = new Map()
  for (const commit of commits || []) {
    const label = dayLabel(commit.committedAt, today)
    if (!groups.has(label)) groups.set(label, [])
    groups.get(label).push(commit)
  }
  return [...groups.entries()].map(([label, items]) => ({ label, commits: items }))
}
function dayLabel(value, todayMillis) {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return '未知日期'
  const day = startOfDay(date)
  const diffDays = Math.round((todayMillis - day) / 86_400_000)
  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  const label = new Date(day)
  return `${label.getFullYear()}-${pad(label.getMonth() + 1)}-${pad(label.getDate())}`
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

function pad(value) {
  return String(value).padStart(2, '0')
}

/** 提交时间的展示形式：只显示时分，日期由分组标题承担。 */
export function formatCommitTime(value) {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return ''
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}
