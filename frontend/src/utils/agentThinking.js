const WAITING_INPUT_MARKERS = ['等待用户输入', 'Waiting for user input']

const FAILURE_MARKERS = /遇到错误|执行失败|没有成功|Command failed|Error on|Failed to execute/

/**
 * 识别历史遗留的错误叙述：等待用户输入是正常暂停，不是工具失败。
 * 旧版后端会在提问暂停前把 "正在等待用户输入。" 叙述成失败（"在 xx 上遇到错误：正在等待用户输入。..."），
 * 这些 THINK 块已持久化为事件；新后端不再产生，但历史回放时需要过滤掉。
 */
export function isWaitingInputMisNarration(content = '') {
  if (!content) return false
  const text = String(content)
  const hasWaitingMarker = WAITING_INPUT_MARKERS.some(marker => text.includes(marker))
  if (!hasWaitingMarker) return false
  return FAILURE_MARKERS.test(text)
}
