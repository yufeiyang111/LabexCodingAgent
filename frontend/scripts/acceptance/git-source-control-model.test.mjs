import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildCloneCommand,
  diffToLines,
  formatCommitTime,
  groupCommitsByDay,
  statusClass,
  statusLabel
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

test('diffToLines 区分文件头与真实增删行', () => {
  const diff = [
    'diff --git a/a.txt b/a.txt',
    'index 111..222 100644',
    '--- a/a.txt',
    '+++ b/a.txt',
    '@@ -1,2 +1,2 @@',
    '-old line',
    '+new line',
    ' context line'
  ].join('\n')

  const lines = diffToLines(diff)

  assert.equal(lines[0].type, 'meta', 'diff 行是文件头')
  assert.equal(lines[2].type, 'meta', '--- 是文件头，不是删除行')
  assert.equal(lines[3].type, 'meta', '+++ 是文件头，不是新增行')
  assert.equal(lines[4].type, 'hunk')
  assert.equal(lines[5].type, 'del')
  assert.equal(lines[6].type, 'add')
  assert.equal(lines[7].type, 'context')
})

test('diffToLines 对空输入返回空数组', () => {
  assert.deepEqual(diffToLines(''), [])
  assert.deepEqual(diffToLines(null), [])
})

test('buildCloneCommand 把令牌放进 URL 用户名位置', () => {
  const command = buildCloneCommand({ url: 'https://labex.example.com/api/git/2.git', username: 'tok123' })

  assert.equal(command, 'git clone https://tok123@labex.example.com/api/git/2.git')
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
