import { computed } from 'vue'
import { useAuthModalStore } from '@/stores/authModal'
import { useUserStore } from '@/stores/user'

/**
 * 落地页的登录入口行为。
 *
 * 为什么不直接用 AuthEntryButton：
 * 该组件被设计为「一个登录主按钮 + 可选的创建账号次按钮」，且内部样式与落地页
 * 所需的 .btn / .btn--ghost 不一致；而它的逻辑又被 authModal.test.mjs 以源码
 * 字符串断言锁定（必须在该文件内出现 useAuthModalStore / authModal.open('login')
 * / userStore.isLoggedIn），无法抽出复用。
 *
 * 因此这里只做一层**极薄**的行为封装：不新增状态、不改协议，
 * 认证弹窗的开关仍是 authModal store 这一唯一事实源。
 * 「已登录就不再提示」的判定与 AuthEntryButton 保持一致，避免两处行为漂移。
 */
export function useLandingAuth() {
  const authModal = useAuthModalStore()
  const userStore = useUserStore()

  const canPrompt = computed(() => !userStore.isLoggedIn)

  /** 打开认证弹窗；已登录时不做任何事并返回 false */
  function openAuth(mode = 'login') {
    if (!canPrompt.value) return false
    authModal.open(mode)
    return true
  }

  return { canPrompt, openAuth }
}
