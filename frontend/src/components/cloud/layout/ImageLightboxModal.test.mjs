import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('./ImageLightboxModal.vue', import.meta.url), 'utf8')

test('wheel zoom is anchored at the cursor and clamped to a bounded range', () => {
  assert.match(source, /@wheel="onWheel"/)
  // 元素级监听默认非 passive，必须取消默认行为防止页面滚动穿透。
  assert.match(source, /function onWheel\(e\) \{[\s\S]*e\.preventDefault\(\)/)
  // 光标锚点：相对滚动容器的坐标。
  assert.match(source, /e\.clientX - rect\.left/)
  assert.match(source, /e\.clientY - rect\.top/)
  // 乘法步进保证跨刻度手感一致。
  assert.match(source, /WHEEL_ZOOM_FACTOR = 1\.2/)
  assert.match(source, /e\.deltaY < 0 \? WHEEL_ZOOM_FACTOR : 1 \/ WHEEL_ZOOM_FACTOR/)
  assert.match(source, /MIN_SCALE = 1/)
  assert.match(source, /MAX_SCALE = 8/)
  assert.match(source, /Math\.min\(MAX_SCALE, Math\.max\(MIN_SCALE, nextScale\)\)/)
})

test('zoom keeps the anchored image point fixed via scroll compensation', () => {
  // newScroll = oldContentPoint * k - anchor；回到适配态时滚动归零。
  assert.match(source, /const contentX = body\.scrollLeft \+ anchorX/)
  assert.match(source, /body\.scrollLeft = contentX \* k - anchorX/)
  assert.match(source, /body\.scrollTop = contentY \* k - anchorY/)
})

test('sizing is imperative so wheel ticks never trigger a Vue re-render', () => {
  // 性能契约：尺寸直接写 DOM（一次布局提交），不走响应式样式绑定。
  assert.match(source, /img\.style\.width = ``?/)
  assert.doesNotMatch(source, /<img[^>]*:style=/)
  assert.match(source, /will-change: width, height/)
  // 基准尺寸缓存：仅在打开/换图/加载完成时测量，滚轮路径零布局读取。
  assert.match(source, /let baseSize = null/)
  assert.match(source, /function measureBaseSize\(\)/)
})

test('explicit scaled size neutralizes the CSS max clamps and restores them at fit', () => {
  // 回归钉子：max-width/max-height 会把显式宽高钳回适配大小，导致滚轮"倍率涨图不动"。
  assert.match(source, /img\.style\.maxWidth = 'none'/)
  assert.match(source, /img\.style\.maxHeight = 'none'/)
  assert.match(source, /img\.style\.maxWidth = ''/)
  assert.match(source, /img\.style\.maxHeight = ''/)
})

test('zoom state resets on open, source change, reload and double-click', () => {
  assert.match(source, /watch\(\s*\n\s*\(\) => \[props\.visible, props\.src\],/)
  assert.match(source, /@load="onImgLoad"/)
  assert.match(source, /DOUBLE_CLICK_SCALE = 2/)
  assert.match(source, /zoomTo\(MIN_SCALE, 0, 0\)/)
  // 回到适配态交给 CSS contain 规则并清空徽标。
  assert.match(source, /if \(scale <= MIN_SCALE \+ 1e-6\)/)
  assert.match(source, /zoomPercent\.value = null/)
})

test('lightbox keeps its escape hatch and overflow-safe centering', () => {
  assert.match(source, /if \(e\.key === 'Escape' && props\.visible\)/)
  // flex 居中会在放大后裁掉上边；margin:auto 才允许四边滚动到达。
  assert.match(source, /\.lightbox-img \{[\s\S]*margin: auto;/)
  assert.match(source, /\.lightbox-body \{[\s\S]*overflow: auto;/)
})

test('zoom badge is pinned to the stage viewport instead of scrolling with content', () => {
  // 徽标挂在滚动容器外层的舞台上绝对定位：不随图片缩放、平移、滚动而移动。
  assert.match(source, /<div class="lightbox-stage">/)
  assert.match(source, /\.lightbox-zoom-badge \{[\s\S]*position: absolute;/)
  assert.match(source, /\.lightbox-zoom-badge \{[\s\S]*right: 12px;[\s\S]*bottom: 12px;/)
})
