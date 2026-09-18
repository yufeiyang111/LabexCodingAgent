<template>
  <!-- 顶栏：返回 + 项目名 + 4 个视图切换 + 教程/导出 -->
  <header class="ide__topbar">
    <div class="ide__tb-side">
      <button type="button" class="ide__tbtn" title="返回项目列表">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M15 18l-6-6 6-6" />
        </svg>
        <span>返回</span>
      </button>
      <span class="ide__proj">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
        </svg>
        <span>work-4425</span>
      </span>
    </div>

    <div class="ide__tb-mid">
      <!-- 视图切换：前两个（资源管理器 / AI 面板）联动真实布局，后两个为纯展示 -->
      <span class="ide__vbtn" :class="{ 'is-on': sidebarOn }" title="开关资源管理器 (Ctrl+B)" @click="$emit('toggle-sidebar')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M9 4v16" />
        </svg>
      </span>
      <span class="ide__vbtn" :class="{ 'is-on': terminalOn }" title="开关底部终端 (Ctrl+`)" @click="terminalOn = !terminalOn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M3 10h18" />
        </svg>
      </span>
      <span class="ide__vbtn" :class="{ 'is-on': previewOn }" title="开关 Web 实时预览视窗" @click="previewOn = !previewOn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      </span>
      <span class="ide__vbtn" :class="{ 'is-on': aiOn }" title="开关 AI 助手面板 (Ctrl+Shift+L)" @click="$emit('toggle-ai')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M15 4v16" />
        </svg>
      </span>
    </div>

    <div class="ide__tb-side">
      <button type="button" class="ide__tbtn" title="查看教程">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M4 19.5V6a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2.5z" />
        </svg>
        <span>教程</span>
      </button>
      <button type="button" class="ide__tbtn" title="导出项目">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M12 3v12M7 10l5 5 5-5M4 21h16" />
        </svg>
        <span>导出</span>
      </button>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  // 资源管理器 / AI 面板是否可见，与下方布局联动
  sidebarOn: { type: Boolean, default: true },
  aiOn: { type: Boolean, default: true },
})

defineEmits(['toggle-sidebar', 'toggle-ai'])

// 终端 / 预览按钮为纯展示高亮，不参与布局变化
const terminalOn = ref(false)
const previewOn = ref(false)
</script>
