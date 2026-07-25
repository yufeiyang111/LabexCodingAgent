<template>
  <button type="button" class="theme-choice-card" :class="{ 'theme-choice-card--selected': selected, 'theme-choice-card--compact': compact }" :aria-pressed="selected" @click="$emit('select', option.value)">
    <ThemePreview :kind="kind" :option="option" />
    <span class="theme-choice-card__label">{{ option.label }}</span>
    <span v-if="selected" class="theme-choice-card__check" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="m5 12 4 4L19 6" /></svg></span>
  </button>
</template>

<script setup>
import ThemePreview from './ThemePreview.vue'

defineProps({ compact: Boolean, kind: { type: String, required: true }, option: { type: Object, required: true }, selected: Boolean })
defineEmits(['select'])
</script>

<style scoped>
.theme-choice-card { position: relative; display: flex; min-width: 0; flex-direction: column; gap: 8px; padding: 0; border: 0; background: transparent; color: var(--theme-panel-text); text-align: left; font: inherit; cursor: pointer; }
.theme-choice-card:focus-visible { outline: 3px solid color-mix(in srgb, var(--theme-accent) 58%, transparent); outline-offset: 4px; border-radius: calc(var(--theme-radius) + 4px); }
.theme-choice-card__label { overflow: hidden; color: var(--theme-panel-text); font-size: 13px; font-weight: 650; line-height: 1.2; text-align: center; text-overflow: ellipsis; white-space: nowrap; }
.theme-choice-card:hover :deep(.theme-preview) { border-color: var(--theme-accent); transform: translateY(-1px); }
.theme-choice-card--selected :deep(.theme-preview) { border-color: var(--theme-panel-text); box-shadow: 0 0 0 1px var(--theme-panel-text); }
.theme-choice-card__check { position: absolute; top: -10px; right: -8px; z-index: 1; display: grid; width: 29px; height: 29px; place-items: center; border: 3px solid var(--theme-panel); border-radius: 50%; background: var(--theme-panel-text); color: var(--theme-panel); box-shadow: 0 5px 18px rgba(0, 0, 0, .18); }
.theme-choice-card__check svg { width: 16px; height: 16px; }
.theme-choice-card--compact :deep(.theme-preview) { min-height: 52px; }
.theme-choice-card--compact .theme-choice-card__label { font-size: 12px; }
</style>
