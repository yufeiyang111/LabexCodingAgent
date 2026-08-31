/** 工作区文件操作共享常量与纯函数；禁止在组件里内联扩展名判断等魔法数据。 */

export const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'ico']

/** 后端明确拒绝 SVG 预览（存储型 XSS），前端菜单同样不展示预览入口。 */
export const PREVIEW_BLOCKED_EXTENSIONS = ['svg', 'svgz']

const IMAGE_SET = new Set(IMAGE_EXTENSIONS)
const BLOCKED_SET = new Set(PREVIEW_BLOCKED_EXTENSIONS)

export function extensionOf(path) {
  const name = String(path || '').split('/').pop() || ''
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

export function isImagePath(path) {
  const ext = extensionOf(path)
  if (BLOCKED_SET.has(ext)) return false
  return IMAGE_SET.has(ext)
}

export function parentPathOf(path) {
  const normalized = String(path || '').replace(/\/+$/, '')
  const slash = normalized.lastIndexOf('/')
  return slash < 0 ? '' : normalized.slice(0, slash)
}

export function fileNameOf(path) {
  const normalized = String(path || '').replace(/\/+$/, '')
  const slash = normalized.lastIndexOf('/')
  return slash < 0 ? normalized : normalized.slice(slash + 1)
}

/** 触发浏览器保存已下载的 blob。 */
export function saveBlobAs(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName || 'download'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 4000)
}

export async function copyTextToClipboard(text) {
  if (!navigator.clipboard?.writeText) {
    throw new Error('剪贴板不可用（需要 HTTPS 环境）')
  }
  await navigator.clipboard.writeText(text)
}
