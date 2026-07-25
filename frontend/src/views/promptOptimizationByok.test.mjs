import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const workspacePath = fileURLToPath(new URL('./CloudWorkspace.vue', import.meta.url))
const source = readFileSync(workspacePath, 'utf8')

assert.match(
  source,
  /projectApi\.optimizePrompt\(projectId\.value,\s*\{[\s\S]*?modelConfigId:\s*selectedModelConfigId\.value\s*\|\|\s*null[\s\S]*?\}\)/,
  'prompt optimization must submit the selected user model configuration'
)
