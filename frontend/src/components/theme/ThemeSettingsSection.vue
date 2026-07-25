<template>
  <section class="theme-settings-section" :aria-labelledby="`${setting.key}-title`">
    <header class="theme-settings-section__header">
      <h3 :id="`${setting.key}-title`">{{ setting.title }}</h3>
      <button type="button" class="theme-settings-section__reset" :title="`恢复${setting.title}默认设置`" :aria-label="`恢复${setting.title}默认设置`" @click="$emit('reset')">↻</button>
    </header>
    <div class="theme-settings-section__grid" :style="gridStyle">
      <ThemeChoiceCard
        v-for="option in options"
        :key="option.value"
        :compact="setting.compact"
        :kind="setting.key"
        :option="option"
        :selected="value === option.value"
        @select="$emit('select', $event)"
      />
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import ThemeChoiceCard from './ThemeChoiceCard.vue'

const props = defineProps({ options: { type: Array, required: true }, setting: { type: Object, required: true }, value: { type: String, required: true } })
defineEmits(['reset', 'select'])
const gridStyle = computed(() => ({ '--theme-settings-columns': props.setting.columns }))
</script>

<style scoped>
.theme-settings-section { margin-bottom: 27px; }
.theme-settings-section:last-child { margin-bottom: 0; }
.theme-settings-section__header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.theme-settings-section__header h3 { margin: 0; color: var(--theme-panel-text); font-size: 17px; font-weight: 750; letter-spacing: -.01em; }
.theme-settings-section__reset { width: 24px; height: 24px; border: 0; border-radius: 50%; background: transparent; color: var(--theme-panel-text-muted); font: inherit; font-size: 24px; line-height: 1; cursor: pointer; }
.theme-settings-section__reset:hover { color: var(--theme-panel-text); background: var(--theme-panel-muted); }
.theme-settings-section__reset:focus-visible { outline: 2px solid var(--theme-accent); outline-offset: 2px; }
.theme-settings-section__grid { display: grid; grid-template-columns: repeat(var(--theme-settings-columns), minmax(0, 1fr)); gap: 16px 13px; }
@media (max-width: 530px) { .theme-settings-section__grid { grid-template-columns: repeat(min(3, var(--theme-settings-columns)), minmax(0, 1fr)); } }
</style>
