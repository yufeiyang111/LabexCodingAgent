import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'

import { STACK_TEMPLATE_ICONS, getStackIcon, getStackIconSvg } from '../../../assets/icons/stacks/index.js'
import { STACKS, ICON_DIR, normalizeSvg } from '../../../../scripts/generate-stack-icons.mjs'

const cloudSpaceUrl = new URL('../../../views/CloudSpace.vue', import.meta.url)
const componentUrl = new URL('./ProjectStackIcon.vue', import.meta.url)

test('每个技术栈都有同名 SVG 资产，且与注册表内联内容完全一致', async () => {
  const files = await readdir(ICON_DIR)

  for (const stack of STACKS) {
    const file = `${stack.key}.svg`
    assert.ok(files.includes(file), `缺少图标资产 ${file}`)

    const onDisk = normalizeSvg(await readFile(join(ICON_DIR, file), 'utf8'))
    const registered = STACK_TEMPLATE_ICONS[stack.key]?.svg

    assert.ok(registered, `注册表缺少 ${stack.key}`)
    assert.equal(registered, onDisk, `${file} 与注册表内容不一致，请运行 node scripts/generate-stack-icons.mjs 重新生成`)
  }
})

test('图标 key 与 CloudSpace 模板 key 一一对应，无多余无缺失', async () => {
  const source = await readFile(cloudSpaceUrl, 'utf8')
  const block = source.match(/const templates = \[([\s\S]*?)\]/)

  assert.ok(block, 'CloudSpace.vue 未找到 templates 定义')

  const templateKeys = [...block[1].matchAll(/key:\s*'([^']+)'/g)].map(m => m[1])
  const iconKeys = Object.keys(STACK_TEMPLATE_ICONS)

  assert.deepEqual([...templateKeys].sort(), [...iconKeys].sort(), '模板 key 与图标 key 必须一一对应')
})

test('每个图标均为 24x24 官方矢量，且图形继承 currentColor 以适配主题', () => {
  // 图标为透明底，直接落在模板卡片面上（浅色 #ffffff / 暗色 #181b24）。
  // 只要品牌色不等于这两种卡面即可保证可见；React 的 #61DAFB 本身就偏浅，
  // 与白底对比偏低属于其官方标志的固有观感，不作更严阈值约束。
  const cardSurfaces = ['#ffffff', '#181b24']

  for (const [key, icon] of Object.entries(STACK_TEMPLATE_ICONS)) {
    assert.match(icon.svg, /^<svg[\s>]/, `${key} 不是合法 SVG`)
    assert.match(icon.svg, /viewBox="0 0 24 24"/, `${key} 缺少统一 viewBox`)
    assert.match(icon.svg, /<path fill="currentColor"/, `${key} 的图形未继承 currentColor`)
    assert.match(icon.brandColor, /^#[0-9A-F]{6}$/i, `${key} 品牌色格式不合法`)

    for (const surface of cardSurfaces) {
      assert.notEqual(
        icon.brandColor.toLowerCase(),
        surface,
        `${key} 品牌色与卡面 ${surface} 相同会导致图形不可辨`
      )
    }
  }
})

test('getStackIcon 大小写不敏感，未知 key 返回 null', () => {
  assert.equal(getStackIcon('VUE')?.name, 'Vue')
  assert.equal(getStackIcon(' SpringBoot ')?.name, 'Spring Boot')
  assert.equal(getStackIcon('react')?.svg, STACK_TEMPLATE_ICONS.react.svg)
  assert.equal(getStackIconSvg('flask'), STACK_TEMPLATE_ICONS.flask.svg)
  assert.equal(getStackIcon(''), null)
  assert.equal(getStackIcon('unknown-stack'), null)
  assert.equal(getStackIconSvg('unknown-stack'), null)
})

test('新建项目弹窗使用官方图标组件渲染模板，不再显示单字母占位', async () => {
  const source = await readFile(cloudSpaceUrl, 'utf8')

  assert.match(source, /import ProjectStackIcon from '@\/components\/cloud\/projects\/ProjectStackIcon\.vue'/)
  assert.match(source, /<ProjectStackIcon[^>]*:stack-key="tpl\.key"/)
  assert.doesNotMatch(source, /tpl\.icon/, '模板项不应再渲染单字母占位图标')
})

test('技术栈图标以透明底 + 官方品牌色渲染，图形铺满给定尺寸', async () => {
  const source = await readFile(componentUrl, 'utf8')

  assert.match(source, /getStackIcon/)
  // 透明底且无内边距：图形即官方矢量本身，并拿满整个 size 的像素预算
  assert.match(source, /background:\s*'transparent'/)
  assert.match(source, /color:\s*icon\.value\.brandColor/)
  assert.doesNotMatch(source, /glyphColor/, '不应再使用反色字形字段')
  assert.doesNotMatch(source, /--cs-stack-chip-bg/, '不应再引入芯片底色变量')
})
