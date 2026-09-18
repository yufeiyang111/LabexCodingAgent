import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api'

/**
 * 认证能力：由后端 /auth/config 下发的「是否需要邀请码」「支持哪些第三方登录」。
 *
 * 为什么要抽成 store：这两项能力同时被落地页（决定 CTA 显示哪几个按钮）与认证表单
 * （决定注册表单的字段、是否渲染第三方登录）消费。若各自请求，会产生重复请求，
 * 且两处状态可能瞬时不一致（例如 hero 显示「创建账号」而表单已切到邀请码模式）。
 * 这里作为唯一事实源，并提供并发去重。
 *
 * 失败回落策略：请求失败 ≠ 后端下线了这些能力。一次后端抖动或重启若把能力清空，
 * 第三方登录区会整块消失，用户无法区分「被下线」与「暂时不可用」。
 * 因此成功即写缓存、失败回落上次已确认的值，绝不无依据清空。
 * 用 sessionStorage 而非 localStorage：随标签页生命周期结束，
 * 既覆盖「后端重启时刷新页面」这一最常见场景，又不会把旧配置长期固化。
 */
const CACHE_KEY = 'labex_auth_capabilities'

function readCachedCapabilities() {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    // 隐私模式或禁用存储时读取会抛异常；此处降级为「无缓存」
    return null
  }
}

function writeCachedCapabilities(next) {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify(next))
  } catch {
    // 写不进去不影响本次会话功能，忽略即可
  }
}

/** 后端返回形状：{ providers: [...] }，也兼容直接返回数组的旧格式 */
function normalizeProviders(raw) {
  if (Array.isArray(raw?.providers)) return raw.providers
  if (Array.isArray(raw)) return raw
  return []
}

export const useAuthCapabilitiesStore = defineStore('authCapabilities', () => {
  const cached = readCachedCapabilities()
  const inviteCodeEnabled = ref(cached?.inviteCodeEnabled ?? false)
  const oauthProviders = ref(Array.isArray(cached?.oauthProviders) ? cached.oauthProviders : [])
  const loaded = ref(false)

  /** 并发的请求合并为一个：落地页与认证表单可能同时挂载 */
  let inflight = null

  const hasOAuthProviders = computed(() => oauthProviders.value.length > 0)

  function fallbackToCache() {
    const fallback = readCachedCapabilities()
    inviteCodeEnabled.value = fallback?.inviteCodeEnabled ?? false
    oauthProviders.value = Array.isArray(fallback?.oauthProviders) ? fallback.oauthProviders : []
  }

  /**
   * 拉取一次并缓存结果。
   * @param {{ force?: boolean }} options force 为真时忽略「已加载」标记重新请求
   */
  async function ensureLoaded(options = {}) {
    if (loaded.value && !options.force) return
    if (inflight) return inflight
    inflight = (async () => {
      try {
        const response = await authApi.getAuthConfig({ silent: true })
        const inviteEnabled = Boolean(response?.data?.inviteCodeEnabled)
        const list = normalizeProviders(response?.data?.oauthProviders)
        inviteCodeEnabled.value = inviteEnabled
        oauthProviders.value = list
        writeCachedCapabilities({ inviteCodeEnabled: inviteEnabled, oauthProviders: list })
      } catch {
        fallbackToCache()
      } finally {
        loaded.value = true
        inflight = null
      }
    })()
    return inflight
  }

  return { inviteCodeEnabled, oauthProviders, hasOAuthProviders, loaded, ensureLoaded }
})
