import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 登录 / 注册弹窗的唯一开关与模式来源。
 *
 * 设计约束（AGENTS.md 第 1、2 节「唯一事实源」）：
 * 1. 认证表单不常驻渲染。弹窗外壳常驻于 App.vue，内部的认证组件由 `visible` 控制挂载，
 *    关闭时立即销毁，因此登录/注册组件只在用户主动唤起时存在于 DOM 中。
 * 2. 模式（login / register）由本 store 单一持有。页面与弹窗都读取同一份状态，不各自维护副本，
 *    保证「切换到注册」后无论从哪个入口打开都一致。
 */

/** 与 AuthPanel 的 mode 取值保持一致，避免出现第三套字面量 */
export const AUTH_MODES = Object.freeze({
  login: 'login',
  register: 'register'
})

function normalizeMode(mode) {
  return mode === AUTH_MODES.register ? AUTH_MODES.register : AUTH_MODES.login
}

export const useAuthModalStore = defineStore('authModal', () => {
  /** 弹窗是否打开（同时决定认证组件是否挂载） */
  const visible = ref(false)
  /** 当前处于登录还是注册 */
  const mode = ref(AUTH_MODES.login)

  function open(nextMode) {
    mode.value = normalizeMode(nextMode)
    visible.value = true
  }

  function close() {
    visible.value = false
  }

  function toggleMode() {
    mode.value = toggle(mode.value)
  }

  function toggle(current) {
    return current === AUTH_MODES.login ? AUTH_MODES.register : AUTH_MODES.login
  }

  return { visible, mode, open, close, toggleMode }
})
