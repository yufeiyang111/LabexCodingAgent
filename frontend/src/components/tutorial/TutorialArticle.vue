<template>
  <div ref="rootRef" class="tutorial-article-slot">
    <article v-if="document" class="tutorial-article">
      <header class="tutorial-article__header">
        <div class="tutorial-article__category">{{ document.category }}</div>
        <h1>{{ document.title }}</h1>
        <p v-if="document.summary" class="tutorial-article__summary">{{ document.summary }}</p>
        <div class="tutorial-article__meta">更新于 {{ formatDate(document.updatedAt || document.publishedAt) }}</div>
      </header>
      <div class="tutorial-article__body markdown-rendered" v-html="renderedContent"></div>
    </article>
    <div v-else class="tutorial-article__empty">
      <svg class="tutorial-article__empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
      选择左侧教程开始阅读
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { enhanceTutorialBody, renderTutorialHtml } from '@/components/tutorial/tutorialMarkdown'

const props = defineProps({ document: { type: Object, default: null } })

const rootRef = ref(null)

const renderedContent = computed(() => renderTutorialHtml(props.document?.contentMarkdown))

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { dateStyle: 'medium' })
}

function enhance() {
  nextTick(() => {
    enhanceTutorialBody(rootRef.value?.querySelector('.tutorial-article__body'))
  })
}

onMounted(enhance)
watch(() => props.document?.id, enhance)
watch(() => props.document?.contentMarkdown, enhance)
</script>






