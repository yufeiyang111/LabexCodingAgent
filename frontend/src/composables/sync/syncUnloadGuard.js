// 唯一职责：在同步进行中阻止用户误关页面，避免丢掉未提交的进度。
// 边界：只管理 beforeunload 守卫与计数，不参与同步逻辑、不持有会话状态。
//
// 为什么需要它：目录句柄只在页面存活期间可用，"关掉标签页后继续写盘"在浏览器里
// 无法实现（那是 daemon / L3 的职责）。但用户误关页面时，浏览器可以拦一下——
// 同步已经跑了 3 分钟，突然关掉会丢掉尚未提交的进度。

const MESSAGE = '同步正在进行中，离开页面会中断本次同步。确定要离开吗？'

export function createUnloadGuard() {
  let depth = 0
  let target = null
  let handler = null

  function shouldWarn() {
    return depth > 0
  }

  function begin() {
    depth += 1
  }

  function end() {
    depth = Math.max(0, depth - 1)
  }

  function onBeforeUnload(event) {
    if (!shouldWarn()) return
    // 标准做法：preventDefault + returnValue。现代浏览器忽略自定义文案，
    // 但仍会弹出通用确认框——这正是我们要的拦截效果。
    event.preventDefault()
    event.returnValue = MESSAGE
    return MESSAGE
  }

  function attach(targetLike) {
    if (!targetLike || typeof targetLike.addEventListener !== 'function') return
    detach()
    target = targetLike
    handler = onBeforeUnload
    target.addEventListener('beforeunload', handler)
  }

  function detach() {
    if (target && handler && typeof target.removeEventListener === 'function') {
      target.removeEventListener('beforeunload', handler)
    }
    target = null
    handler = null
  }

  return {
    get active() { return shouldWarn() },
    get depth() { return depth },
    shouldWarn,
    begin,
    end,
    attach,
    detach
  }
}
