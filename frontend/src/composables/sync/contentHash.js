// 唯一职责：为同步内容提供稳定哈希（用于 journal 去重标记与审计）。
// 边界：冲突判定由 changeSetSyncPlan 以「原文比较」完成，哈希不参与判定——
// 避免"哈希相同"与"内容一致"两套语义并存造成歧义。
// 注意：WebCrypto 仅在安全上下文（https / localhost）可用；不可用时**不静默降级**，
// 由调用方显式选择 trySha256Hex（返回 null）或接受抛错。

const ALGORITHM = 'SHA-256'

export function resolveCrypto(cryptoLike) {
  const candidate = cryptoLike ?? globalThis.crypto
  if (!candidate || typeof candidate !== 'object') return null
  if (!candidate.subtle || typeof candidate.subtle.digest !== 'function') return null
  return candidate
}

export function isHashingAvailable(cryptoLike) {
  return resolveCrypto(cryptoLike) !== null
}

export async function sha256Hex(text, options = {}) {
  const cryptoApi = resolveCrypto(options.crypto)
  if (!cryptoApi) {
    throw new Error('WebCrypto unavailable: hash requires a secure context (https:// or localhost)')
  }
  const value = typeof text === 'string' ? text : ''
  const bytes = new TextEncoder().encode(value)
  const digest = await cryptoApi.subtle.digest(ALGORITHM, bytes)
  return toHex(new Uint8Array(digest))
}

// 返回 null 表示"当前环境无法计算哈希"（调用方必须显式处理，不得当作空串）。
export async function trySha256Hex(text, options = {}) {
  if (!isHashingAvailable(options.crypto)) return null
  try {
    return await sha256Hex(text, options)
  } catch {
    return null
  }
}

export function toHex(bytes) {
  let output = ''
  for (const byte of bytes) output += byte.toString(16).padStart(2, '0')
  return output
}
