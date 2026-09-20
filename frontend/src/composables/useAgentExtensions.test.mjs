import assert from 'node:assert/strict'
import test from 'node:test'

import { eligibleSkillFiles, isTruncatedSkill, parseSkillMarkdown, useAgentExtensions } from './useAgentExtensions.js'

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

test('keeps skill bodies up to the backend limit instead of truncating early', () => {
  // 后端 AgentSkillService.MAX_SKILL_CONTENT = 30000。前端曾只保留 15000，
  // 导致技能尾部（往往是最具体的规则）被静默丢弃，实测 6/19 个技能受影响。
  const body = 'y'.repeat(20000)
  const parsed = parseSkillMarkdown('---\nname: Big\n---\n' + body, 'SKILL.md')
  assert.equal(parsed.content.length, 20000)
  assert.equal(isTruncatedSkill(parsed), false)
})

test('detects truncated bodies so the UI can warn instead of losing content silently', () => {
  const truncated = parseSkillMarkdown('---\nname: Demo\n---\n' + 'x'.repeat(40), 'SKILL.md', { maxContentChars: 20 })
  assert.equal(isTruncatedSkill(truncated), true)

  const intact = parseSkillMarkdown('---\nname: Demo\n---\nshort body', 'SKILL.md')
  assert.equal(isTruncatedSkill(intact), false)
  assert.equal(isTruncatedSkill(null), false)
})

test('loads project skills from the catalog and keeps only the project source', async () => {
  const api = {
    listSkills: async () => ({ data: [] }),
    listMcpServers: async () => ({ data: [] }),
    getSkillCatalog: async () => ({
      data: [
        { skillKey: 'review', title: 'review', source: 'project', relativePath: '.skill/review/SKILL.md' },
        { skillKey: 'deploy', title: 'deploy', source: 'user' }
      ]
    })
  }
  const controller = useAgentExtensions({ api, notify: {}, projectId: { value: 42 } })

  await controller.loadAgentExtensions()

  assert.equal(controller.projectSkillState.value, 'ready')
  // 用户级已由 /skills 提供，这里只取项目级，避免同一技能在界面上出现两次
  assert.deepEqual(controller.projectSkills.value.map(skill => skill.skillKey), ['review'])
})

test('project skill state distinguishes unsupported and failure from a genuinely empty list', async () => {
  const unsupported = useAgentExtensions({ api: {}, notify: {}, projectId: { value: 1 } })
  await unsupported.loadProjectSkills()
  assert.equal(unsupported.projectSkillState.value, 'unsupported')
  await unsupported.loadAgentExtensions()
  assert.equal(unsupported.projectSkillState.value, 'unsupported')

  const failing = useAgentExtensions({
    api: { getSkillCatalog: async () => { throw new Error('catalog unavailable') } },
    notify: {},
    projectId: { value: 1 }
  })
  await failing.loadProjectSkills()
  assert.equal(failing.projectSkillState.value, 'error')

  // 没有选中项目时不应请求，也不应谎报"没有项目级技能"
  const noProject = useAgentExtensions({
    api: { getSkillCatalog: async () => { throw new Error('should not be called') } },
    notify: {},
    projectId: { value: null }
  })
  await noProject.loadProjectSkills()
  assert.equal(noProject.projectSkillState.value, 'unsupported')
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
