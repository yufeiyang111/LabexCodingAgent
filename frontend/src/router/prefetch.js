/*
 * 工作区路由的 chunk 预热。
 *
 * 为什么需要：/workspace/:projectId 是路由级懒加载，router.push 必须等 CloudWorkspace
 * 及其依赖（useProjectExport、github-dark、shared-vendor，合计约 168 KB）下载并解析完
 * 才切换视图。实测 2 Mbps / 200 ms 下这一步要 1075 ms —— 用户看到的就是「点了没反应」。
 * 同一份 chunk 若已在内存里，同样的点击只要 28 ms。
 *
 * 预热只是把「点击后下载」提前成「点击前下载」，不改变最终加载的内容，也不改路由行为。
 *
 * 单独成模块而不是放在 router/index.js 里：项目列表页要 import 它，
 * 而 router/index.js 又动态 import 项目列表页 —— 放一起会形成循环依赖。
 */

let pending = null

export function prefetchWorkspaceRoute() {
  if (!pending) {
    pending = import('@/views/CloudWorkspace.vue').catch(() => {
      // 预热失败不能污染后续导航：清掉缓存，让 router 自己重新 import 一次。
      pending = null
    })
  }
  return pending
}
