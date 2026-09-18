import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const COMPONENT_URL = new URL('./OpsIcon.vue', import.meta.url)
const LUCIDE_ICONS = new URL('../../../../node_modules/@iconify-json/lucide/icons.json', import.meta.url)

/**
 * 历史上组件对外暴露的全部图标名（含别名）。
 * 整改为官方图标集时必须保持这份契约不缩水，否则调用方会静默退化成兜底圆圈。
 */
const REQUIRED_NAMES = [
  'activity', 'alert-circle', 'alert-triangle', 'arrow-left', 'arrow-right',
  'bell', 'bell-off', 'box', 'check', 'check-circle',
  'chevron-down', 'chevron-left', 'chevron-right', 'chevron-up', 'clock',
  'coffee', 'cpu', 'dashboard', 'database', 'disk',
  'edit', 'eye', 'file-text', 'filter', 'globe',
  'hard-drive', 'hash', 'info', 'key', 'layers',
  'layout-dashboard', 'lock', 'log-out', 'memory', 'monitor',
  'network', 'pie-chart', 'plus', 'power', 'refresh',
  'rotate-cw', 'search', 'server', 'shield', 'shield-alert',
  'sparkles', 'terminal', 'trash', 'trending-up', 'user',
  'users', 'x', 'zap'
]

const readComponent = () => readFile(COMPONENT_URL, 'utf8')

test('组件不再内联任何手抄的图形路径，只允许通过 ~icons 按需引入官方图标', async () => {
  const source = await readComponent()

  // 模板中不得出现字面图形元素（整改前有 341 行 Feather/Lucide 手抄路径）
  assert.doesNotMatch(source, /<(path|circle|line|polyline|polygon|rect|ellipse)\s/, '模板中不应再出现内联图形元素')

  // 模板运行时只有 <component :is>，不再有 <svg> 标签
  assert.doesNotMatch(source, /<svg/, '不应再手写 <svg> 容器')

  // 所有图形必须来自 unplugin-icons 的按需导入
  assert.match(source, /from '~icons\/lucide\//, '应通过 ~icons/lucide 按需引入')
})

test('每个 ~icons/lucide 引用都能在已安装的官方图标集合中解析', async () => {
  const source = await readComponent()
  const lucideIcons = JSON.parse(await readFile(LUCIDE_ICONS, 'utf8'))
  const available = new Set(Object.keys(lucideIcons.icons))

  const imports = [...source.matchAll(/from '~icons\/lucide\/([a-z0-9-]+)'/g)].map(m => m[1])
  assert.ok(imports.length >= 40, `应导入足量图标，实际 ${imports.length}`)

  const unresolved = imports.filter(name => !available.has(name))
  assert.deepEqual(unresolved, [], `以下图标在 @iconify-json/lucide 中不存在：${unresolved.join(', ')}`)
})

test('历史图标名契约完整保留，无任何条目丢失', async () => {
  const source = await readComponent()
  const mapBlock = source.match(/const OPS_ICONS = \{([\s\S]*?)\n\}/)
  assert.ok(mapBlock, '未找到 OPS_ICONS 映射')

  const mapped = new Set(
    [...mapBlock[1].matchAll(/^\s*'?([a-z0-9-]+)'?:\s*Icon/gm)].map(m => m[1])
  )

  const missing = REQUIRED_NAMES.filter(name => !mapped.has(name))
  assert.deepEqual(missing, [], `以下图标名在整改中丢失：${missing.join(', ')}`)
})

test('图标来源为 Lucide（ISC），组件内保留可核验的署名声明', async () => {
  const source = await readComponent()

  assert.match(source, /Lucide/, '应声明上游图标集')
  assert.match(source, /ISC/, '应声明上游许可')
  assert.match(source, /lucide-icons\/lucide/, '应给出可核验的上游地址')
  assert.match(source, /unplugin-icons/, '应说明按需引入方式')
})

test('保留未识别名称的兜底渲染，行为与历史一致', async () => {
  const source = await readComponent()
  assert.match(source, /OPS_ICONS\[props\.name\] \|\| IconCircle/, '未知名称应回退到兜底图标')
})
