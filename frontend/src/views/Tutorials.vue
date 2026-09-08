<template>
  <main class="tutorial-page">
    <header class="tutorial-page__topbar">
      <button type="button" class="tutorial-page__back" @click="goBack">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
        <span>{{ backLabel }}</span>
      </button>
      <div class="tutorial-page__brand"><AppIcon :size="26" compact /><span>使用教程</span></div>
      <div class="tutorial-page__topbar-spacer"></div>
      <button
        v-if="isNarrow"
        type="button"
        class="tutorial-page__menu-btn"
        :class="{ active: showMobileDrawer }"
        title="章节目录"
        @click="showMobileDrawer = !showMobileDrawer"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
        <span>目录</span>
      </button>
      <button
        v-if="isNarrow && headings.length > 0"
        type="button"
        class="tutorial-page__menu-btn"
        :class="{ active: showMobileOutline }"
        title="本文大纲"
        @click="showMobileOutline = !showMobileOutline"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
        <span>大纲</span>
      </button>
      <button
        type="button"
        class="tutorial-page__theme-btn"
        title="设置与主题"
        @click="themeStore.openSettings()"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
      </button>
      <div class="tutorial-page__progress" aria-hidden="true">
        <div class="tutorial-page__progress-bar" :style="{ transform: `scaleX(${readingProgress})` }"></div>
      </div>
    </header>
    <div class="tutorial-page__layout" :class="{ 'mobile-drawer-open': showMobileDrawer, 'drawer-mode': isNarrow }">
      <div v-if="isNarrow && showMobileDrawer" class="tutorial-mobile-drawer-mask" @click="showMobileDrawer = false"></div>
      <div v-if="isNarrow && showMobileOutline" class="tutorial-mobile-drawer-mask" @click="showMobileOutline = false"></div>
      <TutorialSidebar
        :class="{ 'is-mobile-drawer': isNarrow }"
        :items="items"
        :active-slug="activeSlug"
        :loading="listLoading"
        :error="listError"
        @select="selectTutorial"
      />
      <section class="tutorial-page__content" aria-live="polite">
        <Transition name="article-swap" mode="out-in">
          <TutorialLoadingState v-if="detailLoading" key="loading" />
          <TutorialLoadingState v-else-if="detailError" key="error" :error="detailError" @retry="loadDetail(activeSlug)" />
          <TutorialArticle v-else :key="activeSlug" :document="document" />
        </Transition>
      </section>
      <TutorialOutline
        :headings="headings"
        :active-id="activeHeadingId"
        :mobile-open="isNarrow && showMobileOutline ? true : null"
        @close="showMobileOutline = false"
      />
    </div>
    <Transition name="totop">
      <button v-if="showBackTop" type="button" class="tutorial-page__back-top" aria-label="回到顶部" @click="scrollTop">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
      </button>
    </Transition>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import '@/styles/tutorials.scss'
import { useRoute, useRouter } from 'vue-router'
import { tutorialApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'
import { useResponsive } from '@/composables/useResponsive'
import TutorialArticle from '@/components/tutorial/TutorialArticle.vue'
import TutorialLoadingState from '@/components/tutorial/TutorialLoadingState.vue'
import TutorialOutline from '@/components/tutorial/TutorialOutline.vue'
import TutorialSidebar from '@/components/tutorial/TutorialSidebar.vue'
import { headingId } from '@/components/tutorial/tutorialHeading'
import AppIcon from '@/components/AppIcon.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const themeStore = useThemeStore()
const { windowWidth } = useResponsive()
// 窄屏（手机 / 平板 / 窄桌面窗口 <1200px）：目录侧边栏默认折叠，顶栏按钮按需唤出
const isNarrow = computed(() => windowWidth.value < 1200)
const showMobileDrawer = ref(false)
const showMobileOutline = ref(false)
const items = ref([])
const document = ref(null)
const listLoading = ref(false)
const listError = ref('')
const detailLoading = ref(false)
const detailError = ref('')
const readingProgress = ref(0)
const activeHeadingId = ref('')
const showBackTop = ref(false)
const currentSlug = computed(() => String(route.params.slug || route.params.id || ''))
const activeSlug = computed(() => currentSlug.value || items.value[0]?.slug || '')
const backLabel = computed(() => userStore.isLoggedIn ? '返回项目' : '返回登录')

function goBack() {
  router.push({ name: userStore.isLoggedIn ? 'Projects' : 'Login' })
}

const headings = computed(() => {
  const markdown = document.value?.contentMarkdown || ''
  return markdown.split('\n').map(line => {
    const match = /^(#{1,3})\s+(.+?)\s*$/.exec(line)
    if (!match) return null
    const text = match[2].replace(/[`*_]/g, '')
    return { level: match[1].length, text, id: headingId(text) }
  }).filter(Boolean)
})

let scrollTicking = false
function updateReadingState() {
  const doc = window.document
  const root = doc.documentElement
  const max = root.scrollHeight - root.clientHeight
  readingProgress.value = max > 0 ? Math.min(1, Math.max(0, root.scrollTop / max)) : 0
  showBackTop.value = root.scrollTop > 480
  let current = ''
  for (const heading of headings.value) {
    const el = doc.getElementById(heading.id)
    if (!el) continue
    if (el.getBoundingClientRect().top <= 124) current = heading.id
    else break
  }
  activeHeadingId.value = current
}

function onScroll() {
  if (scrollTicking) return
  scrollTicking = true
  window.requestAnimationFrame(() => {
    scrollTicking = false
    updateReadingState()
  })
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function loadList() {
  listLoading.value = true
  listError.value = ''
  try {
    const response = await tutorialApi.list()
    items.value = response.data || []
    const requestedSlug = currentSlug.value
    const hasRequestedTutorial = requestedSlug && items.value.some(item => item.slug === requestedSlug)
    if (!hasRequestedTutorial && items.value[0]?.slug) {
      const defaultSlug = items.value[0].slug
      await router.replace({ name: 'TutorialDetail', params: { slug: defaultSlug } })
      await loadDetail(defaultSlug)
      return
    }
    if (requestedSlug) await loadDetail(requestedSlug)
  } catch (error) {
    listError.value = error?.message || '教程目录加载失败'
  } finally {
    listLoading.value = false
  }
}

async function loadDetail(slug) {
  if (!slug) { document.value = null; return }
  detailLoading.value = true
  detailError.value = ''
  try {
    const response = await tutorialApi.get(slug)
    document.value = response.data || null
  } catch (error) {
    document.value = null
    detailError.value = error?.response?.status === 404 ? '教程不存在或尚未发布' : (error?.message || '教程加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function selectTutorial(slug) {
  if (isNarrow.value) {
    showMobileDrawer.value = false
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  if (!slug || slug === currentSlug.value) return
  await router.push({ name: 'TutorialDetail', params: { slug } })
}

watch(currentSlug, slug => {
  if (slug && items.value.length > 0) void loadDetail(slug)
})

// 抽屉/大纲打开时锁定背景滚动（iOS 上 overflow:hidden 对 body 生效即可）
watch([showMobileDrawer, showMobileOutline], ([drawer, outline]) => {
  window.document.body.classList.toggle('tutorial-scroll-locked', Boolean(drawer || outline))
})

// 拉宽回桌面时自动收起抽屉，避免状态残留
watch(isNarrow, narrow => {
  if (!narrow) {
    showMobileDrawer.value = false
    showMobileOutline.value = false
  }
})

watch(document, () => { nextTick(updateReadingState) })

onMounted(() => {
  void loadList()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
  updateReadingState()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
  window.document.body.classList.remove('tutorial-scroll-locked')
})
</script>
