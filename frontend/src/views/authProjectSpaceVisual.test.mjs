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

test('Auth exposes one truthful centered username-password path', async () => {
  const source = await readSource('views', 'Auth.vue')

  assert.match(source, /auth-topbar/)
  assert.match(source, /auth-content/)
  assert.match(source, /auth-footer/)
  assert.match(source, /max-w-\[400px\]/)
  assert.match(source, /Log in to LabexAgent/)
  assert.match(source, /Create account/)
  assert.match(source, /hidden[\s\S]*sm:inline-flex/)
  assert.match(source, /sm:hidden/)
  assert.match(source, /Terms of Service/)
  assert.doesNotMatch(source, /SocialLogin/)
  assert.doesNotMatch(source, /Continue with Google/)
  assert.doesNotMatch(source, /Continue with GitHub/)
  assert.doesNotMatch(source, />or</)
  assert.doesNotMatch(source, /auth-story/)
  assert.doesNotMatch(source, /auth-paper-grain/)
})

test('authentication uses typed brand and form components without generic icon dependencies', async () => {
  const [logo, loginForm, loginWrapper] = await Promise.all([
    readSource('components', 'Logo.vue'),
    readSource('components', 'LoginForm.vue'),
    readSource('views', 'Login.vue'),
  ])

  assert.match(logo, /<script setup lang="ts">/)
  assert.match(logo, /viewBox="0 0 32 32"/)
  assert.doesNotMatch(logo, /lucide-vue-next/)
  assert.match(loginForm, /<script setup lang="ts">/)
  assert.match(loginForm, />Username</)
  assert.match(loginForm, /focus:border-\[#1D1D1F\]/)
  assert.match(loginWrapper, /<Auth \/>/)
})

test('Tailwind keeps utilities above the legacy global reset without importing preflight', async () => {
  const source = await readSource('styles', 'tailwind.css')

  assert.match(source, /--color-auth-background: #FAFAFA;/)
  assert.match(source, /@import "tailwindcss\/utilities\.css";/)
  assert.doesNotMatch(source, /layer\(utilities\)/)
  assert.doesNotMatch(source, /@import "tailwindcss";/)
})
