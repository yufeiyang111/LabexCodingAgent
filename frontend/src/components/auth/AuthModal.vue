<template>
  <el-dialog
    class="auth-modal"
    :model-value="authModal.visible"
    width="480px"
    align-center
    append-to-body
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="true"
    aria-label="登录或注册"
    @update:model-value="onDialogValueChange"
    @closed="onDialogClosed"
  >
    <button type="button" class="auth-modal__close" aria-label="关闭登录窗口" title="关闭" @click="authModal.close()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
        <path d="M18 6 6 18M6 6l12 12" />
      </svg>
    </button>

    <!--
      认证组件按需挂载：仅在 authModal.visible 为 true 时创建。
      弹窗外壳常驻（el-dialog 关闭时不渲染内容），这样开关过渡正常；
      而真正昂贵的登录/注册表单及其子组件在关闭时会被彻底销毁，不再常驻。
    -->
    <Auth
      v-if="authModal.visible"
      v-model:mode="authModal.mode"
      variant="inline"
      @succeeded="onAuthenticated"
    />
  </el-dialog>
</template>

<script setup>
import { useRouter } from 'vue-router'
import Auth from '@/views/Auth.vue'
import { useAuthModalStore } from '@/stores/authModal'
import { useUserStore } from '@/stores/user'

/**
 * 登录 / 注册弹窗。
 *
 * 由任意「登录」入口（AuthEntryButton 等）通过 useAuthModalStore().open() 唤起，
 * 也可在代码里直接调用。认证成功后按用户身份落位：
 * - 已登录用户停留在当前页面（弹窗关闭即可继续操作）；
 * - 当前路由本身要求登录时，跳转到项目空间。
 */
const router = useRouter()
const authModal = useAuthModalStore()
const userStore = useUserStore()

function onDialogValueChange(value) {
  if (!value) authModal.close()
}

/** 关闭后重置模式，避免下次打开残留上一次的注册态 */
function onDialogClosed() {
  authModal.mode = 'login'
}

async function onAuthenticated() {
  authModal.close()
  const current = router.currentRoute.value
  if (current.meta?.requiresAuth) {
    await router.replace(current.fullPath)
    return
  }
  if (!userStore.userInfo) {
    await userStore.getUserInfo().catch(() => null)
  }
}
</script>

<style scoped>
.auth-modal__close {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #5f6b7c;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.auth-modal__close:hover {
  background: var(--theme-surface-muted);
  color: var(--theme-text);
}
</style>

<style>
/* el-dialog 的类名挂在 teleport 到 body 的节点上，必须用非 scoped 样式命中 */
.auth-modal.el-dialog {
  padding: 24px;
  border-radius: 18px;
  background: var(--theme-surface);
  /*
   * 垂直布局：按内容高度 + 居中，且内容超高时可滚动。
   *
   * 背景（踩过的两个坑，改这里前请先读完）：
   *
   * 坑 1｜被拉伸满高。Element Plus 把遮罩层 `.el-overlay-dialog` 设为 display:flex，
   *    `.el-dialog` 作为 flex 子项默认 align-items: stretch → 面板被拉满整个视口
   *    （实测 820px），而内容只有 540px，底部空出 280px 大片留白。
   *
   * 坑 2｜自己加的 max-height 反而把内容裁死。为压掉留白我曾加
   *    `max-height: calc(100dvh - 32px)`，结果注册态内容 903px 被限制在 812px，
   *    面板 overflow 又是 hidden → 底部 129px（Google 登录、返回链接）手指滑不到。
   *    实测触摸滑动 scrollTop 恒为 0。**绝不能在这里限高**：让内容自然撑开，
   *    超出的部分交给 overlay 的 overflow: auto 滚动。
   *
   * `margin-block` 固定 12px 保证面板永不贴视口边；居中交给 align-self。
   * 这里必须写两行 align-self 做兜底：
   *   第一行给不认识 `safe` 关键字的旧浏览器；
   *   第二行 `safe center` 让内容高于视口时退化为 start 对齐 ——
   *   否则 flex 居中会把溢出的部分顶到视口上方，那段内容永远滚不到。
   * `!important` 用于压过项目里给 `.el-dialog` 设的同值 margin（保持单一来源）。
   */
  margin-block: 12px !important;
  align-self: center;
  align-self: safe center;
}

.auth-modal .el-dialog__header {
  display: none;
}

/*
 * 归零 __body 的内距。
 *
 * 必须写成 `.el-dialog.auth-modal`（特异性 0,3,0）：
 * 全局样式 global.scss 里有 `.el-dialog .el-dialog__body { padding: 24px }`（0,2,0），
 * 与本组件原先的 `.auth-modal .el-dialog__body`（也是 0,2,0）同特异性，
 * 而 global.scss 在源码顺序上更靠后 → 原先那条被静默覆盖，实测内距仍是 24px。
 * 叠加面板自身的 18/16 内距后，窄屏可用宽度被压到 276px，内容溢出、观感拥挤。
 */
.el-dialog.auth-modal .el-dialog__body {
  padding: 0;
  color: var(--theme-text);
}

/*
 * 窄屏：整体等比收缩。
 *
 * 原样式（面板 24px 内距、标题 36px、表头 30px 下边距）是按桌面弹窗设计的，
 * 直接搬到手机会让内容几乎填满整屏且四处拥挤。这里按同一比例收一档，
 * 同时保留 46px 的输入框高度（触屏可点性不降级）。
 */
@media (max-width: 640px) {
  .auth-modal.el-dialog {
    width: calc(100vw - 32px) !important;
    padding: 18px 16px;
    border-radius: 16px;
  }
}
</style>
