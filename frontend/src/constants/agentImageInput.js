// Agent 图片输入的前端默认策略；必须与后端 labex-agent.attachment 配置一致。
export const DEFAULT_AGENT_IMAGE_INPUT_POLICY = Object.freeze({
  maxFilesPerMessage: 4,
  maxFileSizeBytes: 20 * 1024 * 1024,
  maxTotalSizeBytes: 40 * 1024 * 1024,
  allowedMimeTypes: ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
})

export function imageAcceptValue(mimeTypes = []) {
  return mimeTypes.length > 0 ? mimeTypes.join(',') : 'image/jpeg,image/png,image/gif,image/webp'
}
