/**
 * 品牌标记的几何与配色（唯一数据源）。
 *
 * 为什么把图元写成结构化数组而不是直接内联一段 <svg> 字符串：
 *   1. 组件需要按主题微调描边（暗色下深色底会与背景糊在一起），结构化后每块可单独控制；
 *   2. 站点图标（favicon / PWA / 分享图）由脚本从同一份数据生成，
 *      避免「改了一处、另一处没跟上」——这类不一致往往要等到用户看到旧图标才发现。
 *
 * 几何取自 public/brand/labex-mark.svg（画布 128×128，四边各留 12 单位内边距）。
 * 内联渲染时使用紧裁 viewBox `12 12 104 104` 去掉内边距：
 * 同样尺寸下主体更大，21–26px 的小尺寸下细节更清楚。
 */

/** 紧裁后的 viewBox。原图 0 0 128 128 含 12 单位内边距，小尺寸下主体会显得偏小。 */
export const BRAND_MARK_VIEWBOX = '12 12 104 104'
/** 原始画布尺寸（站点图标导出用，保留内边距更符合平台规范） */
export const BRAND_MARK_CANVAS = 128
export const BRAND_MARK_PADDING = 12

/** 品牌配色 */
export const BRAND_MARK_COLORS = {
  /** 圆角底板：深板岩 */
  plate: '#0F172A',
  /** 主瓶身线条：近白薄荷 */
  vessel: '#D7F9F1',
  /** 强调绿（液面） */
  accentGreen: '#39D98A',
  /** 次要青（刻度） */
  accentTeal: '#5EEAD4',
  /** 连接线：中性灰 */
  link: '#94A3B8'
}

/**
 * 图元数组。每条按 `type` 区分：
 *   rect  → 底板
 *   path  → 线段 / 形状轮廓
 *   circle→ 节点
 * 属性名与 SVG 原生属性一致，组件可直接 v-bind。
 */
export const BRAND_MARK_SHAPES = [
  { type: 'rect', x: 12, y: 12, width: 104, height: 104, rx: 28, fill: 'plate', role: 'plate' },

  // 瓶口与瓶颈
  { type: 'path', d: 'M40 32H88', stroke: 'vessel', strokeWidth: 8, linecap: 'round' },
  { type: 'path', d: 'M52 34V57L37 88C34.4 93.3 38.2 99.5 44.1 99.5H83.9C89.8 99.5 93.6 93.3 91 88L76 57V34', stroke: 'vessel', strokeWidth: 8, linecap: 'round', linejoin: 'round' },

  // 液面与刻度
  { type: 'path', d: 'M46 84H82', stroke: 'accentGreen', strokeWidth: 8, linecap: 'round' },
  { type: 'path', d: 'M54 72H74', stroke: 'accentTeal', strokeWidth: 7, linecap: 'round' },

  // 网络节点
  { type: 'circle', cx: 43, cy: 42, r: 5, fill: 'accentGreen' },
  { type: 'circle', cx: 85, cy: 45, r: 5, fill: 'accentTeal' },
  { type: 'path', d: 'M43 42L64 55L85 45', stroke: 'link', strokeWidth: 4, linecap: 'round' },

  // 左右引出线与底部接口
  { type: 'path', d: 'M31 73H18', stroke: 'accentGreen', strokeWidth: 5, linecap: 'round' },
  { type: 'path', d: 'M110 73H97', stroke: 'accentTeal', strokeWidth: 5, linecap: 'round' },
  { type: 'path', d: 'M64 111V100', stroke: 'vessel', strokeWidth: 5, linecap: 'round' }
]

/**
 * 生成标准 SVG 源码字符串。
 * 供站点图标导出脚本（favicon / PWA / 分享图）复用同一份几何。
 *
 * @param {{ size?: number, padding?: number }} options
 *        size 省略时输出可缩放的 viewBox 版本；指定时输出固定像素尺寸。
 */
export function buildBrandMarkSvg(options = {}) {
  const { size, padding = BRAND_MARK_PADDING } = options
  const canvas = BRAND_MARK_CANVAS
  const viewBox = padding ? `${padding} ${padding} ${canvas - padding * 2} ${canvas - padding * 2}` : `0 0 ${canvas} ${canvas}`
  const sizeAttrs = size ? ` width="${size}" height="${size}"` : ''
  const c = BRAND_MARK_COLORS

  const body = BRAND_MARK_SHAPES.map(shape => {
    if (shape.type === 'rect') {
      return `  <rect x="${shape.x}" y="${shape.y}" width="${shape.width}" height="${shape.height}" rx="${shape.rx}" fill="${c[shape.fill] || shape.fill}"/>`
    }
    if (shape.type === 'circle') {
      return `  <circle cx="${shape.cx}" cy="${shape.cy}" r="${shape.r}" fill="${c[shape.fill] || shape.fill}"/>`
    }
    const stroke = c[shape.stroke] || shape.stroke
    const cap = shape.linecap ? ` stroke-linecap="${shape.linecap}"` : ''
    const join = shape.linejoin ? ` stroke-linejoin="${shape.linejoin}"` : ''
    return `  <path d="${shape.d}" stroke="${stroke}" stroke-width="${shape.strokeWidth}"${cap}${join}/>`
  }).join('\n')

  return `<svg${sizeAttrs} viewBox="${viewBox}" fill="none" xmlns="http://www.w3.org/2000/svg">\n${body}\n</svg>\n`
}
