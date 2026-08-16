import assert from 'node:assert/strict'
import test from 'node:test'

import { createWorkspaceMutationProjection } from './workspaceMutationProjection.js'

function message(taskId) {
  return { taskId }
}

test('只为当前项目和所属 task 投影一次持久化工作区变更', async () => {
  const projectId = { value: 12 }
  let refreshes = 0
  const projection = createWorkspaceMutationProjection({
    projectId,
    loadRoot: async () => {
      refreshes += 1
      return true
    }
  })
  const event = {
    eventId: 404,
    data: {
      projectId: 12,
      taskId: 71,
      workspaceChangeId: 'approval-71:workspace-changed'
    }
  }

  assert.equal(await projection.projectWorkspaceChange(event, message(71)), true)
  assert.equal(await projection.projectWorkspaceChange(event, message(71)), false)
  assert.equal(refreshes, 1)
})

test('不会让其他项目或 task 的持久化工作区事件刷新当前文件树', async () => {
  const projectId = { value: 12 }
  let refreshes = 0
  const projection = createWorkspaceMutationProjection({
    projectId,
    loadRoot: async () => {
      refreshes += 1
      return true
    }
  })

  assert.equal(await projection.projectWorkspaceChange({
    data: { projectId: 13, taskId: 71, workspaceChangeId: 'foreign-project' }
  }, message(71)), false)
  assert.equal(await projection.projectWorkspaceChange({
    data: { projectId: 12, taskId: 72, workspaceChangeId: 'foreign-task' }
  }, message(71)), false)
  assert.equal(refreshes, 0)
})
