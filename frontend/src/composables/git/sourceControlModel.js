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
 * 把工作区相对路径拆成「文件名 + 目录」两段。
 *
 * VSCode 的源代码管理列表是「文件名在前、目录在后且弱化」：几十个文件里，用户找的是
 * `GlobalExceptionHandler.java` 而不是 `backend/src/main/java/com/labex/common/`。
 * 直接把整条路径铺开会让文件名被挤到截断位置。
 *
 * @returns {{ name: string, dir: string }} dir 为空串表示文件在根目录
 */
export function splitGitPath(path) {
  const normalized = path == null ? '' : String(path).replace(/\\/g, '/')
  if (!normalized) return { name: '', dir: '' }
  const index = normalized.lastIndexOf('/')
  if (index < 0) return { name: normalized, dir: '' }
  return { name: normalized.slice(index + 1), dir: normalized.slice(0, index) }
}

/** 提交信息非空且至少选了一个文件才允许提交。空白字符串不算内容。 */
export function canCommit(message, selectedCount) {
  return typeof message === 'string' && message.trim().length > 0 && Number(selectedCount) > 0
}

/**
 * 从 git 状态条目里挑出默认勾选的路径。
 *
 * VSCode 的默认行为是「点提交就提交全部改动」。这里保持一致：首次加载或提交完成后，
 * 把所有未提交文件设为已选；用户取消勾选后不再自动加回（保持用户的选择）。
 */
export function defaultSelection(entries) {
  if (!Array.isArray(entries)) return []
  return entries.map(entry => entry && entry.path).filter(path => typeof path === 'string' && path)
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
  // 必须带用户名与密码（labex:token@）：如果只有单个 token@host，git 客户端会把 token
  // 当作用户名，并在终端挂起等待输入密码。
  const withToken = token
    ? cloneUrl.url.replace(/^(https?:\/\/)/, `$1labex:${encodeURIComponent(token)}@`)
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
