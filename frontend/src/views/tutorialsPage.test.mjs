import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)

async function source(path) {
  return readFile(new URL(path, root), 'utf8')
}

test('教程页通过路由和入口组件接入，正文不硬编码在前端', async () => {
  const router = await source('router/index.js')
  const app = await source('App.vue')
  const page = await source('views/Tutorials.vue')
  const api = await source('api/index.js')

  assert.match(router, /name: 'Tutorials'/)
  assert.match(app, /TutorialsLauncher/)
  assert.match(page, /tutorialApi\.list\(\)/)
  assert.match(page, /tutorialApi\.get\(slug\)/)
  assert.match(api, /get\(slug\)/)
  assert.doesNotMatch(page, /快速开始|如何使用 Agent|contentMarkdown:/)
})

test('教程正文只允许安全 Markdown 标签和安全链接协议', async () => {
  const article = await source('components/tutorial/TutorialArticle.vue')
  const markdown = await source('components/tutorial/tutorialMarkdown.js')

  assert.match(markdown, /ALLOWED_TAGS\s*=\s*new\s*Set/i)
  assert.match(markdown, /https\?:\\\/\\\/\|mailto:\|#/)
  assert.match(markdown, /noopener noreferrer/)
  assert.match(article, /v-html="renderedContent"/)
})
