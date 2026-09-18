<template>
  <!-- 未登录时整个组件不渲染：调用方无需再写一次 v-if -->
  <div v-if="userStore.isLoggedIn" ref="rootEl" class="user-menu">
    <button
      type="button"
      class="user-menu__trigger"
      :aria-expanded="open"
      aria-haspopup="menu"
      @click="toggle"
    >
      <span class="user-menu__avatar" aria-hidden="true">{{ initial }}</span>
      <span class="user-menu__greet">欢迎，{{ displayName }}</span>
      <svg class="user-menu__caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <path d="M6 9l6 6 6-6" />
      </svg>
    </button>

    <!-- 点击外部关闭：监听挂在 document 上，用 rootEl 判定范围，避免为每个单元格加监听 -->
    <div v-if="open" class="user-menu__dropdown" role="menu">
      <div class="user-menu__meta">
        <div class="user-menu__name">{{ displayName }}</div>
        <div v-if="secondary" class="user-menu__sub">{{ secondary }}</div>
      </div>
      <button type="button" class="user-menu__item" role="menuitem" @click="goProjects">项目空间</button>
      <button type="button" class="user-menu__item user-menu__item--danger" role="menuitem" @click="handleLogout">退出登录</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 顶栏用户菜单：已登录时取代「登录」按钮，展示头像、欢迎语与账号操作。
 *
 * 之所以独立成组件：落地页顶栏与其它公开页顶栏都要用同一份展示与登出逻辑；
 * 写第二份会导致「退出后落点」这类行为在两处漂移。
 *
 * 落点约定（与产品要求一致）：登录页只是子页面，退出后回首页而非登录页。
 */
const router = useRouter()
const userStore = useUserStore()

const rootEl = ref(null)
const open = ref(false)

/** 昵称优先，其次用户名，最后兜底，避免出现空白的欢迎语 */
const displayName = computed(() => {
  const info = userStore.userInfo || {}
  return info.displayName || info.username || info.email || '用户'
})

/** 副标题：不重复展示已在欢迎语里出现的名字 */
const secondary = computed(() => {
  const info = userStore.userInfo || {}
  const candidates = [info.email, info.username, info.inviteCode ? `邀请码 ${info.inviteCode}` : '']
  return candidates.find(value => value && value !== displayName.value) || ''
})

const initial = computed(() => Array.from(displayName.value)[0]?.toUpperCase() ?? '?')

function toggle() {
  open.value = !open.value
}

function onDocumentPointerDown(event) {
  if (!open.value) return
  if (rootEl.value && !rootEl.value.contains(event.target)) open.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onKeydown)
})

function goProjects() {
  open.value = false
  router.push({ name: 'Projects' })
}

function handleLogout() {
  open.value = false
  userStore.logout()
  // 退出后回首页：登录页只作为子页面，不作为默认落地点
  router.push('/')
}
</script>

<style scoped>
.user-menu { position: relative; }

.user-menu__trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 10px 0 5px;
  border: 1px solid var(--theme-border);
  border-radius: 999px;
  background: var(--theme-surface);
  color: var(--theme-text);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.user-menu__trigger:hover { border-color: var(--theme-accent); background: var(--theme-surface-muted); }
.user-menu__trigger:focus-visible { outline: 2px solid var(--theme-accent); outline-offset: 2px; }

.user-menu__avatar {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--theme-accent);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}

/* 长昵称截断而非撑破顶栏 */
.user-menu__greet {
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-menu__caret { width: 13px; height: 13px; flex: 0 0 auto; color: var(--theme-text-muted); }

/* 浮层需要投影表达层级（非 hover 反馈），属设计规范里允许保留阴影的场景 */
.user-menu__dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 40;
  min-width: 184px;
  padding: 6px;
  border: 1px solid var(--theme-border);
  border-radius: 10px;
  background: var(--theme-surface);
  box-shadow: var(--theme-shadow);
}

.user-menu__meta { padding: 8px 10px 10px; margin-bottom: 4px; border-bottom: 1px solid var(--theme-border); }
.user-menu__name { color: var(--theme-text); font-size: 13px; font-weight: 600; }
.user-menu__sub { margin-top: 2px; color: var(--theme-text-muted); font-size: 11px; }

.user-menu__item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--theme-text);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.user-menu__item:hover { background: var(--theme-surface-muted); }
.user-menu__item--danger { color: var(--theme-danger); }

@media (max-width: 640px) {
  /* 窄屏只留头像，避免欢迎语把顶栏挤爆 */
  .user-menu__greet { display: none; }
}
</style>
