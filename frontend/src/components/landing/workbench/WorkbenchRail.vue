<template>
  <!-- 活动栏：3 个竖向按钮，资源管理器联动侧栏展开态，其余为展示高亮 -->
  <nav class="ide__rail">
    <button
      type="button"
      class="ide__railbtn"
      :class="{ 'is-on': explorerOn }"
      title="资源管理器"
      aria-label="资源管理器"
      @click="$emit('toggle-explorer')"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
        <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
        <path d="M13 2v7h7" />
      </svg>
    </button>
    <button
      type="button"
      class="ide__railbtn"
      :class="{ 'is-on': searchOn }"
      title="全局搜索"
      aria-label="全局搜索"
      @click="searchOn = !searchOn"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
        <circle cx="11" cy="11" r="7" />
        <path d="M20 20l-3.5-3.5" />
      </svg>
    </button>
    <button
      type="button"
      class="ide__railbtn"
      :class="{ 'is-on': historyOn }"
      title="历史会话"
      aria-label="历史会话"
      @click="historyOn = !historyOn"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
        <path d="M12 8v4l3 2" />
        <circle cx="12" cy="12" r="9" />
      </svg>
    </button>
  </nav>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  // 资源管理器按钮高亮态，与侧栏显隐联动
  explorerOn: { type: Boolean, default: true },
})

defineEmits(['toggle-explorer'])

// 搜索 / 历史为展示高亮
const searchOn = ref(false)
const historyOn = ref(false)
</script>
