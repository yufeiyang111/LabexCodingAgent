<template>
  <header class="chrome" :class="{ 'is-scrolled': scrolled }">
    <div class="wrap chrome__inner">
      <span class="brand">
        <BrandMark class="brand__mark" />
        <span class="brand__wordmark">LabexAgent</span>
      </span>

      <nav class="chrome__nav">
        <template v-for="item in SITE_NAV" :key="item.to">
          <!--
            需登录的导航项在未登录时改为唤起登录弹窗：
            直接跳过去只会撞上路由守卫再被送回首页，等于原地打转。
            公开页（教程）保持正常链接。
          -->
          <button
            v-if="item.requiresAuth && !userStore.isLoggedIn"
            type="button"
            class="chrome__link"
            @click="goProtected(item.to)"
          >{{ item.label }}</button>
          <RouterLink v-else class="chrome__link" :to="item.to">{{ item.label }}</RouterLink>
        </template>

        <button
          type="button"
          class="chrome__link"
          :aria-pressed="isDark"
          :aria-label="isDark ? '切换浅色主题' : '切换暗色主题'"
          title="切换主题"
          @click="themeStore.toggleLightDark()"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
          </svg>
        </button>

        <!-- 已登录时用用户菜单取代登录按钮（欢迎语 + 账号操作） -->
        <UserMenu v-if="userStore.isLoggedIn" />
        <button v-else type="button" class="btn btn--sm" @click="openAuth('login')">登录</button>
      </nav>
    </div>

    <!-- 阅读进度：由 --scroll-progress 驱动，避免每帧改 style 触发重排 -->
    <div class="progress" aria-hidden="true"><span class="progress__bar" :style="{ '--scroll-progress': progress }"></span></div>
  </header>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import BrandMark from '@/components/landing/BrandMark.vue'
import UserMenu from '@/components/landing/UserMenu.vue'
import { useLandingAuth } from '@/composables/landing/useLandingAuth.js'
import { SITE_NAV } from '@/data/landing/landingContent.js'
import { useThemeStore } from '@/stores/theme'
import { useUserStore } from '@/stores/user'

/**
 * 站点顶栏。
 *
 * 滚动相关只有两个用途：过了首屏浮出背景（is-scrolled），以及顶部阅读进度条。
 * 二者同源，因此合并在一个 rAF 节流监听里处理——拆成两个监听只会让滚动回调翻倍。
 */
const themeStore = useThemeStore()
const userStore = useUserStore()
const router = useRouter()

const isDark = computed(() => themeStore.effectiveTheme === 'dark')
const { openAuth } = useLandingAuth()

const scrolled = ref(false)
const progress = ref(0)
let ticking = false

function readScroll() {
  const y = window.scrollY || 0
  scrolled.value = y > 12
  const scrollable = document.documentElement.scrollHeight - window.innerHeight
  progress.value = scrollable > 0 ? Math.min(1, Math.max(0, y / scrollable)) : 0
  ticking = false
}

function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(readScroll)
}

/*
 * 未登录时点「项目空间」：唤起登录弹窗，并记住这个意图。
 * 登录成功后自动进入项目空间——否则用户点了「项目空间」、登录完却停在首页，会以为没生效。
 * 意图是本组件内的局部 UI 状态，不需要进全局 store。
 */
const pendingRoute = ref('')

function goProtected(target) {
  pendingRoute.value = target
  openAuth('login')
}

watch(() => userStore.isLoggedIn, loggedIn => {
  if (!loggedIn || !pendingRoute.value) return
  const target = pendingRoute.value
  pendingRoute.value = ''
  router.push(target)
})

onMounted(() => {
  readScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
})
</script>
