/*
 * 工作区首屏数据预取。
 *
 * 为什么需要：进入 /workspace/:projectId 后，CloudWorkspace 必须等 project detail 返回
 * 才能并发发起其余 5 个请求，每个都要一个完整 RTT。实测 2 Mbps / 200 ms 下，
 * 从点击到文件树出现 1063 ms；把这些响应换成内存直出后是 312 ms。
 * 这里把「挂载后才取」提前成「挂载前就取」，挂载时直接命中。
 *
 * 与 router/prefetch.js 的分工：那边预热 chunk（代码），这边预热数据。
 * 两者都只是提前取，不改变最终加载的内容。
 *
 * 只预取小响应：项目详情、模型配置、附件策略、技能/MCP、命令目录、会话列表、根目录树，
 * 合计约 31 KB。会话历史（单个 2 MB 量级）和文件内容不在预取范围。
 */

import { projectApi, modelConfigApi } from '@/api'
import { PREFETCH_KEYS, rememberPrefetched } from '@/composables/workspacePrefetchCache'

export function prefetchWorkspaceData(projectId) {
  const id = Number(projectId)
  if (!Number.isFinite(id) || id <= 0) return Promise.resolve()

  // 顺序有讲究：浏览器对同一域名只有 6 条并发连接，根目录树是首屏最慢、最关键的一项
  // （服务端 90–390 ms，随条目数增长），必须排在最前面抢到连接槽，
  // 否则用户 hover 后立刻点击时，它会排在 5 个配置请求后面等连接。
  // 缓存一次性消费：进入工作区后 loadRoot 拿走它，之后的刷新照常回源。
  return Promise.all([
    rememberPrefetched(PREFETCH_KEYS.rootTree(id), () => projectApi.getTreePage(id, '', 0)),
    rememberPrefetched(PREFETCH_KEYS.project(id), () => projectApi.detail(id)),
    rememberPrefetched(PREFETCH_KEYS.conversations(id), () => projectApi.agentConversations(id)),
    rememberPrefetched(PREFETCH_KEYS.commands(id), () => projectApi.agentCommands(id)),
    rememberPrefetched(PREFETCH_KEYS.modelConfigs(), () => modelConfigApi.list()),
    rememberPrefetched(PREFETCH_KEYS.skills(), () => projectApi.listSkills()),
    rememberPrefetched(PREFETCH_KEYS.mcpServers(), () => projectApi.listMcpServers()),
    rememberPrefetched(PREFETCH_KEYS.skillCatalog(id), () => projectApi.getSkillCatalog(id)),
    rememberPrefetched(PREFETCH_KEYS.attachmentPolicy(id), () => projectApi.agentImageAttachmentPolicy(id))
  ]).then(() => undefined)
}
