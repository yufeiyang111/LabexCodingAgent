<template>
  <aside class="tutorial-sidebar">
    <div class="tutorial-sidebar__header">
      <span class="tutorial-sidebar__eyebrow">使用手册</span>
      <h2>使用教程</h2>
      <p>从配置模型到完成一次开发任务，按需查阅。</p>
    </div>
    <div v-if="loading" class="tutorial-sidebar__loading">正在加载目录…</div>
    <div v-else-if="error" class="tutorial-sidebar__error">{{ error }}</div>
    <nav v-else class="tutorial-sidebar__nav" aria-label="教程目录">
      <div v-for="group in groups" :key="group.category" class="tutorial-sidebar__group">
        <div class="tutorial-sidebar__category">
          <svg class="tutorial-sidebar__category-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" v-html="categoryIcon(group.category)"></svg>
          <span>{{ group.category }}</span>
        </div>
        <button
          v-for="item in group.items"
          :key="item.slug"
          type="button"
          class="tutorial-sidebar__item"
          :class="{ 'is-active': item.slug === activeSlug }"
          @click="$emit('select', item.slug)"
        >
          <span>{{ item.title }}</span>
        </button>
      </div>
      <div v-if="items.length === 0" class="tutorial-sidebar__empty">暂时没有已发布教程</div>
    </nav>
  </aside>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  activeSlug: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
defineEmits(['select'])

const categoryIcons = [
  { match: /开始|快速|入门/, icon: '<circle cx="12" cy="12" r="10"/><polygon points="10 8 16 12 10 16 10 8"/>' },
  { match: /模型|配置/, icon: '<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/>' },
  { match: /agent/i, icon: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>' },
  { match: /安全|权限/, icon: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>' },
  { match: /排查|问题|故障/, icon: '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4"/><line x1="4.93" y1="4.93" x2="9.17" y2="9.17"/><line x1="14.83" y1="14.83" x2="19.07" y2="19.07"/><line x1="14.83" y1="9.17" x2="19.07" y2="4.93"/><line x1="4.93" y1="19.07" x2="9.17" y2="14.83"/>' },
  { match: /进阶|MCP|技能|扩展/, icon: '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>' },
  { match: /操作|文件|项目/, icon: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>' }
]

const defaultIcon = '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>'

function categoryIcon(category) {
  const found = categoryIcons.find(entry => entry.match.test(String(category || '')))
  return found ? found.icon : defaultIcon
}

const groups = computed(() => {
  const map = new Map()
  props.items.forEach(item => {
    const category = item.category || '其他'
    if (!map.has(category)) map.set(category, [])
    map.get(category).push(item)
  })
  return Array.from(map, ([category, items]) => ({ category, items }))
})
</script>
