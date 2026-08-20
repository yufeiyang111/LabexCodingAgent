import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

async function source(path) {
  return readFile(new URL(`../../${path}`, import.meta.url), 'utf8')
}

test('OAuth account binding is exposed through the centralized API and account panel', async () => {
  const [api, panel, cloudSpace] = await Promise.all([
    source('api/index.js'),
    source('components/cloud/UserDetailButton.vue'),
    source('views/CloudSpace.vue')
  ])

  assert.match(api, /getOAuthBindings\(/)
  assert.match(api, /\/auth\/oauth\/bindings/)
  assert.match(api, /\/binding/)
  assert.match(api, /\/bind\/start/)
  assert.doesNotMatch(api, /window\.location\.href\s*=\s*'\/api\/auth\/oauth\//)
  assert.match(panel, /await authApi\.startOAuthBinding/)
  assert.match(panel, /window\.location\.assign\(authorizationUrl\)/)
  assert.match(panel, /GitHub/)
  assert.match(panel, /Google/)
  assert.match(panel, /startOAuthBinding/)
  assert.match(panel, /unbindOAuth/)
  assert.match(cloudSpace, /oauth_bound/)
})
