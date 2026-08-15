<template>
  <span class="fi" :class="{ 'fi-selected': selected }">
    <svg
      v-if="kind === 'folder'"
      :width="size"
      :height="size"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path v-if="open" d="M13 3h5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h3l2 2h6"/>
      <path v-else d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
    </svg>
    <component v-else :is="iconComponent" :width="size" :height="size" />
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { fileIconFor } from './fileIcons'

const props = defineProps({
  name: { type: String, default: '' },
  kind: { type: String, default: '' },
  selected: { type: Boolean, default: false },
  open: { type: Boolean, default: false },
  size: { type: [Number, String], default: 14 }
})

const iconComponent = computed(() => fileIconFor(props.name))
</script>

<style scoped>
.fi {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  line-height: 0;
  color: #9ca3af;
  transition: color 0.15s ease;
}
.fi.fi-selected {
  color: #6366f1;
}
</style>
