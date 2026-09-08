<template>
  <div class="mc-template-selector">
    <!-- 顶部工具栏：搜索与分类过滤 -->
    <div class="mc-template-toolbar">
      <div class="mc-template-search-wrapper">
        <svg class="mc-template-search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/>
          <line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input
          v-model.trim="searchKeyword"
          class="mc-template-search-input"
          placeholder="搜索厂商、模型名称或关键词..."
        />
        <button
          v-if="searchKeyword"
          type="button"
          class="mc-template-search-clear"
          @click="searchKeyword = ''"
        >
          &times;
        </button>
      </div>

      <!-- 分类筛选器 -->
      <div class="mc-template-categories">
        <button
          v-for="cat in categories"
          :key="cat.key"
          type="button"
          class="mc-template-category-tab"
          :class="{ active: currentCategory === cat.key }"
          @click="currentCategory = cat.key"
        >
          {{ cat.label }}
          <span class="mc-category-count">{{ getCategoryCount(cat.key) }}</span>
        </button>
      </div>
    </div>

    <!-- 模板网格卡片区 -->
    <div class="mc-template-grid-scroll">
      <div v-if="filteredTemplates.length > 0" class="mc-template-grid">
        <ModelTemplateCard
          v-for="tpl in filteredTemplates"
          :key="tpl.name"
          :template="tpl"
          @select="$emit('select', $event)"
        />
      </div>

      <!-- 无搜索结果提示 -->
      <div v-else class="mc-template-no-results">
        <p>未找到匹配的厂商模板</p>
        <button type="button" class="mc-btn mc-btn-outline mc-btn-small" @click="resetFilters">
          清除筛选条件
        </button>
      </div>
    </div>

    <!-- 底部操作区 -->
    <div class="mc-form-actions mc-template-actions">
      <button type="button" class="mc-btn mc-btn-outline" @click="$emit('back')">
        返回配置列表
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ModelTemplateCard from './ModelTemplateCard.vue'

const props = defineProps({
  templates: {
    type: Array,
    default: () => []
  }
})

defineEmits(['select', 'back'])

const searchKeyword = ref('')
const currentCategory = ref('all')

const categories = [
  { key: 'all', label: '全部' },
  { key: 'china', label: '国内主流' },
  { key: 'global', label: '国际前沿' },
  { key: 'hub', label: '聚合与算力' }
]

function getCategoryCount(catKey) {
  if (catKey === 'all') return props.templates.length
  return props.templates.filter(t => t.category === catKey).length
}

const filteredTemplates = computed(() => {
  let list = props.templates

  // 1. 分类过滤
  if (currentCategory.value !== 'all') {
    list = list.filter(t => {
      // 自定义模板始终展示在第一位供用户快捷选择
      if (t.custom) return true
      return t.category === currentCategory.value
    })
  }

  // 2. 关键词搜索
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(t => {
      return (
        (t.name && t.name.toLowerCase().includes(kw)) ||
        (t.vendor && t.vendor.toLowerCase().includes(kw)) ||
        (t.modelName && t.modelName.toLowerCase().includes(kw)) ||
        (t.note && t.note.toLowerCase().includes(kw))
      )
    })
  }

  return list
})

function resetFilters() {
  searchKeyword.value = ''
  currentCategory.value = 'all'
}
</script>

<style scoped>
.mc-template-selector {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 480px;
}

.mc-template-toolbar {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mc-template-search-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.mc-template-search-icon {
  position: absolute;
  left: 12px;
  color: var(--ai-text-muted, #94a3b8);
  pointer-events: none;
}

.mc-template-search-input {
  width: 100%;
  height: 38px;
  padding: 0 34px 0 34px;
  border: 1px solid var(--ai-border-strong, #e2e8f0);
  border-radius: 8px;
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text, #0f172a);
  font-size: 13px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
}

.mc-template-search-input:focus {
  border-color: var(--ai-accent, #2563eb);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}

.mc-template-search-clear {
  position: absolute;
  right: 10px;
  background: transparent;
  border: none;
  font-size: 16px;
  line-height: 1;
  color: var(--ai-text-muted, #94a3b8);
  cursor: pointer;
  padding: 4px;
}

.mc-template-categories {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.mc-template-category-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid var(--ai-border, #e2e8f0);
  background: var(--ai-card-bg, #f8fafc);
  color: var(--ai-text-muted, #64748b);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.mc-template-category-tab:hover {
  background: var(--ai-bg-hover, #f1f5f9);
  color: var(--ai-text, #0f172a);
}

.mc-template-category-tab.active {
  background: var(--ai-accent, #2563eb);
  border-color: var(--ai-accent, #2563eb);
  color: #ffffff;
}

.mc-category-count {
  font-size: 10px;
  opacity: 0.85;
  background: rgba(0, 0, 0, 0.08);
  padding: 1px 5px;
  border-radius: 10px;
}

.mc-template-category-tab.active .mc-category-count {
  background: rgba(255, 255, 255, 0.25);
  color: #ffffff;
}

.mc-template-grid-scroll {
  max-height: 520px;
  overflow-y: auto;
  padding-right: 4px;
}

.mc-template-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 640px) {
  .mc-template-grid {
    grid-template-columns: 1fr;
  }
}

.mc-template-no-results {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
  color: var(--ai-text-muted, #94a3b8);
  gap: 12px;
}

.mc-template-no-results p {
  margin: 0;
  font-size: 14px;
}

.mc-template-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
}
</style>
