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

test('production build keeps relative /api base and dist output for the reverse proxy', async () => {
  delete process.env.VITE_API_TARGET
  delete process.env.ACCEPTANCE_FRONTEND_PORT
  const config = (await import(`../../vite.config.js?prod=${Date.now()}`)).default
  assert.equal(config.build.outDir, 'dist')
  // 生产构建产物必须使用相对 /api 路径（Nginx 同源代理），不能内嵌 localhost 或外部 host。
  assert.equal(config.server.proxy['/api'].target, 'http://localhost:8080')
  // 无独立 base 配置：默认 '/',与 Nginx `root /usr/share/nginx/html` 一致。
  assert.ok(config.base === '/' || config.base === undefined)
})
