import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const viewDirectory = dirname(fileURLToPath(import.meta.url))
const sourceRoot = join(viewDirectory, '..')

async function readSource(...segments) {
  return readFile(join(sourceRoot, ...segments), 'utf8')
}

test('Auth uses a crisp split wabi-sabi AI coding-agent layout', async () => {
  const source = await readSource('views', 'Auth.vue')
  const styles = await readSource('styles', 'tailwind.css')

  assert.match(source, /auth-layout/)
  assert.match(source, /auth-narrative/)
  assert.match(source, /lg:grid-cols-\[minmax\(0,1fr\)_460px\]/)
  assert.match(source, /auth-card--expanded/)
  assert.match(source, /handleCardPointerMove/)
  assert.match(source, /\u667a\u80fd\u7f16\u7a0b\u5de5\u4f5c\u95f4/)
  assert.match(source, /\u7406\u89e3\u4ed3\u5e93/)
  assert.match(source, /\u89c4\u5212\u6539\u52a8/)
  assert.match(source, /\u9a8c\u8bc1\u7ed3\u679c/)
  assert.doesNotMatch(source, /SocialLogin/)
  assert.doesNotMatch(styles, /filter:\s*blur/)
  assert.match(styles, /max-width: calc\(100vw - 40px\);/)
})

test('authentication uses typed Chinese brand and form components', async () => {
  const [logo, loginForm, loginWrapper] = await Promise.all([
    readSource('components', 'Logo.vue'),
    readSource('components', 'LoginForm.vue'),
    readSource('views', 'Login.vue'),
  ])

  assert.match(logo, /<script setup lang="ts">/)
  assert.match(logo, /\u667a\u80fd\u4ee3\u7801\u5de5\u4f5c\u95f4/)
  assert.match(loginForm, /<script setup lang="ts">/)
  assert.match(loginForm, /\u7528\u6237\u540d/)
  assert.match(loginForm, /focus:border-\[#5D675B\]/)
  assert.match(loginWrapper, /<Auth \/>/)
})

test('Tailwind keeps the wabi-sabi material tokens and utilities above the legacy reset', async () => {
  const source = await readSource('styles', 'tailwind.css')

  assert.match(source, /--color-auth-background: #EEE9DE;/)
  assert.match(source, /auth-paper-grain/)
  assert.match(source, /--color-auth-moss: #5D675B;/)
  assert.match(source, /@import "tailwindcss\/utilities\.css";/)
  assert.doesNotMatch(source, /layer\(utilities\)/)
})
