import assert from 'node:assert/strict'
import test from 'node:test'

import { CONFLICT_POLICIES, buildSyncPlan } from './changeSetSyncPlan.js'

// 二进制文件（.db / .png / .pkl）在服务端以 UTF-8 文本存放：
// Files.readString(UTF_8) 把非法字节替换成 U+FFFD，再把替换后的字符串存进 longtext。
// 前端若把它当文本写回去，落盘的是 45094 字节的"文本"而不是原来 45056 字节的二进制——
// SQLite 头还在，后面整片错位。这不是理论问题：项目 2 的 backend/blog.db 就是这种行。
//
// 为什么按"跳过"而不是"冲突"：冲突的语义是"用户裁决后能继续"，而这里用户裁决不了——
// 原始字节在服务端落库那一刻就已经丢了，任何策略都写不出正确文件。
// 若判成冲突，strict 策略会永远卡在同一个文件上，游标推不动，整个项目再也同步不了。
// 跳过会进 skipped 列表并在进度面板逐行显示，用户仍然看得见"这个文件没同步"。

const NUL = '\u0000'

function binaryPayload(overrides = {}) {
  return {
    changeSets: [
      {
        changeSetId: 337,
        files: [
          {
            changeId: 'bin-1',
            relativePath: 'backend/blog.db',
            changeType: 'create',
            status: 'applied',
            afterContent: `SQLite format 3${NUL}\u0010${NUL}\u0001\u0001${NUL}@  ${NUL}${NUL}${NUL}\u0006`,
            ...overrides
          }
        ]
      }
    ]
  }
}

test('binary content is never written as text', () => {
  const plan = buildSyncPlan({
    ...binaryPayload(),
    cursor: 0,
    // 空目录：这是"同步到一台新机器"的正常起点，也是唯一会真正写盘的情形。
    probe: { 'backend/blog.db': { state: 'absent', exists: false, content: null } }
  })

  assert.equal(plan.steps.length, 0, 'binary file must not produce a write step')
  assert.equal(plan.conflicts.length, 0, 'a file the user cannot fix is not a conflict')
  assert.equal(plan.skipped.length, 1)
  assert.equal(plan.skipped[0].reason, 'binary-content')
  assert.equal(plan.skipped[0].path, 'backend/blog.db')
})

test('binary skip does not block the batch or stall the cursor', () => {
  const payload = binaryPayload()
  payload.changeSets[0].files.push({
    changeId: 'txt-1',
    relativePath: 'README.md',
    changeType: 'create',
    status: 'applied',
    afterContent: '# hi'
  })

  const plan = buildSyncPlan({
    ...payload,
    cursor: 0,
    probe: {
      'backend/blog.db': { state: 'absent', exists: false, content: null },
      'README.md': { state: 'absent', exists: false, content: null }
    }
  })

  assert.deepEqual(plan.steps.map(step => step.path), ['README.md'])
  assert.equal(plan.blocked, false, 'one unsyncable binary file must not block the whole project')
  assert.equal(plan.nextCursor, 337, 'cursor must advance past the set that holds the binary file')
})

test('overwrite policy does not bypass the binary guard', () => {
  const plan = buildSyncPlan({
    ...binaryPayload(),
    cursor: 0,
    policy: CONFLICT_POLICIES.OVERWRITE,
    probe: { 'backend/blog.db': { state: 'absent', exists: false, content: null } }
  })

  assert.equal(plan.steps.length, 0, 'overwrite means "replace my edits", not "corrupt the file"')
  assert.equal(plan.skipped[0].reason, 'binary-content')
})

test('the verdict depends on the content, not on what is already on disk', () => {
  // 本地那份恰好等于服务端存的有损文本（旧版本同步留下的）：仍然报 binary-content。
  // 这不是重复噪音，而是唯一诚实的说法——那个文件本来就不可用，说"已一致"会把损坏藏起来。
  // 它也不会让项目收敛不了：游标照常推进，下一轮 feed 不再返回这个集合。
  const stored = binaryPayload().changeSets[0].files[0].afterContent
  const plan = buildSyncPlan({
    ...binaryPayload(),
    cursor: 0,
    probe: { 'backend/blog.db': { state: 'present', exists: true, content: stored } }
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.conflicts.length, 0)
  assert.equal(plan.skipped[0].reason, 'binary-content')
  assert.equal(plan.nextCursor, 337, 'cursor still advances, so the project can converge')
})

test('plain text containing a replacement character is still written', () => {
  // U+FFFD 本身不构成"二进制"证据：合法文本也可能含它（用户原文里就有）。
  const plan = buildSyncPlan({
    changeSets: [
      {
        changeSetId: 10,
        files: [
          {
            changeId: 'txt-2',
            relativePath: 'docs/note.md',
            changeType: 'create',
            status: 'applied',
            afterContent: '替换符 \uFFFD 是正文的一部分'
          }
        ]
      }
    ],
    cursor: 0,
    probe: { 'docs/note.md': { state: 'absent', exists: false, content: null } }
  })

  assert.equal(plan.steps.length, 1)
  assert.equal(plan.conflicts.length, 0)
  assert.equal(plan.skipped.length, 0)
})
