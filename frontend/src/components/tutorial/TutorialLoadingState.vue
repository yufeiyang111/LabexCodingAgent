<template>
  <div class="tutorial-article-state" :class="{ 'is-error': error }">
    <template v-if="error">
      <div class="tutorial-article-state__icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
      </div>
      <p>{{ error }}</p>
      <button type="button" @click="$emit('retry')">重新加载</button>
    </template>
    <div v-else class="tutorial-skeleton" role="status" aria-label="正在加载教程…">
      <div class="tutorial-skeleton__line tutorial-skeleton__line--chip"></div>
      <div class="tutorial-skeleton__line tutorial-skeleton__line--title"></div>
      <div class="tutorial-skeleton__line tutorial-skeleton__line--full"></div>
      <div class="tutorial-skeleton__line tutorial-skeleton__line--full"></div>
      <div class="tutorial-skeleton__line tutorial-skeleton__line--half"></div>
      <div class="tutorial-skeleton__line tutorial-skeleton__line--half"></div>
    </div>
  </div>
</template>

<script setup>
defineProps({ error: { type: String, default: '' } })
defineEmits(['retry'])
</script>
