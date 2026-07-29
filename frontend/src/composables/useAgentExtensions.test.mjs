import assert from 'node:assert/strict'
import test from 'node:test'

import { eligibleSkillFiles, parseSkillMarkdown, useAgentExtensions } from './useAgentExtensions.js'

test('rejects malformed frontmatter and bounds imported skill content', () => {
  assert.equal(parseSkillMarkdown('---\nname: broken\nmissing close', 'SKILL.md'), null)
  const parsed = parseSkillMarkdown('---\nname: Demo Skill\ndescription: safe\n---\n' + 'x'.repeat(40), 'SKILL.md', { maxContentChars: 20 })
  assert.equal(parsed.skillKey, 'demo-skill')
  assert.equal(parsed.content, 'x'.repeat(20) + '\n\n[内容已截断]')
})

test('filters folder uploads by exact name, count, and byte limit', () => {
  const files = [
    { name: 'SKILL.md', size: 10 },
    { name: 'README.md', size: 10 },
    { name: 'SKILL.md', size: 200 },
    { name: 'SKILL.md', size: 10 }
  ]
  assert.deepEqual(eligibleSkillFiles(files, { maxFiles: 1, maxFileBytes: 100 }), [files[0]])
})

test('ignores stale extension-list responses', async () => {
  let resolveFirst
  const first = new Promise(resolve => { resolveFirst = resolve })
  let call = 0
  const api = {
    listSkills: async () => (++call === 1 ? first : { data: [{ skillId: 2 }] }),
    listMcpServers: async () => ({ data: [] })
  }
  const controller = useAgentExtensions({ api, notify: {} })
  const oldLoad = controller.loadAgentExtensions()
  await controller.loadAgentExtensions()
  resolveFirst({ data: [{ skillId: 1 }] })
  await oldLoad
  assert.deepEqual(controller.agentSkills.value, [{ skillId: 2 }])
})

test('reports MCP test unsupported and provider errors instead of fake success', async () => {
  const warnings = []
  const errors = []
  const unsupported = useAgentExtensions({ api: {}, notify: { warning: value => warnings.push(value) } })
  assert.deepEqual(await unsupported.testMcpConnection({ serverId: 1 }), { success: false, reason: 'unsupported' })
  assert.match(warnings[0], /未提供 MCP 连接测试接口/)

  const failing = useAgentExtensions({
    api: { testMcpServer: async () => { throw new Error('connection refused') } },
    notify: { info() {}, error: value => errors.push(value) }
  })
  assert.deepEqual(await failing.testMcpConnection({ serverId: 1 }), { success: false, reason: 'failed' })
  assert.match(errors[0], /connection refused/)
})
