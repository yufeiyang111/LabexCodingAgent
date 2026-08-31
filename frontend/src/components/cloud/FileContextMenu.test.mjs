import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('FileContextMenu clamps itself inside the viewport near screen edges', async () => {
  const source = await readFile(new URL('./FileContextMenu.vue', import.meta.url), 'utf8')
  // 位置必须用调整后的坐标渲染，而不是原始光标坐标。
  assert.match(source, /:style="\{ top: adjusted\.y \+ 'px', left: adjusted\.x \+ 'px' \}"/)
  // 测量布局尺寸（不受 transform 动画影响）后再决定是否翻转/钳制。
  assert.match(source, /el\.offsetHeight/)
  assert.match(source, /el\.offsetWidth/)
  // 贴底时向上翻转，翻转后仍越界则钳制在视口内；横向同理。
  assert.match(source, /flipped = props\.y - menuHeight/)
  assert.match(source, /viewHeight - menuHeight - VIEWPORT_MARGIN/)
  assert.match(source, /flipped = props\.x - menuWidth/)
})
