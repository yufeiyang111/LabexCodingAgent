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

test('教程路由正确定位 slug 参数与 TutorialDetail，防止路由丢弃参数导致内容不显示', async () => {
  const router = await source('router/index.js')
  const page = await source('views/Tutorials.vue')

  assert.match(router, /path:\s*'\/tutorials\/:slug',\s*name:\s*'TutorialDetail'/, '路由必须使用 :slug 作为参数占位符并命名为 TutorialDetail')
  assert.match(page, /router\.push\(\{\s*name:\s*'TutorialDetail',\s*params:\s*\{\s*slug\s*\}\s*\}\)/, '点击侧边栏必须导航到 TutorialDetail 且携带 slug')
  assert.match(page, /router\.replace\(\{\s*name:\s*'TutorialDetail',\s*params:\s*\{\s*slug:\s*defaultSlug\s*\}\s*\}\)/, '默认第一篇必须替换导航到 TutorialDetail')
})

test('教程页空状态与侧边栏布局样式：右侧正文大纲栏宽度增大，空状态方块与图标放大居中', async () => {
  const scss = await source('styles/tutorials.scss')
  const article = await source('components/tutorial/TutorialArticle.vue')

  assert.match(scss, /\.tutorial-page__layout\s*\{[^}]*grid-template-columns:[^}]*280px/, '右侧正文内容侧边栏初始宽度必须增大至 280px')
  assert.match(scss, /\.tutorial-article__empty\s*\{[^}]*min-height:\s*460px/, '空状态占位方块高度需放大')
  assert.match(scss, /\.tutorial-article__empty\s*\{[^}]*margin:\s*auto/, '空状态方块需居中显示')
  assert.match(scss, /\.tutorial-article__empty-icon-wrap/, '空状态需包含放大的图标背景包裹')
  assert.match(article, /tutorial-article__empty-icon-wrap/, '空状态组件需渲染放大图标容器')
})

test('窄屏（<1200px）教程目录默认折叠为抽屉，需要时才唤出，正文不被遮挡', async () => {
  const page = await source('views/Tutorials.vue')
  const scss = await source('styles/tutorials.scss')
  const outline = await source('components/tutorial/TutorialOutline.vue')

  // 折叠判定与抽屉挂载
  assert.match(page, /isNarrow = computed\(\(\) => windowWidth\.value < 1200\)/, '折叠判定必须覆盖 <1200px 的手机/平板/窄窗口')
  assert.match(page, /'is-mobile-drawer': isNarrow/, '侧边栏必须在窄屏挂载抽屉类')
  assert.match(page, /'drawer-mode': isNarrow/, '布局必须在窄屏切换为单列正文流')
  assert.match(page, /v-if="isNarrow"/, '顶栏目录按钮必须在窄屏显示')
  // 抽屉默认收起：transform 移出屏幕 + visibility 隐藏，仅 drawer-open 时滑入
  assert.match(scss, /\.tutorial-sidebar\.is-mobile-drawer\s*\{[^}]*transform:\s*translateX\(-100%\)/, '抽屉默认必须收起在屏幕外')
  assert.match(scss, /\.tutorial-sidebar\.is-mobile-drawer\s*\{[^}]*visibility:\s*hidden/, '收起状态的抽屉必须不可交互')
  assert.match(scss, /\.tutorial-page__layout\.mobile-drawer-open \.tutorial-sidebar\.is-mobile-drawer\s*\{[^}]*transform:\s*translateX\(0\)/, '点击目录按钮时抽屉滑入')
  assert.match(scss, /\.tutorial-page__layout\.drawer-mode\s*\{[^}]*display:\s*block/, '窄屏布局必须退化为单列正文流，目录不再挤占正文')
  // 大纲底部抽屉
  assert.match(outline, /mobileOpen/, '大纲组件必须支持移动端抽屉形态')
  assert.match(page, /showMobileOutline/, '顶栏必须提供大纲唤出入口')
})
