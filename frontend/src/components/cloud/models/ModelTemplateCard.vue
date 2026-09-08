<template>
  <button
    type="button"
    class="mc-template-card"
    :title="`选择 ${template.name} 模板`"
    @click="$emit('select', template)"
  >
    <ModelVendorIcon
      :icon-key="template.iconKey"
      :name="template.name"
      :fallback-text="template.iconText"
      :accent="template.accent"
      :size="38"
    />
    <span class="mc-template-main">
      <span class="mc-template-name-row">
        <span class="mc-template-name">{{ template.name }}</span>
        <span v-if="template.categoryLabel" class="mc-template-tag">{{ template.categoryLabel }}</span>
      </span>
      <span class="mc-template-vendor">{{ template.vendor }}</span>
      <span class="mc-template-model">{{ template.modelName || '手动填写模型名称' }}</span>
    </span>
    <span v-if="template.modelsUrl" class="mc-template-source">官方列表</span>
  </button>
</template>

<script setup>
import ModelVendorIcon from './ModelVendorIcon.vue'

defineProps({
  template: {
    type: Object,
    required: true
  }
})

defineEmits(['select'])
</script>

<style scoped>
.mc-template-card {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--ai-border-strong, #e5e7eb);
  border-radius: 10px;
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text, #111827);
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  box-sizing: border-box;
  width: 100%;
}

.mc-template-card:hover {
  border-color: var(--ai-accent, #2563eb);
  background: var(--ai-accent-bg, #eff6ff);
  box-shadow: 0 4px 12px -2px rgba(37, 99, 235, 0.12);
  transform: translateY(-1px);
}

.mc-template-card:focus-visible {
  outline: 2px solid var(--ai-accent, #2563eb);
  outline-offset: 1px;
}

.mc-template-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mc-template-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.mc-template-name {
  color: var(--ai-text, #111827);
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mc-template-tag {
  font-size: 9px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(100, 116, 139, 0.12);
  color: var(--ai-text-muted, #64748b);
  font-weight: 500;
  flex-shrink: 0;
}

.mc-template-vendor,
.mc-template-model {
  color: var(--ai-text-muted, #64748b);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mc-template-model {
  color: var(--ai-text-faint, #94a3b8);
  font-family: 'JetBrains Mono', monospace, Consolas;
}

.mc-template-source {
  align-self: flex-start;
  padding: 2px 7px;
  border-radius: 6px;
  background: var(--ai-accent-bg, #eff6ff);
  color: var(--ai-accent, #2563eb);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}
</style>
