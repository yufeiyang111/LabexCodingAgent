<template>
  <main class="tutorial-page">
    <header class="tutorial-page__topbar">
      <button type="button" class="tutorial-page__back" @click="goBack">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
        <span>{{ backLabel }}</span>
      </button>
      <div class="tutorial-page__brand"><AppIcon :size="26" compact /><span>使用教程</span></div>
      <div class="tutorial-page__topbar-spacer"></div>
      <div class="tutorial-page__progress" aria-hidden="true">
        <div class="tutorial-page__progress-bar" :style="{ transform: `scaleX(${readingProgress})` }"></div>
      </div>
    </header>
    <div class="tutorial-page__layout">
      <TutorialSidebar :items="items" :active-slug="activeSlug" :loading="listLoading" :error="listError" @select="selectTutorial" />
      <section class="tutorial-page__content" aria-live="polite">
        <Transition name="article-swap" mode="out-in">
          <TutorialLoadingState v-if="detailLoading" key="loading" />
          <TutorialLoadingState v-else-if="detailError" key="error" :error="detailError" @retry="loadDetail(activeSlug)" />
          <TutorialArticle v-else :key="activeSlug" :document="document" />
        </Transition>
      </section>
      <TutorialOutline :headings="headings" :active-id="activeHeadingId" />
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
import TutorialArticle from '@/components/tutorial/TutorialArticle.vue'
import TutorialLoadingState from '@/components/tutorial/TutorialLoadingState.vue'
import TutorialOutline from '@/components/tutorial/TutorialOutline.vue'
import TutorialSidebar from '@/components/tutorial/TutorialSidebar.vue'
import { headingId } from '@/components/tutorial/tutorialHeading'
import AppIcon from '@/components/AppIcon.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const items = ref([])
const document = ref(null)
const listLoading = ref(false)
const listError = ref('')
const detailLoading = ref(false)
const detailError = ref('')
const readingProgress = ref(0)
const activeHeadingId = ref('')
const showBackTop = ref(false)
const activeSlug = computed(() => String(route.params.slug || items.value[0]?.slug || ''))
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
    const requestedSlug = String(route.params.slug || '')
    const hasRequestedTutorial = requestedSlug && items.value.some(item => item.slug === requestedSlug)
    if (!hasRequestedTutorial && items.value[0]?.slug) {
      await router.replace({ name: 'Tutorials', params: { slug: items.value[0].slug } })
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
  if (slug === route.params.slug) return
  await router.push({ name: 'Tutorials', params: { slug } })
}

watch(() => route.params.slug, slug => {
  if (slug && items.value.length > 0) void loadDetail(slug)
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
})
</script>
