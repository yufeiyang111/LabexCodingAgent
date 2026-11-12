/*
 * 工作区预取的响应缓存。
 *
 * 只存 promise，不存业务状态：命中就直接 await，没命中就现取。
 * 刻意不 import 任何 api 模块 —— composable 会在单测里被直接 import，
 * 那里没有 `@` 别名解析；调用方自己把「怎么取」传进来。
 *
 * 缓存是「一次性」的：读一次就删。会话列表、技能目录这些在新建会话、改配置后都会重新拉，
 * 如果留着，刷新拿到的就是旧数据。一次性语义下，预取只影响首屏那一次读取，
 * 之后所有调用照常回源，也就不需要维护失效逻辑。
 */

import { WORKSPACE_PREFETCH_TTL_MS } from '../constants/workspacePrefetch.js'

const cache = new Map()

export const PREFETCH_KEYS = {
  project: id => `project:${id}`,
  modelConfigs: () => 'model-configs',
  attachmentPolicy: id => `attachment-policy:${id}`,
  skills: () => 'skills',
  mcpServers: () => 'mcp-servers',
  skillCatalog: id => `skill-catalog:${id}`,
  conversations: id => `conversations:${id}`,
  commands: id => `commands:${id}`,
  rootTree: id => `root-tree:${id}`
}

/**
 * 提前发起一次请求并记住它。
 *
 * 返回的 promise 永远 resolve（失败给 undefined），所以预取失败不会把错误状态带进页面，
 * 也不会阻止调用方回源。
 */
export function rememberPrefetched(key, load) {
  const hit = cache.get(key)
  if (hit && Date.now() - hit.at <= WORKSPACE_PREFETCH_TTL_MS) return hit.promise

  const promise = Promise.resolve()
    .then(load)
    .catch(() => {
      // 失败的预取不留缓存，下一次调用会重新发起。
      cache.delete(key)
      return undefined
    })
  cache.set(key, { at: Date.now(), promise })
  return promise
}

/**
 * 消费预取结果：命中就用内存里那份并立刻清掉，没命中就现取。
 *
 * 页面只用这一个入口，所以「预取过」和「没预取」是同一条代码路径，
 * 不存在两套分支各自维护状态。预取失败时在这里回源一次，
 * 页面拿到的行为与完全没有预取时一致。
 */
export async function takePrefetched(key, load) {
  const hit = cache.get(key)
  cache.delete(key)
  if (hit && Date.now() - hit.at <= WORKSPACE_PREFETCH_TTL_MS) {
    const value = await hit.promise
    if (value !== undefined) return value
  }
  return load()
}

/** 登出后不能留着上一个账号的数据。 */
export function clearWorkspacePrefetch() {
  cache.clear()
}
