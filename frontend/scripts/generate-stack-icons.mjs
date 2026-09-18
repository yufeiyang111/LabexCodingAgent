/**
 * 依据 `src/assets/icons/stacks/<key>.svg` 资产生成集中式注册表 `index.js`。
 *
 * 为什么需要生成步骤：
 * 前端测试由 `scripts/run-tests.mjs` 以纯 `node --test` 执行，无法解析 Vite 专有的
 * `?raw` 资源导入，因此注册表必须内联 SVG 字符串。为杜绝"脚本内联内容"与"磁盘资产"
 * 之间的漂移，本脚本统一归一化 `.svg` 文件并同步生成 `index.js`，
 * 并由 `ProjectStackIcon.test.mjs` 反向校验两者一致。
 *
 * 用法：node scripts/generate-stack-icons.mjs
 */
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
export const ICON_DIR = join(here, '..', 'src', 'assets', 'icons', 'stacks')

/**
 * 技术栈定义：key 与 `<key>.svg` 文件名严格一一对应。
 * brandColor 为上游官方品牌色（Simple Icons 数据集 / Material 中性色），
 * 字形一律以 brandColor 绘制在主题化中性芯片上，从而与官方标志观感一致。
 * 不要把品牌色当芯片底色再用反色字形：Spring Boot 等"实心轮廓"标志会被吃掉形状。
 */
export const STACKS = [
  {
    key: 'vue',
    name: 'Vue',
    brandColor: '#4FC08D',
    source: 'Simple Icons (CC0-1.0)；Vue.js 官方标志，商标归 Vue.js 团队所有'
  },
  {
    key: 'react',
    name: 'React',
    brandColor: '#61DAFB',
    source: 'Simple Icons (CC0-1.0)；React 官方标志，商标归 Meta 所有'
  },
  {
    key: 'springboot',
    name: 'Spring Boot',
    brandColor: '#6DB33F',
    source: 'Simple Icons (CC0-1.0)；Spring Boot 官方标志，商标归 Spring 项目所有'
  },
  {
    key: 'flask',
    name: 'Flask',
    brandColor: '#3BABC3',
    source: 'Simple Icons (CC0-1.0)；Flask 官方标志，商标归 Pallets 项目所有'
  },
  {
    key: 'empty',
    name: '空项目',
    brandColor: '#64748B',
    source: 'Material Symbols create_new_folder (Apache-2.0)；无品牌归属，使用中性通用图标'
  }
]

/** 归一化：压成单行、去除标签间空白，并让 path 继承 currentColor 以适配主题。 */
export function normalizeSvg(raw) {
  return raw
    .replace(/>\s+</g, '><')
    .replace(/\s*\n\s*/g, '')
    .replace(/<path (?!fill=)/g, '<path fill="currentColor" ')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

async function main() {
  const entries = []

  for (const stack of STACKS) {
    const file = join(ICON_DIR, `${stack.key}.svg`)
    const svg = normalizeSvg(await readFile(file, 'utf8'))
    await writeFile(file, `${svg}\n`, 'utf8')
    entries.push({ ...stack, svg })
  }

  const blocks = entries
    .map(
      ({ key, name, brandColor, source, svg }) => `  // ${source}
  ${JSON.stringify(key)}: {
    name: ${JSON.stringify(name)},
    brandColor: ${JSON.stringify(brandColor)},
    svg: ${JSON.stringify(svg)}
  }`
    )
    .join(',\n')

  const output = `/**
 * 项目模板技术栈官方图标库（集中管理）
 *
 * 目录约定：\`assets/icons/stacks/<stack-key>.svg\`，文件名与技术栈 key 一一对应。
 * 本文件由 \`scripts/generate-stack-icons.mjs\` 依据上述 SVG 资产生成，请勿手工编辑 svg 字段；
 * 更换图标请替换 \`.svg\` 文件后重新生成，配套测试会校验两者内容一致。
 *
 * 授权：图形路径取自 Simple Icons（CC0-1.0）与 Material Symbols（Apache-2.0）；
 * 各技术栈名称与商标归其各自所有者，此处仅用于标识对应的项目模板类型。
 * 字形一律以 \`brandColor\` 绘制在主题化中性芯片上，与各官方标志观感一致。
 */

export const STACK_TEMPLATE_ICONS = Object.freeze({
${blocks}
});

/**
 * 依据技术栈 key 获取官方矢量图标定义。
 * @param {string} stackKey 技术栈 key，大小写不敏感
 * @returns {{name: string, brandColor: string, svg: string}|null}
 */
export function getStackIcon(stackKey) {
  if (!stackKey) return null;
  const key = String(stackKey).toLowerCase().trim();
  return STACK_TEMPLATE_ICONS[key] || null;
}

/**
 * 依据技术栈 key 获取图标 SVG 字符串。
 * @param {string} stackKey 技术栈 key，大小写不敏感
 * @returns {string|null}
 */
export function getStackIconSvg(stackKey) {
  const icon = getStackIcon(stackKey);
  return icon ? icon.svg : null;
}
`

  await writeFile(join(ICON_DIR, 'index.js'), output, 'utf8')
  console.log(`generate-stack-icons: 已同步 ${entries.length} 个技术栈图标`)
}

// 仅在直接执行时生成，避免被测试导入时产生副作用
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    console.error('generate-stack-icons failed:', error)
    process.exit(1)
  })
}
