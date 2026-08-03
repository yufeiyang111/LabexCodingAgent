import assert from 'node:assert/strict'
import test from 'node:test'
import {
  normalizeCommandCatalog,
  resolveSlashCommand
} from './slashCommandRuntime.js'

test('normalizes the server catalog and drops unavailable entries', () => {
  const commands = normalizeCommandCatalog({
    data: {
      commands: [
        { name: 'new', description: '新建会话', dispatch: 'CLIENT_ACTION', action: 'SESSION_NEW', aliases: ['clear'] },
        { name: 'review', description: '代码审查', dispatch: 'AGENT_PROMPT', aliases: [] },
        { name: 'rename', description: '重命名', dispatch: 'UNAVAILABLE', aliases: [] }
      ]
    }
  })

  assert.deepEqual(commands.map(command => command.name), ['new', 'review'])
  assert.equal(commands[0].category, '界面')
  assert.deepEqual(commands[0].aliases, ['clear'])
  assert.equal(commands[1].category, 'Agent')
})

test('dispatches client actions without producing an Agent prompt', async () => {
  const calls = []
  const result = await resolveSlashCommand({
    raw: '/clear',
    runCommand: async request => {
      calls.push(request)
      return { data: { success: true, command: 'new', dispatch: 'CLIENT_ACTION', action: 'SESSION_NEW' } }
    },
    clientActions: {
      SESSION_NEW: async ({ command, arguments: args }) => calls.push({ command, args })
    }
  })

  assert.equal(result.kind, 'handled')
  assert.equal(result.command, 'new')
  assert.deepEqual(calls, [
    { command: 'clear', message: '/clear' },
    { command: 'new', args: '' }
  ])
})

test('returns a prompt only for AGENT_PROMPT commands', async () => {
  const result = await resolveSlashCommand({
    raw: '/review src/App.vue',
    runCommand: async () => ({
      data: {
        success: true,
        command: 'review',
        dispatch: 'AGENT_PROMPT',
        template: 'Review this target: src/App.vue',
        subtask: true,
        description: '代码审查'
      }
    }),
    clientActions: {}
  })

  assert.deepEqual(result, {
    kind: 'agent_prompt',
    command: 'review',
    prompt: 'Review this target: src/App.vue',
    subtask: true,
    description: '代码审查'
  })
})

test('never falls back to sending unsupported commands to the Agent', async () => {
  await assert.rejects(
    resolveSlashCommand({
      raw: '/rename demo',
      runCommand: async () => ({
        data: {
          success: false,
          command: 'rename',
          dispatch: 'UNAVAILABLE',
          message: '指令 /rename 尚未接通真实执行能力'
        }
      }),
      clientActions: {}
    }),
    /尚未接通真实执行能力/
  )
})
