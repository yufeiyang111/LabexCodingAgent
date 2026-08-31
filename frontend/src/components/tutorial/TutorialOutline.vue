<template>
  <div class="tutorial-outline">
    <div class="tutorial-outline__label">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      <span>本文内容</span>
    </div>
    <a
      v-for="heading in headings"
      :key="heading.id"
      :href="`#${heading.id}`"
      class="tutorial-outline__item"
      :class="[`level-${heading.level}`, { 'is-active': heading.id === activeId }]"
      @click="onClick($event, heading)"
    >{{ heading.text }}</a>
    <div v-if="headings.length === 0" class="tutorial-outline__empty">暂无章节</div>
  </div>
</template>

<script setup>
defineProps({
  headings: { type: Array, default: () => [] },
  activeId: { type: String, default: '' }
})

function onClick(event, heading) {
  event.preventDefault()
  const el = window.document.getElementById(heading.id)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  window.history.replaceState(null, '', `#${heading.id}`)
}
</script>
