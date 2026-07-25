<template>
  <div class="theme-preview" :class="[`theme-preview--${kind}`, `theme-preview--${kind}-${option.value}`]" :style="previewStyle" aria-hidden="true">
    <template v-if="kind === 'colorPreset'">
      <span class="theme-preview__swatch"></span>
    </template>
    <template v-else-if="kind === 'fontFamily'">
      <span class="theme-preview__letters" :class="`theme-preview__letters--${option.value}`">{{ option.preview }}</span>
    </template>
    <template v-else-if="kind === 'radius'">
      <span class="theme-preview__corner"></span>
    </template>
    <template v-else-if="kind === 'density'">
      <span v-for="line in 3" :key="line" class="theme-preview__line" :style="{ width: `${72 - line * 12}%` }"></span>
    </template>
    <template v-else-if="kind === 'sidebar'">
      <span class="theme-preview__rail"></span><span class="theme-preview__content"></span>
    </template>
    <template v-else-if="kind === 'contentWidth'">
      <span class="theme-preview__content-width"></span>
      <span class="theme-preview__line"></span>
    </template>
    <template v-else>
      <span class="theme-preview__rail"></span>
      <span class="theme-preview__canvas"><i></i><i></i><i></i></span>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  kind: { type: String, required: true },
  option: { type: Object, required: true },
})

const previewStyle = computed(() => props.kind === 'colorPreset'
  ? { '--theme-preview-color': props.option.preview }
  : {})
</script>

<style scoped>
.theme-preview { position: relative; display: flex; align-items: center; gap: 7px; min-height: 64px; width: 100%; overflow: hidden; border: 1px solid color-mix(in srgb, var(--theme-panel-border) 86%, transparent); border-radius: calc(var(--theme-radius) * .8); background: color-mix(in srgb, var(--theme-panel-muted) 92%, var(--theme-panel)); transition: border-color .16s ease, transform .16s ease; }
.theme-preview__rail { width: 27%; align-self: stretch; background: color-mix(in srgb, var(--theme-text-muted) 28%, var(--theme-panel)); }
.theme-preview__content { flex: 1; align-self: stretch; margin: 10px; margin-left: 2px; border-radius: calc(var(--theme-radius) * .55); background: color-mix(in srgb, var(--theme-text-muted) 38%, var(--theme-panel)); }
.theme-preview__canvas { display: grid; grid-template-columns: 1fr 1.45fr; grid-template-rows: repeat(2, 1fr); gap: 4px; flex: 1; align-self: stretch; padding: 9px 9px 9px 2px; }
.theme-preview__canvas i { display: block; border-radius: calc(var(--theme-radius) * .35); background: color-mix(in srgb, var(--theme-text-muted) 42%, var(--theme-panel)); }
.theme-preview__canvas i:first-child { grid-row: span 2; }
.theme-preview__swatch { position: absolute; inset: 0; background: var(--theme-preview-color); }
.theme-preview__letters { width: 100%; text-align: center; color: var(--theme-panel-text); font-size: 30px; font-weight: 600; }
.theme-preview__letters--serif { font-family: Georgia, 'Times New Roman', serif; font-weight: 500; }
.theme-preview__corner { width: 36px; height: 36px; border-top: 2px solid var(--theme-panel-text-muted); border-left: 2px solid var(--theme-panel-text-muted); border-top-left-radius: 18px; margin-left: 22px; }
.theme-preview--radius-0 .theme-preview__corner { border-top-left-radius: 0; }
.theme-preview--radius-0\.3 .theme-preview__corner { border-top-left-radius: 7px; }
.theme-preview--radius-0\.5 .theme-preview__corner { border-top-left-radius: 11px; }
.theme-preview--radius-0\.75 .theme-preview__corner { border-top-left-radius: 15px; }
.theme-preview--radius-1\.0 .theme-preview__corner { border-top-left-radius: 20px; }
.theme-preview--density { flex-direction: column; justify-content: center; align-items: flex-start; gap: 4px; padding-left: 18px; }
.theme-preview--density-compact { gap: 2px; }
.theme-preview--density-relaxed { gap: 6px; }
.theme-preview--density-spacious { gap: 9px; }
.theme-preview__line { display: block; height: 3px; border-radius: 999px; background: var(--theme-panel-text-muted); }
.theme-preview--sidebar-floating { padding: 7px; background: transparent; }
.theme-preview--sidebar-floating .theme-preview__rail, .theme-preview--sidebar-floating .theme-preview__content { border-radius: calc(var(--theme-radius) * .65); }
.theme-preview--sidebar-sidebar .theme-preview__rail { width: 22%; background: color-mix(in srgb, var(--theme-accent) 28%, var(--theme-panel)); }
.theme-preview--layout-compact .theme-preview__canvas { padding: 5px 6px 5px 2px; gap: 3px; }
.theme-preview--layout-fullscreen .theme-preview__canvas { padding: 6px 5px 6px 1px; }
.theme-preview--contentWidth { flex-direction: column; align-items: stretch; justify-content: center; gap: 8px; padding: 12px; }
.theme-preview__content-width { display: block; height: 11px; width: 100%; border-radius: 99px; background: color-mix(in srgb, var(--theme-text-muted) 58%, var(--theme-panel)); }
.theme-preview--contentWidth .theme-preview__line { width: 72%; }
.theme-preview--contentWidth-centered .theme-preview__content-width, .theme-preview--contentWidth-centered .theme-preview__line { width: 56%; align-self: center; }
.theme-preview--direction-rtl { flex-direction: row-reverse; }
.theme-preview--direction-rtl .theme-preview__canvas { padding: 9px 2px 9px 9px; }
</style>


