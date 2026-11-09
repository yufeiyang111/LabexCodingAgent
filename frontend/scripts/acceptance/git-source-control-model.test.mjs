import assert from 'node:assert/strict'
import test from 'node:test'

import {
  BRANCH_NAME,
  buildCloneCommand,
  canCommit,
  defaultSelection,
  formatCommitTime,
  groupCommitsByDay,
  splitGitPath,
  statusClass,
  statusLabel,
  statusText
} from '../../src/composables/git/sourceControlModel.js'

test('statusLabel 把未跟踪显示成 U，其余保留字母', () => {
  assert.equal(statusLabel('??'), 'U')
  assert.equal(statusLabel('A'), 'A')
  assert.equal(statusLabel('M'), 'M')
  assert.equal(statusLabel('D'), 'D')
  assert.equal(statusLabel('R'), 'R')
})

test('statusClass 给每种状态一个颜色类', () => {
  assert.equal(statusClass('A'), 'added')
  assert.equal(statusClass('M'), 'modified')
  assert.equal(statusClass('D'), 'deleted')
  assert.equal(statusClass('R'), 'renamed')
  assert.equal(statusClass('??'), 'untracked')
})

test('buildCloneCommand 把令牌放进 URL 凭据位置（labex:token@）避免客户端挂起等待密码', () => {
  const command = buildCloneCommand({ url: 'https://labex.example.com/api/git/2.git', username: 'tok123' })

  assert.equal(command, 'git clone https://labex:tok123@labex.example.com/api/git/2.git')
})

test('buildCloneCommand 没有令牌时不拼 @', () => {
  assert.equal(buildCloneCommand({ url: 'https://x/y.git', username: '' }), 'git clone https://x/y.git')
  assert.equal(buildCloneCommand(null), '')
})

test('groupCommitsByDay 把提交按今天/昨天/日期分组', () => {
  const now = new Date('2026-11-06T12:00:00+08:00')
  const commits = [
    { sha: 'c3', committedAt: '2026-11-06T09:00:00+08:00' },
    { sha: 'c2', committedAt: '2026-11-05T09:00:00+08:00' },
    { sha: 'c1', committedAt: '2026-10-20T09:00:00+08:00' }
  ]

  const groups = groupCommitsByDay(commits, now)

  assert.deepEqual(groups.map(group => group.label), ['今天', '昨天', '2026-10-20'])
  assert.equal(groups[0].commits[0].sha, 'c3')
})

test('formatCommitTime 只输出时分', () => {
  const formatted = formatCommitTime('2026-11-06T09:05:00+08:00')
  assert.match(formatted, /^\d{2}:\d{2}$/)
})

test('formatCommitTime 对无效输入返回空串', () => {
  assert.equal(formatCommitTime(''), '')
  assert.equal(formatCommitTime('not-a-date'), '')
})

test('splitGitPath 把文件名与目录分开，供列表弱化目录', () => {
  assert.deepEqual(splitGitPath('backend/src/main/java/com/labex/common/GlobalExceptionHandler.java'), {
    name: 'GlobalExceptionHandler.java',
    dir: 'backend/src/main/java/com/labex/common'
  })
})

test('splitGitPath 对根目录文件返回空 dir', () => {
  assert.deepEqual(splitGitPath('README.md'), { name: 'README.md', dir: '' })
})

test('splitGitPath 兼容反斜杠并容忍空值', () => {
  assert.deepEqual(splitGitPath('src\\a\\B.java'), { name: 'B.java', dir: 'src/a' })
  assert.deepEqual(splitGitPath(null), { name: '', dir: '' })
  assert.deepEqual(splitGitPath(''), { name: '', dir: '' })
})

test('canCommit 要求提交信息非空且至少选一个文件', () => {
  assert.equal(canCommit('fix: x', 1), true)
  assert.equal(canCommit('   ', 1), false, '纯空白不算提交信息')
  assert.equal(canCommit('fix: x', 0), false, '没选文件不能提交')
  assert.equal(canCommit(null, 3), false)
})

test('defaultSelection 默认全选未提交文件（对齐 VSCode 的一点即提交）', () => {
  const entries = [{ path: 'a.txt' }, { path: 'src/b.js' }, { path: '' }, null]

  assert.deepEqual(defaultSelection(entries), ['a.txt', 'src/b.js'])
  assert.deepEqual(defaultSelection(null), [])
})

test('statusText 给状态方块的悬停提示中文全称', () => {
  assert.equal(statusText('A'), '新增')
  assert.equal(statusText('M'), '修改')
  assert.equal(statusText('D'), '删除')
  assert.equal(statusText('??'), '未跟踪')
  assert.equal(statusText('未知'), '修改', '未知字母退回「修改」，不显示空提示')
})

test('BRANCH_NAME 与仓库初始化的 --initial-branch 一致', () => {
  // 分支名由 ProjectGitRepository 初始化时固定为 master；面板上的 chip 读同一常量，
  // 两边写死不同的值时 chip 会显示一个并不存在的分支。
  assert.equal(BRANCH_NAME, 'master')
})
