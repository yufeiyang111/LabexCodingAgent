<template>
  <div ref="rootRef" class="tutorial-page legal-page">
    <header class="tutorial-page__topbar">
      <button type="button" class="tutorial-page__back" @click="goBack">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M19 12H5M12 19l-7-7 7-7" />
        </svg>
        返回
      </button>
      <span class="tutorial-page__brand">
        <!--
          尺寸必须显式给：.tutorial-page__brand 是 flex 容器，而 BrandMark 自身没有宽高约束
          （display:block + 无尺寸），在 flex 里会被压成极窄的一条，把旁边的文字挤到竖排换行。
          与教程页保持一致，统一用 AppIcon 承担「指定尺寸」的职责。
        -->
        <AppIcon :size="26" compact />
        <span>法律条款</span>
      </span>
      <span class="tutorial-page__topbar-spacer" />

      <nav class="legal-page__tabs" aria-label="法律文档">
        <RouterLink
          v-for="slug in LEGAL_ORDER"
          :key="slug"
          class="legal-page__tab"
          :class="{ 'is-active': slug === document.slug }"
          :to="legalPath(slug)"
        >{{ LEGAL_DOCUMENTS[slug].title }}</RouterLink>
      </nav>
    </header>

    <main class="legal-page__layout">
      <article class="tutorial-article legal-page__article">
        <header class="tutorial-article__header">
          <div class="tutorial-article__category">{{ document.category }}</div>
          <h1>{{ document.title }}</h1>
          <p class="tutorial-article__summary">{{ document.summary }}</p>
          <div class="tutorial-article__meta">更新于 {{ formatDate(document.updatedAt) }}</div>
        </header>

        <!--
          复用教程页的 markdown 渲染与排版（renderTutorialHtml + .tutorial-article__body）。
          理由：markdown 的正文排版规则很多（标题层级、列表、表格、代码块、引用），
          法律页的阅读体验也应与教程一致，另起一套只会产生两处需要同步维护的样式。
        -->
        <div class="tutorial-article__body markdown-rendered" v-html="renderedContent"></div>
      </article>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { enhanceTutorialBody, renderTutorialHtml } from '@/components/tutorial/tutorialMarkdown'
// 复用教程页样式：本页的骨架类（tutorial-page__*）与正文排版都来自它
import '@/styles/tutorials.scss'
import { LEGAL_DOCUMENTS, LEGAL_ORDER, legalPath } from '@/data/legal/legalDocuments.js'

/**
 * 法律文档页（使用说明 / 隐私说明 / 开源许可）。
 *
 * 设计取舍：复用教程页的类名而不是另建一套 legal-* 样式。
 * 这三页与教程同属「长文阅读」，视觉要求一致（纯白底、居中正文、同样的正文排版）；
 * 若新建一套，465 行排版规则会被迫维护两份，是典型的重复实现。
 * 仅在本组件内补充法律页独有的部分（顶部切换标签与阅读宽度）。
 */
const route = useRoute()
const router = useRouter()

const rootRef = ref(null)

const document = computed(() => {
  const slug = String(route.params.slug || '')
  return LEGAL_DOCUMENTS[slug] || LEGAL_DOCUMENTS.terms
})

const renderedContent = computed(() => renderTutorialHtml(document.value.content))

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN', { dateStyle: 'long' })
}

/**
 * 正文增强（代码块复制按钮等）。
 * 必须在 DOM 渲染完成后再处理，且正文随 slug 变化时需重新执行，否则切换文档后新增的代码块没有按钮。
 */
function enhance() {
  nextTick(() => {
    enhanceTutorialBody(rootRef.value?.querySelector('.tutorial-article__body'))
  })
}

onMounted(enhance)
watch(() => document.value.slug, enhance)

/** 返回：优先回上一页；直接从外部打开时回首页，避免用户无路可走 */
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<style scoped>
.legal-page__layout {
  display: flex;
  justify-content: center;
  padding: 40px 24px 72px;
}

.legal-page__article {
  width: min(100%, 820px);
}

/* 顶部三份文档的切换：窄屏横向滚动，不挤压品牌与返回按钮 */
.legal-page__tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  overflow-x: auto;
  scrollbar-width: none;
}

.legal-page__tabs::-webkit-scrollbar { display: none; }

.legal-page__tab {
  padding: 6px 12px;
  border-radius: 8px;
  color: var(--tut-text-secondary);
  font-size: 13px;
  white-space: nowrap;
  text-decoration: none;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.legal-page__tab:hover { background: var(--tut-accent-soft); color: var(--tut-accent); }
.legal-page__tab.is-active { background: var(--tut-accent-soft); color: var(--tut-accent); font-weight: 600; }

@media (max-width: 720px) {
  .tutorial-page__brand { display: none; }
  .legal-page__layout { padding: 24px 16px 56px; }
}
</style>
