import assert from 'node:assert/strict'
import test from 'node:test'

async function loadConfig(target, port) {
  process.env.VITE_API_TARGET = target
  process.env.ACCEPTANCE_FRONTEND_PORT = String(port)
  return (await import(`../../vite.config.js?case=${target}-${port}-${Date.now()}`)).default
}

test('Vite acceptance settings are opt-in while preserving production defaults', async () => {
  const configured = await loadConfig('http://127.0.0.1:18080', 13000)
  assert.equal(configured.server.proxy['/api'].target, 'http://127.0.0.1:18080')
  assert.equal(configured.server.port, 13000)
})
